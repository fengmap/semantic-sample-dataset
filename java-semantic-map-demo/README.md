# 蜂鸟视图语义地图查询与导航 Demo

本 DEMO 使用 Java Spring Boot 开发的演示程序，用于展示标准语义地图 v0.2 在商场空间查询和路径规划中的使用方式。仓库内包含北京蜂鸟视图（`911907`）的 `Sample-test` 示例包。

## 演示能力

- 按楼层绘制空间、设施、跨层设施和 PATH；
- 按品牌、设施名称或“想找会员中心”一类自然语言需求检索实体；
- 可选接入 Ollama Embedding，理解“哪里可以办会员”等不直接包含实体名称的表达；
- 查看同楼层附近的店铺和设施；
- 将实体设为起点或终点；
- 使用 `navigation/nodes.jsonl` 和 `navigation/edges.jsonl` 计算有向 WALK 最短路径；
- 展示同层路线、跨层 TRANSFER、距离和分层文字步骤；
- 明确标记实体到最近 WALK 节点的推导接入关系。

推荐体验：搜索“最近的咨询台在哪”或“洗手间”；连接 Embedding 后可以尝试“哪里可以办会员”等自然语言表达。

## 环境

- Java 17
- Maven 3.8+

未配置 Ollama 时，程序完全离线运行。浏览器地图由原生 Canvas 绘制。

## 启动

```bash
export JAVA_HOME=/path/to/jdk-17
mvn spring-boot:run
```

浏览器访问：<http://localhost:18080>

也可以打包后运行：

```bash
mvn clean package
java -jar target/semantic-map-demo.jar
```

## 可选的 Ollama 语义检索

Demo 提供两种兼容运行方式：

- 未配置 `OLLAMA_BASE_URL`：使用现有的名称匹配、意图清洗和同义词规则，响应中的 `retrievalMode` 为 `RULE_BASED`；
- 配置 `OLLAMA_BASE_URL`：在规则结果基础上使用 Ollama 向量召回，响应中的 `retrievalMode` 为 `OLLAMA_HYBRID`。

接入当前 Embedding 服务的启动示例：

```bash
OLLAMA_BASE_URL=http://172.118.1.172:11434 \
OLLAMA_EMBEDDING_MODEL=qwen3-embedding:0.6b \
OLLAMA_EMBEDDING_DIMENSIONS=1024 \
java -jar target/semantic-map-demo.jar
```

地址也可以省略 `http://`。Ollama 地址只由 Spring Boot 后端访问，不会返回给浏览器。程序调用 `/api/embed` 批量生成实体与查询文本的向量，并在内存中以余弦相似度检索；不需要额外部署向量数据库。

首次查询某张地图时需要生成该地图的实体向量，因此耗时会高于后续查询。索引只存在于进程内存，程序重启后按需重建。如果 Ollama 超时、无法连接、模型不存在或返回维度不符，当前请求自动回退到规则检索，`retrievalMode` 为 `RULE_BASED_FALLBACK`，页面会显示回退提示。

可选配置如下：

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | 空 | Ollama 服务地址；为空即不启用向量检索 |
| `OLLAMA_EMBEDDING_MODEL` | `qwen3-embedding:0.6b` | Embedding 模型名称 |
| `OLLAMA_EMBEDDING_DIMENSIONS` | `1024` | 返回向量的预期维度，用于防止模型配置错误 |
| `OLLAMA_EMBEDDING_BATCH_SIZE` | `64` | 首次构建索引时每批处理的实体文本数，最大按 256 执行 |
| `OLLAMA_EMBEDDING_MIN_SCORE` | `0.45` | 语义候选的最低余弦相似度 |
| `OLLAMA_CONNECT_TIMEOUT` | `3s` | 建立连接超时时间 |
| `OLLAMA_REQUEST_TIMEOUT` | `60s` | 单批请求超时时间 |

这里的“最近”不使用向量相似度判断：Embedding 只负责召回目标实体；设置起点后，最终顺序仍按地图中的 WALK 路网代价计算。

## 数据目录

默认读取仓库下的 `./data`：

```text
data/911907/
├── manifest.json
├── entities.json
├── relations.jsonl
└── navigation/
    ├── nodes.jsonl
    └── edges.jsonl
```

可以通过环境变量切换到其他 v0.2 对外交付包目录：

```bash
SEMANTIC_DEMO_DATA_ROOT=/path/to/semantic/output java -jar target/semantic-map-demo.jar
```

程序只接受 `schemaVersion=0.2` 且 `packageProfile=DISTRIBUTION` 的地图目录。

## 主要接口

```http
GET  /api/demo/maps
GET  /api/demo/maps/{mapId}/floors
GET  /api/demo/maps/{mapId}/entities?floorName=F6
GET  /api/demo/maps/{mapId}/search?keyword=最近的咨询台在哪
GET  /api/demo/maps/{mapId}/entities/{entityId}/nearby
POST /api/demo/maps/{mapId}/routes
```

搜索接口会额外返回：

- `interpretedTarget`：从自然语言中清洗出的检索目标；
- `retrievalMode`：本次实际使用的检索模式；
- `nearestRequested` / `nearestApplied`：是否识别并应用了“最近”意图；
- `message`：语义召回、路网排序或自动回退的说明；
- `results[].matchSource`：启用 Ollama 时标识候选来自 `RULE`、`OLLAMA` 或 `HYBRID`；
- `results[].semanticScore`：Ollama 候选的余弦相似度，仅用于解释召回结果，不代表路线距离。

路径请求示例：

```json
{
  "startEntityId": "space:911907:F6:91190706012",
  "targetEntityId": "facility:911907:F6:91190706022",
  "mode": "WALK"
}
```
