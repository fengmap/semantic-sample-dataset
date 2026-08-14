package com.fengmap.semanticdemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fengmap.semanticdemo.config.DemoProperties;
import com.fengmap.semanticdemo.model.DemoMap;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用 Ollama Embedding API，并为每张地图构建进程内向量索引。
 *
 * <p>索引不写入地图交付包，也不要求数据库或向量数据库。首次查询一张地图时批量生成实体向量，
 * 后续查询直接复用内存缓存；进程重启后会按需重新生成。</p>
 */
@Service
public class OllamaEmbeddingService {

    private final DemoMapRepository repository;
    private final ObjectMapper objectMapper;
    private final DemoProperties.Embedding properties;
    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final Map<String, List<IndexedEntity>> indexes = new ConcurrentHashMap<>();
    private final Map<String, Object> indexLocks = new ConcurrentHashMap<>();

    public OllamaEmbeddingService(
            DemoMapRepository repository,
            ObjectMapper objectMapper,
            DemoProperties demoProperties
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = demoProperties.getEmbedding();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        this.embedEndpoint = configured() ? URI.create(normalizeBaseUrl(properties.getBaseUrl()) + "/api/embed") : null;
    }

    public boolean configured() {
        return properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
    }

    /**
     * 返回相似度达到阈值的实体。调用失败时抛出异常，由上层决定是否回退到规则检索。
     */
    public List<SemanticCandidate> search(String mapId, String query, int requestedLimit) {
        if (!configured()) {
            return List.of();
        }
        List<IndexedEntity> index = index(mapId);
        float[] queryVector = embed(List.of(query)).get(0);
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        return index.stream()
                .map(value -> new SemanticCandidate(value.entity(), cosine(queryVector, value.vector())))
                .filter(value -> value.score() >= properties.getMinScore())
                .sorted(Comparator.comparingDouble(SemanticCandidate::score).reversed()
                        .thenComparing(value -> value.entity().path("id").asText()))
                .limit(limit)
                .toList();
    }

    private List<IndexedEntity> index(String mapId) {
        List<IndexedEntity> existing = indexes.get(mapId);
        if (existing != null) {
            return existing;
        }
        Object lock = indexLocks.computeIfAbsent(mapId, ignored -> new Object());
        synchronized (lock) {
            existing = indexes.get(mapId);
            if (existing != null) {
                return existing;
            }
            DemoMap map = repository.get(mapId);
            List<JsonNode> entities = map.entities().stream()
                    .filter(OllamaEmbeddingService::searchable)
                    .toList();
            List<IndexedEntity> values = new ArrayList<>(entities.size());
            int batchSize = Math.max(1, Math.min(properties.getBatchSize(), 256));
            for (int start = 0; start < entities.size(); start += batchSize) {
                int end = Math.min(start + batchSize, entities.size());
                List<JsonNode> batch = entities.subList(start, end);
                List<float[]> vectors = embed(batch.stream().map(OllamaEmbeddingService::document).toList());
                for (int index = 0; index < batch.size(); index++) {
                    values.add(new IndexedEntity(batch.get(index), vectors.get(index)));
                }
            }
            List<IndexedEntity> immutable = List.copyOf(values);
            indexes.put(mapId, immutable);
            indexLocks.remove(mapId, lock);
            return immutable;
        }
    }

    private List<float[]> embed(List<String> inputs) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("truncate", true);
        ArrayNode input = body.putArray("input");
        inputs.forEach(input::add);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(embedEndpoint)
                    .timeout(properties.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成 Ollama 请求", exception);
        }

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama 返回 HTTP " + response.statusCode());
            }
            JsonNode embeddings = objectMapper.readTree(response.body()).path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != inputs.size()) {
                throw new IllegalStateException("Ollama 返回的向量数量与输入数量不一致");
            }
            List<float[]> vectors = new ArrayList<>(embeddings.size());
            for (JsonNode embedding : embeddings) {
                if (!embedding.isArray() || embedding.size() != properties.getDimensions()) {
                    throw new IllegalStateException("Ollama 向量维度不是配置值 " + properties.getDimensions());
                }
                float[] vector = new float[embedding.size()];
                for (int index = 0; index < embedding.size(); index++) {
                    vector[index] = embedding.get(index).floatValue();
                }
                vectors.add(vector);
            }
            return List.copyOf(vectors);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Ollama 响应", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama 请求被中断", exception);
        }
    }

    private static boolean searchable(JsonNode entity) {
        String kind = entity.path("kind").asText();
        return !"NAVIGATION_PATH".equals(kind) && !"FLOOR".equals(kind);
    }

    private static String document(JsonNode entity) {
        List<String> parts = new ArrayList<>();
        add(parts, "名称", entity.path("name").asText());
        add(parts, "类型", entity.path("semanticProperties").path("typeLabel").asText());
        add(parts, "类别", entity.path("subtype").asText());
        JsonNode categoryPath = entity.path("semanticProperties").path("categoryPath");
        if (categoryPath.isArray()) {
            List<String> categories = new ArrayList<>();
            categoryPath.forEach(value -> {
                if (!value.asText().isBlank()) {
                    categories.add(value.asText());
                }
            });
            if (!categories.isEmpty()) {
                parts.add("分类：" + String.join("/", categories));
            }
        }
        return String.join("；", parts);
    }

    private static void add(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + "：" + value);
        }
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("http://")
                && !normalized.toLowerCase(Locale.ROOT).startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record IndexedEntity(JsonNode entity, float[] vector) {
    }

    public record SemanticCandidate(JsonNode entity, double score) {
    }
}
