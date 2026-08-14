package com.fengmap.semanticdemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fengmap.semanticdemo.config.DemoProperties;
import com.fengmap.semanticdemo.model.DemoMap;
import com.fengmap.semanticdemo.model.DemoNavigationEdge;
import com.fengmap.semanticdemo.model.DemoNavigationNode;
import com.fengmap.semanticdemo.model.Point2D;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 使用 v0.2 有向导航边完成 WALK 最短路径演示。
 *
 * <p>实体到路网的接入点由最近节点推导，结果会在响应中明确标记，
 * 不把它当成源地图已经提供的门或入口。</p>
 */
@Service
public class RouteService {

    private static final String WALK = "WALK";

    private final DemoMapRepository repository;
    private final double transferCost;

    public RouteService(DemoMapRepository repository, DemoProperties properties) {
        this.repository = repository;
        this.transferCost = properties.getTransferCost();
    }

    public Map<String, Object> route(String mapId, String startEntityId, String targetEntityId, String mode) {
        if (!WALK.equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("当前 Demo 只演示 WALK 模式");
        }
        DemoMap map = repository.get(mapId);
        JsonNode startEntity = requiredEntity(map, startEntityId);
        JsonNode targetEntity = requiredEntity(map, targetEntityId);
        Point2D startPoint = GeometrySupport.representativePoint(startEntity)
                .orElseThrow(() -> new IllegalArgumentException("起点实体没有可用几何"));
        Point2D targetPoint = GeometrySupport.representativePoint(targetEntity)
                .orElseThrow(() -> new IllegalArgumentException("终点实体没有可用几何"));
        DemoNavigationNode startNode = nearestNode(map, startEntity.path("floorName").asText(), startPoint);
        DemoNavigationNode targetNode = nearestNode(map, targetEntity.path("floorName").asText(), targetPoint);

        List<DemoNavigationEdge> routeEdges = shortestPath(map, startNode.id(), targetNode.id());
        double walkLength = routeEdges.stream()
                .filter(edge -> "PATH".equals(edge.type()))
                .mapToDouble(DemoNavigationEdge::length)
                .sum();
        long transferCount = routeEdges.stream().filter(edge -> "TRANSFER".equals(edge.type())).count();
        double routeCost = walkLength + transferCount * transferCost;
        Set<String> floors = new LinkedHashSet<>();
        floors.add(startNode.floorName());
        routeEdges.forEach(edge -> floors.add(map.nodesById().get(edge.toNodeId()).floorName()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mapId", mapId);
        result.put("mode", WALK);
        result.put("startEntity", entitySummary(startEntity));
        result.put("targetEntity", entitySummary(targetEntity));
        result.put("startNodeId", startNode.id());
        result.put("targetNodeId", targetNode.id());
        result.put("walkLength", rounded(walkLength));
        result.put("routeCost", rounded(routeCost));
        result.put("transferCount", transferCount);
        result.put("floors", List.copyOf(floors));
        result.put("inferredAccess", true);
        result.put("inferenceNote", "实体通过代表点吸附至同楼层最近 WALK 节点；该接入点不是源 SHP 明确提供的门。");
        result.put("accessLinks", List.of(
                accessLink(startEntity.path("floorName").asText(), startPoint, startNode.point()),
                accessLink(targetEntity.path("floorName").asText(), targetNode.point(), targetPoint)
        ));
        result.put("edges", routeEdges.stream().map(DemoNavigationEdge::source).toList());
        result.put("steps", routeSteps(map, routeEdges, startEntity, targetEntity,
                startPoint.distance(startNode.point()), targetPoint.distance(targetNode.point())));
        return result;
    }

    private List<DemoNavigationEdge> shortestPath(DemoMap map, String startId, String targetId) {
        if (startId.equals(targetId)) {
            return List.of();
        }
        Map<String, Double> distances = new HashMap<>();
        Map<String, DemoNavigationEdge> previous = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(
                Comparator.comparingDouble(NodeDistance::distance).thenComparing(NodeDistance::nodeId)
        );
        distances.put(startId, 0D);
        queue.add(new NodeDistance(startId, 0D));

        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll();
            if (current.distance() > distances.getOrDefault(current.nodeId(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (current.nodeId().equals(targetId)) {
                break;
            }
            for (DemoNavigationEdge edge : map.outgoingEdges().getOrDefault(current.nodeId(), List.of())) {
                if (!WALK.equals(edge.mode())) {
                    continue;
                }
                double weight = "TRANSFER".equals(edge.type()) ? transferCost : edge.length();
                if (!Double.isFinite(weight) || weight <= 0) {
                    continue;
                }
                double candidate = current.distance() + weight;
                double known = distances.getOrDefault(edge.toNodeId(), Double.POSITIVE_INFINITY);
                if (candidate + 1E-9 < known) {
                    distances.put(edge.toNodeId(), candidate);
                    previous.put(edge.toNodeId(), edge);
                    queue.add(new NodeDistance(edge.toNodeId(), candidate));
                }
            }
        }
        if (!previous.containsKey(targetId)) {
            throw new IllegalArgumentException("当前 WALK 路网中未找到起点到终点的连通路线");
        }

        List<DemoNavigationEdge> result = new ArrayList<>();
        String cursor = targetId;
        while (!cursor.equals(startId)) {
            DemoNavigationEdge edge = previous.get(cursor);
            if (edge == null) {
                throw new IllegalStateException("路线回溯失败");
            }
            result.add(edge);
            cursor = edge.fromNodeId();
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private static DemoNavigationNode nearestNode(DemoMap map, String floorName, Point2D point) {
        return map.nodesById().values().stream()
                .filter(node -> WALK.equals(node.mode()))
                .filter(node -> floorName.equalsIgnoreCase(node.floorName()))
                .min(Comparator.comparingDouble((DemoNavigationNode node) -> node.point().distance(point))
                        .thenComparing(DemoNavigationNode::id))
                .orElseThrow(() -> new IllegalArgumentException("楼层 " + floorName + " 没有 WALK 导航节点"));
    }

    private static List<Map<String, Object>> routeSteps(
            DemoMap map,
            List<DemoNavigationEdge> edges,
            JsonNode startEntity,
            JsonNode targetEntity,
            double startAccessDistance,
            double targetAccessDistance
    ) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("ACCESS", startEntity.path("floorName").asText(),
                "从“" + displayName(startEntity) + "”接入附近步行路径（推导接入点）",
                rounded(startAccessDistance)));

        double floorDistance = 0D;
        String currentFloor = startEntity.path("floorName").asText();
        for (DemoNavigationEdge edge : edges) {
            if ("PATH".equals(edge.type())) {
                floorDistance += edge.length();
                continue;
            }
            if (floorDistance > 0) {
                steps.add(step("WALK", currentFloor, "沿 " + currentFloor + " 步行",
                        rounded(floorDistance)));
                floorDistance = 0D;
            }
            DemoNavigationNode from = map.nodesById().get(edge.fromNodeId());
            DemoNavigationNode to = map.nodesById().get(edge.toNodeId());
            String connectorId = edge.source().path("fromConnectorId").asText();
            JsonNode connector = map.entitiesById().get(connectorId);
            String connectorName = connector == null
                    ? "跨层设施"
                    : connector.path("subtype").asText("跨层设施");
            steps.add(step("TRANSFER", from.floorName(),
                    "通过 " + connectorName + " 从 " + from.floorName() + " 前往 " + to.floorName(), null));
            currentFloor = to.floorName();
        }
        if (floorDistance > 0) {
            steps.add(step("WALK", currentFloor, "沿 " + currentFloor + " 步行",
                    rounded(floorDistance)));
        }
        steps.add(step("ARRIVE", targetEntity.path("floorName").asText(),
                "离开步行路径，到达“" + displayName(targetEntity) + "”（推导接入点）",
                rounded(targetAccessDistance)));
        return List.copyOf(steps);
    }

    private static Map<String, Object> entitySummary(JsonNode entity) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", entity.path("id").asText());
        value.put("name", displayName(entity));
        value.put("kind", entity.path("kind").asText());
        value.put("floorName", entity.path("floorName").asText());
        value.put("point", GeometrySupport.representativePoint(entity).orElse(null));
        return value;
    }

    private static Map<String, Object> accessLink(String floorName, Point2D from, Point2D to) {
        return Map.of(
                "floorName", floorName,
                "from", List.of(from.x(), from.y()),
                "to", List.of(to.x(), to.y())
        );
    }

    private static Map<String, Object> step(String type, String floorName, String instruction, Double distance) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("floorName", floorName);
        value.put("instruction", instruction);
        if (distance != null) {
            value.put("distance", distance);
        }
        return value;
    }

    private static JsonNode requiredEntity(DemoMap map, String entityId) {
        JsonNode entity = map.entitiesById().get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("实体不存在: " + entityId);
        }
        return entity;
    }

    private static String displayName(JsonNode entity) {
        String name = entity.path("name").asText();
        return name.isBlank() ? entity.path("subtype").asText(entity.path("id").asText()) : name;
    }

    private static double rounded(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private record NodeDistance(String nodeId, double distance) {
    }
}
