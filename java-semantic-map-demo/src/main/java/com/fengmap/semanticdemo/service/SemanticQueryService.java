package com.fengmap.semanticdemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fengmap.semanticdemo.model.DemoMap;
import com.fengmap.semanticdemo.model.Point2D;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面向 Demo 的实体检索、楼层加载和邻近查询。
 */
@Service
public class SemanticQueryService {

    private static final List<String> QUERY_PREFIXES = List.of(
            "请给我找一个", "请给我找个", "给我找一个", "给我找个", "请帮我找一下",
            "请帮我找", "帮我找一下", "我想找一下", "帮我找个", "我想找个", "帮我找",
            "我想找", "我想去", "我想要", "我要找", "带我去", "请问",
            "想找一下", "给我找", "找一下", "想找", "寻找", "找个", "想去", "前往", "我要", "我想"
    );
    private static final List<String> QUERY_QUALIFIERS = List.of(
            "离我最近的", "离当前位置最近的", "当前位置附近的", "最近的", "附近的", "周边的"
    );
    private static final List<String> QUERY_SUFFIXES = List.of(
            "在哪里呢", "在哪里", "在哪儿", "在哪", "怎么走", "怎么去", "有吗", "呢", "一下"
    );
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("会员中心", List.of("会员中心", "会员服务中心", "VIP中心", "贵宾中心", "服务中心")),
            Map.entry("会员服务", List.of("会员中心", "会员服务中心", "服务中心")),
            Map.entry("vip", List.of("会员中心", "贵宾中心", "VIP中心")),
            Map.entry("厕所", List.of("洗手间", "卫生间")),
            Map.entry("卫生间", List.of("洗手间", "卫生间")),
            Map.entry("洗手间", List.of("洗手间", "卫生间")),
            Map.entry("服务台", List.of("咨询台", "服务中心")),
            Map.entry("客服中心", List.of("服务中心", "咨询台")),
            Map.entry("直梯", List.of("直升电梯", "电梯")),
            Map.entry("电梯", List.of("直升电梯", "电梯")),
            Map.entry("扶梯", List.of("手扶电梯", "扶梯"))
    );

    private final DemoMapRepository repository;
    private final OllamaEmbeddingService embeddingService;

    public SemanticQueryService(DemoMapRepository repository, OllamaEmbeddingService embeddingService) {
        this.repository = repository;
        this.embeddingService = embeddingService;
    }

    public List<Map<String, Object>> maps() {
        return repository.availableMapIds().stream().map(mapId -> {
            DemoMap map = repository.get(mapId);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("mapId", mapId);
            value.put("semanticId", map.manifest().path("semanticId").asText());
            value.put("coordinateReferenceSystem", map.manifest().path("coordinateReferenceSystem"));
            value.put("recordCounts", map.manifest().path("recordCounts"));
            value.put("embeddingConfigured", embeddingService.configured());
            return value;
        }).toList();
    }

    public JsonNode floors(String mapId) {
        return repository.get(mapId).manifest().path("floors");
    }

    public List<JsonNode> floorEntities(String mapId, String floorName, boolean includePaths) {
        return repository.get(mapId).entities().stream()
                .filter(entity -> floorName.equalsIgnoreCase(entity.path("floorName").asText()))
                .filter(entity -> includePaths || !"NAVIGATION_PATH".equals(entity.path("kind").asText()))
                .toList();
    }

    public JsonNode entity(String mapId, String entityId) {
        JsonNode entity = repository.get(mapId).entitiesById().get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("实体不存在: " + entityId);
        }
        return entity;
    }

    public List<Map<String, Object>> search(String mapId, String keyword, int requestedLimit) {
        return searchWithIntent(mapId, keyword, requestedLimit).results();
    }

    public SearchResponse searchWithIntent(String mapId, String keyword, int requestedLimit) {
        SearchIntent intent = searchIntent(keyword);
        if (intent.terms().isEmpty()) {
            return new SearchResponse("", false, "RULE_BASED", null, List.of());
        }
        List<Map<String, Object>> ruleResults = searchResults(mapId, intent, requestedLimit);
        if (!embeddingService.configured()) {
            return new SearchResponse(intent.target(), intent.nearestRequested(),
                    "RULE_BASED", null, ruleResults);
        }
        try {
            List<Map<String, Object>> results = mergeSemanticResults(
                    ruleResults,
                    embeddingService.search(mapId, intent.target(), requestedLimit),
                    requestedLimit
            );
            return new SearchResponse(intent.target(), intent.nearestRequested(),
                    "OLLAMA_HYBRID", "已使用 Ollama 进行语义召回。", results);
        } catch (RuntimeException exception) {
            // 外部 Embedding 服务不可用时，公开 Demo 仍需保持基础检索能力。
            return new SearchResponse(intent.target(), intent.nearestRequested(),
                    "RULE_BASED_FALLBACK", "Ollama 语义检索暂不可用，已回退到规则检索。", ruleResults);
        }
    }

    private static List<Map<String, Object>> mergeSemanticResults(
            List<Map<String, Object>> ruleResults,
            List<OllamaEmbeddingService.SemanticCandidate> semanticResults,
            int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> result : ruleResults) {
            Map<String, Object> value = new LinkedHashMap<>(result);
            value.put("matchSource", "RULE");
            merged.put(value.get("id").toString(), value);
        }
        for (OllamaEmbeddingService.SemanticCandidate candidate : semanticResults) {
            String id = candidate.entity().path("id").asText();
            Map<String, Object> existing = merged.get(id);
            if (existing != null) {
                existing.put("matchSource", "HYBRID");
                existing.put("semanticScore", roundedScore(candidate.score()));
            } else {
                Map<String, Object> value = summary(candidate.entity(), null);
                value.put("matchSource", "OLLAMA");
                value.put("semanticScore", roundedScore(candidate.score()));
                merged.put(id, value);
            }
            if (merged.size() >= limit) {
                break;
            }
        }
        return merged.values().stream().limit(limit).toList();
    }

    private List<Map<String, Object>> searchResults(String mapId, SearchIntent intent, int requestedLimit) {
        if (intent.terms().isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        return repository.get(mapId).entities().stream()
                .filter(entity -> !"NAVIGATION_PATH".equals(entity.path("kind").asText()))
                .filter(entity -> !"FLOOR".equals(entity.path("kind").asText()))
                .map(entity -> new SearchCandidate(entity, score(entity, intent.terms())))
                .filter(candidate -> candidate.score() < Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt(SearchCandidate::score)
                        .thenComparing(candidate -> candidate.entity().path("name").asText())
                        .thenComparing(candidate -> candidate.entity().path("id").asText()))
                .limit(limit)
                .map(candidate -> summary(candidate.entity(), null))
                .toList();
    }

    public List<Map<String, Object>> nearby(String mapId, String entityId, int requestedLimit) {
        DemoMap map = repository.get(mapId);
        JsonNode source = entity(mapId, entityId);
        Point2D sourcePoint = GeometrySupport.representativePoint(source)
                .orElseThrow(() -> new IllegalArgumentException("实体没有可用于邻近计算的几何"));
        String floorName = source.path("floorName").asText();
        int limit = Math.max(1, Math.min(requestedLimit, 50));

        List<NearbyCandidate> candidates = new ArrayList<>();
        for (JsonNode target : map.entities()) {
            if (entityId.equals(target.path("id").asText())
                    || !floorName.equalsIgnoreCase(target.path("floorName").asText())
                    || "FLOOR".equals(target.path("kind").asText())
                    || "NAVIGATION_PATH".equals(target.path("kind").asText())) {
                continue;
            }
            GeometrySupport.representativePoint(target).ifPresent(point ->
                    candidates.add(new NearbyCandidate(target, sourcePoint.distance(point))));
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(NearbyCandidate::distance)
                        .thenComparing(value -> value.entity().path("id").asText()))
                .limit(limit)
                .map(value -> summary(value.entity(), rounded(value.distance())))
                .toList();
    }

    private static int score(JsonNode entity, List<String> searchTerms) {
        int best = Integer.MAX_VALUE;
        for (int index = 0; index < searchTerms.size(); index++) {
            int termScore = scoreTerm(entity, searchTerms.get(index));
            if (termScore < Integer.MAX_VALUE) {
                // 清洗后的原始意图优先，同义词只作为召回补充。
                best = Math.min(best, termScore + index * 10);
            }
        }
        return best;
    }

    private static int scoreTerm(JsonNode entity, String keyword) {
        String name = entity.path("name").asText().toLowerCase(Locale.ROOT);
        String typeLabel = entity.path("semanticProperties").path("typeLabel")
                .asText().toLowerCase(Locale.ROOT);
        if (name.equals(keyword)) {
            return 0;
        }
        if (name.startsWith(keyword)) {
            return 1;
        }
        if (name.contains(keyword)) {
            return 2;
        }
        if (typeLabel.equals(keyword)) {
            return 3;
        }
        if (typeLabel.contains(keyword)) {
            return 4;
        }
        return Integer.MAX_VALUE;
    }

    private static SearchIntent searchIntent(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new SearchIntent("", false, List.of());
        }
        String original = keyword.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、,.!?；;：:‘’“”\"']+", "");
        boolean nearestRequested = original.contains("最近")
                || original.contains("离我")
                || original.contains("当前位置附近");
        String cleaned = stripIntentWords(original);
        for (String qualifier : QUERY_QUALIFIERS) {
            if (cleaned.startsWith(qualifier) && cleaned.length() > qualifier.length()) {
                cleaned = cleaned.substring(qualifier.length());
                break;
            }
        }
        if (cleaned.isBlank()) {
            return new SearchIntent("", nearestRequested, List.of());
        }

        String target = cleaned;
        Set<String> terms = new LinkedHashSet<>();
        terms.add(target);
        SYNONYMS.forEach((trigger, values) -> {
            if (target.contains(trigger)) {
                terms.addAll(values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList());
            }
        });
        return new SearchIntent(target, nearestRequested, List.copyOf(terms));
    }

    private static String stripIntentWords(String value) {
        String result = value;
        boolean changed;
        do {
            changed = false;
            for (String prefix : QUERY_PREFIXES) {
                if (result.startsWith(prefix) && result.length() > prefix.length()) {
                    result = result.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        do {
            changed = false;
            for (String suffix : QUERY_SUFFIXES) {
                if (result.endsWith(suffix) && result.length() > suffix.length()) {
                    result = result.substring(0, result.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }

    private static Map<String, Object> summary(JsonNode entity, Double distance) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", entity.path("id").asText());
        value.put("name", entity.path("name").asText(entity.path("subtype").asText()));
        value.put("kind", entity.path("kind").asText());
        value.put("subtype", entity.path("subtype").asText());
        value.put("floorName", entity.path("floorName").asText());
        value.put("typeLabel", entity.path("semanticProperties").path("typeLabel").asText(null));
        value.put("point", GeometrySupport.representativePoint(entity).orElse(null));
        if (distance != null) {
            value.put("distance", distance);
        }
        return value;
    }

    private static double rounded(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private static double roundedScore(double value) {
        return Math.round(value * 10_000D) / 10_000D;
    }

    private record SearchCandidate(JsonNode entity, int score) {
    }

    private record NearbyCandidate(JsonNode entity, double distance) {
    }

    private record SearchIntent(String target, boolean nearestRequested, List<String> terms) {
    }

    public record SearchResponse(
            String interpretedTarget,
            boolean nearestRequested,
            String retrievalMode,
            String message,
            List<Map<String, Object>> results
    ) {
    }
}
