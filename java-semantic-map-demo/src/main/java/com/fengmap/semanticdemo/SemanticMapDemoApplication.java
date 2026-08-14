package com.fengmap.semanticdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 语义地图演示程序启动入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SemanticMapDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticMapDemoApplication.class, args);
    }
}

