package com.fengmap.semanticdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Demo 数据目录和路线计算参数。
 */
@ConfigurationProperties(prefix = "semantic-demo")
public class DemoProperties {

    private Path dataRoot = Path.of("./data");
    private double transferCost = 20D;
    private Embedding embedding = new Embedding();

    public Path getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(Path dataRoot) {
        this.dataRoot = dataRoot;
    }

    public double getTransferCost() {
        return transferCost;
    }

    public void setTransferCost(double transferCost) {
        this.transferCost = transferCost;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    /**
     * Ollama 向量检索参数。baseUrl 为空时不启用外部模型，Demo 保持规则检索模式。
     */
    public static class Embedding {

        private String baseUrl = "";
        private String model = "qwen3-embedding:0.6b";
        private int dimensions = 1024;
        private int batchSize = 64;
        private double minScore = 0.45D;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(60);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }
}
