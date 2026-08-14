package com.fengmap.semanticdemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fengmap.semanticdemo.model.Point2D;

import java.util.Optional;

/**
 * Demo 只需要实体代表点和距离，不在浏览器服务中重复实现完整 GIS 运算。
 */
final class GeometrySupport {

    private GeometrySupport() {
    }

    static Optional<Point2D> representativePoint(JsonNode entity) {
        Optional<Point2D> label = pointGeometry(entity.path("labelPoint"));
        if (label.isPresent()) {
            return label;
        }
        Optional<Point2D> point = pointGeometry(entity.path("geometry"));
        if (point.isPresent()) {
            return point;
        }
        Bounds bounds = new Bounds();
        collectCoordinates(entity.path("geometry").path("coordinates"), bounds);
        return bounds.empty()
                ? Optional.empty()
                : Optional.of(new Point2D((bounds.minX + bounds.maxX) / 2D, (bounds.minY + bounds.maxY) / 2D));
    }

    static Optional<Point2D> pointGeometry(JsonNode geometry) {
        if (!"Point".equals(geometry.path("type").asText())) {
            return Optional.empty();
        }
        JsonNode coordinates = geometry.path("coordinates");
        if (coordinates.size() < 2 || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) {
            return Optional.empty();
        }
        return Optional.of(new Point2D(coordinates.get(0).asDouble(), coordinates.get(1).asDouble()));
    }

    private static void collectCoordinates(JsonNode value, Bounds bounds) {
        if (!value.isArray()) {
            return;
        }
        if (value.size() >= 2 && value.get(0).isNumber() && value.get(1).isNumber()) {
            bounds.add(value.get(0).asDouble(), value.get(1).asDouble());
            return;
        }
        value.forEach(child -> collectCoordinates(child, bounds));
    }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        private void add(double x, double y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        private boolean empty() {
            return !Double.isFinite(minX);
        }
    }
}

