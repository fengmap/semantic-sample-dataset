package com.fengmap.semanticdemo.model;

import com.fasterxml.jackson.databind.JsonNode;

public record DemoNavigationNode(
        String id,
        String floorName,
        String mode,
        Point2D point,
        JsonNode source
) {
}

