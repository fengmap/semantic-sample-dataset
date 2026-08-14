package com.fengmap.semanticdemo.model;

import com.fasterxml.jackson.databind.JsonNode;

public record DemoNavigationEdge(
        String id,
        String fromNodeId,
        String toNodeId,
        String mode,
        String type,
        double length,
        JsonNode source
) {
}

