package com.fengmap.semanticdemo.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fengmap.semanticdemo.service.RouteService;
import com.fengmap.semanticdemo.service.SemanticQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/demo/maps")
public class DemoMapController {

    private final SemanticQueryService queryService;
    private final RouteService routeService;

    public DemoMapController(SemanticQueryService queryService, RouteService routeService) {
        this.queryService = queryService;
        this.routeService = routeService;
    }

    @GetMapping
    public List<Map<String, Object>> maps() {
        return queryService.maps();
    }

    @GetMapping("/{mapId}/floors")
    public JsonNode floors(@PathVariable String mapId) {
        return queryService.floors(mapId);
    }

    @GetMapping("/{mapId}/entities")
    public List<JsonNode> floorEntities(
            @PathVariable String mapId,
            @RequestParam String floorName,
            @RequestParam(defaultValue = "true") boolean includePaths
    ) {
        return queryService.floorEntities(mapId, floorName, includePaths);
    }

    @GetMapping("/{mapId}/entities/{entityId}")
    public JsonNode entity(@PathVariable String mapId, @PathVariable String entityId) {
        return queryService.entity(mapId, entityId);
    }

    @GetMapping("/{mapId}/search")
    public Map<String, Object> search(
            @PathVariable String mapId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String referenceEntityId
    ) {
        var response = queryService.searchWithIntent(mapId, keyword,
                responseLimit(limit, responseNeedsMoreCandidates(keyword)));
        List<Map<String, Object>> results = response.results();
        boolean nearestApplied = false;
        String message = response.message();
        if (response.nearestRequested()) {
            if (referenceEntityId == null || referenceEntityId.isBlank()) {
                message = appendMessage(message,
                        "已识别目标“" + response.interpretedTarget() + "”；请先设置起点，再按 WALK 路网判断最近。");
            } else {
                results = rankByRoute(mapId, referenceEntityId, results, limit);
                nearestApplied = true;
                message = appendMessage(message, "已根据当前起点和 WALK 路网代价排序。");
            }
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("interpretedTarget", response.interpretedTarget());
        value.put("retrievalMode", response.retrievalMode());
        value.put("nearestRequested", response.nearestRequested());
        value.put("nearestApplied", nearestApplied);
        value.put("referenceEntityId", nearestApplied ? referenceEntityId : null);
        value.put("message", message);
        value.put("results", results.stream().limit(Math.max(1, Math.min(limit, 50))).toList());
        return value;
    }

    @GetMapping("/{mapId}/entities/{entityId}/nearby")
    public List<Map<String, Object>> nearby(
            @PathVariable String mapId,
            @PathVariable String entityId,
            @RequestParam(defaultValue = "12") int limit
    ) {
        return queryService.nearby(mapId, entityId, limit);
    }

    @PostMapping("/{mapId}/routes")
    public Map<String, Object> route(
            @PathVariable String mapId,
            @Valid @RequestBody RouteRequest request
    ) {
        return routeService.route(mapId, request.startEntityId(), request.targetEntityId(), request.mode());
    }

    public record RouteRequest(
            @NotBlank String startEntityId,
            @NotBlank String targetEntityId,
            @NotBlank String mode
    ) {
    }

    private List<Map<String, Object>> rankByRoute(
            String mapId,
            String referenceEntityId,
            List<Map<String, Object>> candidates,
            int requestedLimit
    ) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            try {
                Map<String, Object> route = routeService.route(
                        mapId, referenceEntityId, candidate.get("id").toString(), "WALK"
                );
                Map<String, Object> value = new LinkedHashMap<>(candidate);
                value.put("routeDistance", route.get("walkLength"));
                value.put("transferCount", route.get("transferCount"));
                value.put("routeCost", route.get("routeCost"));
                ranked.add(value);
            } catch (IllegalArgumentException ignored) {
                // 与当前起点不连通的候选不能参与“最近”排序。
            }
        }
        return ranked.stream()
                .sorted(Comparator.comparingDouble(
                                (Map<String, Object> value) -> ((Number) value.get("routeCost")).doubleValue())
                        .thenComparing(value -> value.get("id").toString()))
                .limit(Math.max(1, Math.min(requestedLimit, 50)))
                .toList();
    }

    private static int responseLimit(int requestedLimit, boolean nearestRequested) {
        return nearestRequested ? 50 : requestedLimit;
    }

    private static boolean responseNeedsMoreCandidates(String keyword) {
        return keyword != null && (keyword.contains("最近") || keyword.contains("离我"));
    }

    private static String appendMessage(String current, String addition) {
        return current == null || current.isBlank() ? addition : current + " " + addition;
    }
}
