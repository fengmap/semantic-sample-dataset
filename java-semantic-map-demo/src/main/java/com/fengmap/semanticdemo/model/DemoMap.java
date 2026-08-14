package com.fengmap.semanticdemo.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 从一份 v0.2 DISTRIBUTION 包构建的只读内存快照。
 */
public record DemoMap(
        String mapId,
        JsonNode manifest,
        List<JsonNode> entities,
        Map<String, JsonNode> entitiesById,
        List<JsonNode> relations,
        Map<String, DemoNavigationNode> nodesById,
        List<DemoNavigationEdge> edges,
        Map<String, List<DemoNavigationEdge>> outgoingEdges
) {
}

