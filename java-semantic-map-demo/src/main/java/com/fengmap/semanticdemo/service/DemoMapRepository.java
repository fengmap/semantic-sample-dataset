package com.fengmap.semanticdemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fengmap.semanticdemo.config.DemoProperties;
import com.fengmap.semanticdemo.model.DemoMap;
import com.fengmap.semanticdemo.model.DemoNavigationEdge;
import com.fengmap.semanticdemo.model.DemoNavigationNode;
import com.fengmap.semanticdemo.model.Point2D;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取标准语义地图 v0.2 对外交付包，并缓存为只读内存图。
 */
@Repository
public class DemoMapRepository {

    private final Path dataRoot;
    private final ObjectMapper objectMapper;
    private final Map<String, DemoMap> cache = new ConcurrentHashMap<>();

    public DemoMapRepository(DemoProperties properties, ObjectMapper objectMapper) {
        this.dataRoot = properties.getDataRoot().toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public List<String> availableMapIds() {
        if (!Files.isDirectory(dataRoot)) {
            return List.of();
        }
        try (var paths = Files.list(dataRoot)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("manifest.json")))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("无法扫描 Demo 数据目录: " + dataRoot, exception);
        }
    }

    public DemoMap get(String mapId) {
        validateMapId(mapId);
        try {
            return cache.computeIfAbsent(mapId, this::loadUnchecked);
        } catch (MapLoadException exception) {
            throw new IllegalStateException("无法加载语义地图 " + mapId + ": " + exception.getCause().getMessage(),
                    exception.getCause());
        }
    }

    private DemoMap loadUnchecked(String mapId) {
        try {
            return load(mapId);
        } catch (IOException exception) {
            throw new MapLoadException(exception);
        }
    }

    private DemoMap load(String mapId) throws IOException {
        Path directory = dataRoot.resolve(mapId).normalize();
        if (!directory.startsWith(dataRoot) || !Files.isDirectory(directory)) {
            throw new IOException("地图目录不存在: " + directory);
        }
        Path manifestFile = required(directory, "manifest.json");
        JsonNode manifest = objectMapper.readTree(manifestFile.toFile());
        if (!"0.2".equals(manifest.path("schemaVersion").asText())
                || !"DISTRIBUTION".equals(manifest.path("packageProfile").asText())) {
            throw new IOException("Demo 只接受 v0.2 DISTRIBUTION 文件包");
        }
        if (!mapId.equals(manifest.path("mapId").asText())) {
            throw new IOException("目录名称与 manifest.mapId 不一致");
        }

        JsonNode entityRoot = objectMapper.readTree(required(directory, "entities.json").toFile());
        List<JsonNode> entities = new ArrayList<>();
        entityRoot.path("entities").forEach(entities::add);
        entities.sort(Comparator.comparing(value -> value.path("id").asText()));
        Map<String, JsonNode> entitiesById = new LinkedHashMap<>();
        entities.forEach(entity -> entitiesById.put(entity.path("id").asText(), entity));

        List<JsonNode> relations = readJsonLines(required(directory, "relations.jsonl"));
        List<JsonNode> nodeValues = readJsonLines(required(directory, "navigation/nodes.jsonl"));
        List<JsonNode> edgeValues = readJsonLines(required(directory, "navigation/edges.jsonl"));

        Map<String, DemoNavigationNode> nodes = new LinkedHashMap<>();
        for (JsonNode value : nodeValues) {
            Point2D point = GeometrySupport.pointGeometry(value.path("geometry"))
                    .orElseThrow(() -> new IOException("导航节点缺少 Point 几何: " + value.path("id").asText()));
            DemoNavigationNode node = new DemoNavigationNode(
                    value.path("id").asText(), value.path("floorName").asText(),
                    value.path("mode").asText(), point, value
            );
            nodes.put(node.id(), node);
        }

        List<DemoNavigationEdge> edges = new ArrayList<>();
        Map<String, List<DemoNavigationEdge>> outgoing = new LinkedHashMap<>();
        for (JsonNode value : edgeValues) {
            DemoNavigationEdge edge = new DemoNavigationEdge(
                    value.path("id").asText(), value.path("fromNodeId").asText(),
                    value.path("toNodeId").asText(), value.path("mode").asText(),
                    value.path("type").asText(), value.path("length").asDouble(0D), value
            );
            if (!nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
                throw new IOException("导航边引用不存在的节点: " + edge.id());
            }
            edges.add(edge);
            outgoing.computeIfAbsent(edge.fromNodeId(), ignored -> new ArrayList<>()).add(edge);
        }
        outgoing.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparing(DemoNavigationEdge::id)).toList());

        return new DemoMap(
                mapId,
                manifest,
                List.copyOf(entities),
                Collections.unmodifiableMap(entitiesById),
                List.copyOf(relations),
                Collections.unmodifiableMap(nodes),
                List.copyOf(edges),
                Collections.unmodifiableMap(outgoing)
        );
    }

    private List<JsonNode> readJsonLines(Path file) throws IOException {
        List<JsonNode> values = new ArrayList<>();
        try (var lines = Files.lines(file)) {
            var iterator = lines.filter(line -> !line.isBlank()).iterator();
            while (iterator.hasNext()) {
                values.add(objectMapper.readTree(iterator.next()));
            }
        }
        return List.copyOf(values);
    }

    private static Path required(Path directory, String relative) throws IOException {
        Path file = directory.resolve(relative).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new IOException("文件包缺少 " + relative);
        }
        return file;
    }

    private static void validateMapId(String mapId) {
        if (mapId == null || !mapId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("非法地图编号");
        }
    }

    private static final class MapLoadException extends RuntimeException {
        private MapLoadException(IOException cause) {
            super(cause);
        }
    }
}

