package com.fengmap.semanticdemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fengmap.semanticdemo.config.DemoProperties;
import com.fengmap.semanticdemo.model.DemoMap;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用仓库内 911907 对外交付包验证 Demo 的核心用户链路。
 */
class DemoMapIntegrationTest {

    private DemoMapRepository repository;
    private SemanticQueryService queryService;
    private RouteService routeService;
    private HttpServer ollamaServer;

    @BeforeEach
    void setUp() {
        DemoProperties properties = new DemoProperties();
        properties.setDataRoot(Path.of("data"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new DemoMapRepository(properties, objectMapper);
        queryService = new SemanticQueryService(repository,
                new OllamaEmbeddingService(repository, objectMapper, properties));
        routeService = new RouteService(repository, properties);
    }

    @AfterEach
    void tearDown() {
        if (ollamaServer != null) {
            ollamaServer.stop(0);
        }
    }

    @Test
    void loadsV02DistributionAndFindsNamedEntity() {
        DemoMap map = repository.get("911907");

        assertThat(map.entities()).hasSize(2648);
        assertThat(map.relations()).hasSize(3047);
        assertThat(map.nodesById()).hasSize(1437);
        assertThat(map.edges()).hasSize(3426);

        assertThat(queryService.maps().get(0).get("embeddingConfigured")).isEqualTo(false);

        List<Map<String, Object>> results = queryService.search("911907", "会员中心", 10);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0)).satisfies(result -> {
            assertThat(result.get("name")).isEqualTo("会员中心");
            assertThat(result.get("floorName")).isEqualTo("F5");
        });
    }

    @Test
    void understandsNaturalLanguageSearchIntent() {
        List<Map<String, Object>> results = queryService.search("911907", "我想找会员中心在哪里", 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0)).satisfies(result -> {
            assertThat(result.get("name")).isEqualTo("会员中心");
            assertThat(result.get("floorName")).isEqualTo("F5");
        });
        assertThat(queryService.search("911907", "请问厕所在哪儿", 10))
                .isNotEmpty()
                .allSatisfy(result -> assertThat(result.get("name")).isIn("洗手间", "卫生间"));
        var nearestIntent = queryService.searchWithIntent("911907", "给我找个最近的咨询台", 10);
        assertThat(nearestIntent.interpretedTarget()).isEqualTo("咨询台");
        assertThat(nearestIntent.nearestRequested()).isTrue();
        assertThat(nearestIntent.retrievalMode()).isEqualTo("RULE_BASED");
        assertThat(nearestIntent.results()).isNotEmpty()
                .allSatisfy(result -> assertThat(result.get("name")).isEqualTo("咨询台"));
    }

    @Test
    void findsNearbyRestroomAndBuildsWalkRoute() {
        String startId = "space:911907:F6:91190706012";
        String restroomId = "facility:911907:F6:91190706022";

        assertThat(queryService.nearby("911907", startId, 20))
                .anySatisfy(value -> assertThat(value.get("id")).isEqualTo(restroomId));

        Map<String, Object> route = routeService.route("911907", startId, restroomId, "WALK");
        assertThat(route.get("inferredAccess")).isEqualTo(true);
        assertThat(route.get("transferCount")).isEqualTo(0L);
        assertThat((double) route.get("walkLength")).isPositive();
        assertThat((List<?>) route.get("edges")).isNotEmpty();
    }

    @Test
    void buildsDirectedCrossFloorRouteUsingTransferEdges() {
        Map<String, Object> route = routeService.route(
                "911907",
                "space:911907:F6:91190706012",
                "facility:911907:F2:911907020213",
                "WALK"
        );

        assertThat(route.get("transferCount")).isEqualTo(4L);
        assertThat(route.get("floors")).isEqualTo(List.of("F6", "F5", "F4", "F3", "F2"));
    }

    @Test
    void usesOllamaForQueriesWithoutLiteralEntityName() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ollamaServer = fakeOllama(objectMapper);
        DemoProperties properties = propertiesWithEmbedding(
                "127.0.0.1:" + ollamaServer.getAddress().getPort(), 3
        );
        DemoMapRepository embeddingRepository = new DemoMapRepository(properties, objectMapper);
        SemanticQueryService embeddingQueryService = new SemanticQueryService(
                embeddingRepository,
                new OllamaEmbeddingService(embeddingRepository, objectMapper, properties)
        );

        var response = embeddingQueryService.searchWithIntent("911907", "哪里可以办会员", 10);

        assertThat(response.retrievalMode()).isEqualTo("OLLAMA_HYBRID");
        assertThat(response.results()).isNotEmpty();
        assertThat(response.results().get(0)).satisfies(result -> {
            assertThat(result.get("name")).isEqualTo("会员中心");
            assertThat(result.get("matchSource")).isEqualTo("OLLAMA");
        });
    }

    @Test
    void fallsBackToRuleSearchWhenOllamaIsUnavailable() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DemoProperties properties = propertiesWithEmbedding("http://127.0.0.1:1", 3);
        DemoMapRepository fallbackRepository = new DemoMapRepository(properties, objectMapper);
        SemanticQueryService fallbackQueryService = new SemanticQueryService(
                fallbackRepository,
                new OllamaEmbeddingService(fallbackRepository, objectMapper, properties)
        );

        var response = fallbackQueryService.searchWithIntent("911907", "咨询台", 10);

        assertThat(response.retrievalMode()).isEqualTo("RULE_BASED_FALLBACK");
        assertThat(response.results()).isNotEmpty()
                .allSatisfy(result -> assertThat(result.get("name")).isEqualTo("咨询台"));
    }

    private HttpServer fakeOllama(ObjectMapper objectMapper) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            JsonNodeRequest request = new JsonNodeRequest(
                    objectMapper.readTree(exchange.getRequestBody()).path("input")
            );
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode embeddings = response.putArray("embeddings");
            request.inputs().forEach(input -> {
                boolean membership = input.asText().contains("会员");
                ArrayNode vector = embeddings.addArray();
                vector.add(membership ? 1D : 0D);
                vector.add(membership ? 0D : 1D);
                vector.add(0D);
            });
            byte[] body = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static DemoProperties propertiesWithEmbedding(String baseUrl, int dimensions) {
        DemoProperties properties = new DemoProperties();
        properties.setDataRoot(Path.of("data"));
        properties.getEmbedding().setBaseUrl(baseUrl);
        properties.getEmbedding().setDimensions(dimensions);
        properties.getEmbedding().setMinScore(0.9D);
        return properties;
    }

    private record JsonNodeRequest(com.fasterxml.jackson.databind.JsonNode inputs) {
    }
}
