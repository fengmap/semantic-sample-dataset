package com.fengmap.semanticdemo.model;

/**
 * EPSG:3857 平面坐标。
 */
public record Point2D(double x, double y) {

    public double distance(Point2D other) {
        return Math.hypot(x - other.x, y - other.y);
    }
}

