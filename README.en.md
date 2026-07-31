# Utils Support Parent Starter

[![Java Version](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)

A powerful, highly extensible Java toolkit collection with an SPI-based plugin architecture, providing rich out-of-the-box functionality.

## Table of Contents

- [Introduction](#introduction)
- [Module Overview](#module-overview)
- [Quick Start](#quick-start)
- [Advanced Examples](#advanced-examples)
- [Installation](#installation)
- [Documentation](#documentation)
- [Contributing](#contributing)

---

## Introduction

An enterprise-grade Java toolkit collection with a modular, SPI-driven architecture. All components can be extended and replaced via SPI.

### Key Features

- **Plugin Architecture**: SPI-based hot-plug extensions
- **Modular Design**: Import on demand, minimize conflicts
- **Unified Interfaces**: ProtocolServer, FileStorage, ChatClient, IdentificationEngine
- **High Performance**: Built on Netty, Vert.x, Disruptor
- **Dynamic Plugins**: Runtime load/unload with Spring integration

---

## Module Overview

```
utils-support-parent-starter/
│
├── core-parent/                              # ★ Core (required)
│   ├── common-starter                        # Type conversion, JSON, date, crypto, HTTP
│   ├── spring-starter                        # Spring integration
│   ├── extension-starter                     # Extensions
│   ├── spider-starter                        # Web crawler
│   └── example-starter                       # Usage examples
│
├── cloud-parent/                             # Cloud services
│   ├── alibaba-starter / tencent-starter     # OSS, SMS, LLM
│   ├── huawei-starter / baidu-starter        # OBS, BOS
│   ├── amazon-starter / google-starter       # AWS S3, Google Cloud
│   ├── openai-starter / claude-parent        # AI LLM APIs (OpenAI-compatible services use openai-starter)
│   ├── zhipu-starter / doubao-starter        # ChatGLM, ByteDance
│   ├── ollama-starter / xunfei-starter       # Local inference, iFlytek
│   ├── dingding-starter / feishu-starter     # DingTalk, Feishu bots
│   ├── hikvision-starter / uniview-starter   # Security cameras
│   └── qiniu-starter / unionpay-starter      # Cloud storage, payments
│
├── datasource-parent/                        # 30+ datasources
│   ├── mysql-starter / postgresql-starter    # Relational: MySQL, PG, Oracle, SQL Server
│   ├── redis-starter / mongodb-starter       # NoSQL: Redis, MongoDB, ES
│   ├── milvus-starter / jvector-starter      # Vector: Milvus, JVector
│   ├── clickhouse-starter / duckdb-starter   # Columnar: ClickHouse, DuckDB
│   ├── hbase-starter / neo4j-starter         # HBase, Neo4j graph
│   ├── hibernate-starter / mybatis-plus      # ORM
│   ├── calcite-starter / shardingv5          # SQL parsing, sharding
│   └── debezium-starter / r2dbc-starter      # CDC, reactive JDBC
│
├── protocol-parent/                          # 50+ protocols
│   ├── netty-starter / vertx-starter         # TCP/UDP
│   ├── armeria-starter / rsocket-starter     # HTTP/2, reactive streams
│   ├── rpc-dubbo-starter / rpc-sofa-starter  # RPC frameworks
│   ├── ssh-starter / ftp-starter             # Remote access
│   ├── docker-starter / git-starter          # Container & tools
│   ├── socketd-starter / socketio-starter    # Socket frameworks
│   ├── datalake-starter                      # Data lake
│   └── httpclient-starter / retrofit-starter # HTTP clients
│
├── filesystem-parent/                        # File system
│   ├── excel-starter / pdf-starter           # Documents: Excel, PDF, Word, OFD
│   ├── image-starter / ffmpeg-starter        # Media: images, SVG, FFmpeg
│   ├── email-starter                         # SMTP/IMAP
│   ├── aspose-starter / libreoffice-starter  # Document conversion
│   ├── markdown-starter / groovy-starter     # Markup & scripting
│   └── zxing-starter / geoip2-starter        # QR codes, geo IP
│
├── middleware-parent/                         # Middleware
│   ├── kafka-starter / rabbitmq-starter      # Message queues
│   ├── nacos-starter / zookeeper-starter     # Registry/config
│   ├── etcd-starter / consul-starter         # Distributed KV
│   └── skywalking-starter                    # Tracing
│
├── deeplearning-parent/                      # Deep learning
│   ├── deeplearning-onnx-starter             # ONNX Runtime
│   ├── deeplearning-pytorch-starter          # PyTorch
│   ├── deeplearning-tensorflow-starter       # TensorFlow
│   ├── deeplearning-opencv-starter           # Computer vision
│   ├── deeplearning-arcface-starter          # Face recognition
│   ├── deeplearning-tesseract-starter        # OCR
│   ├── deeplearning-speech-starter           # Speech recognition
│   └── deeplearning-langchain4j-starter      # LangChain4j
│
├── derive-parent/                            # Derived extensions
│   ├── cli-starter / javafx-starter          # CLI, JavaFX desktop
│   ├── oshi-starter / hanlp-starter          # System info, Chinese NLP
│   ├── pinyin-starter / jdk15on-starter      # Pinyin, Bouncy Castle
│   └── tiktoken-starter / perf-rust-starter  # Token counting, perf
│
├── task-parent/                              # Task scheduling
│   ├── quartz-starter                        # Enterprise scheduling
│   ├── resilience4j-starter                  # Circuit breaker
│   ├── chronicle-starter                     # Persistent queue
│   └── guava-retrying-starter                # Retry mechanism
│
├── test-parent/                              # Browser automation
│   ├── playwright-starter / selenium-starter
│   └── htmlunit-starter
│
├── network-parent/                           # Networking
├── data-parent/                              # Data processing
```

---

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.6+

### Add Dependency

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>${latest.version}</version>
</dependency>
```

### Basic Usage

```java
// Type conversion
Integer num = Converter.convertIfNecessary("123", Integer.class);

// Date handling
DateTime now = DateTime.now();
String formatted = now.toString("yyyy-MM-dd HH:mm:ss");

// JSON
String json = Json.toJson(object);
MyClass obj = Json.fromJson(json, MyClass.class);

// HTTP request
HttpResponse response = HttpClient.get()
    .url("https://api.example.com/data")
    .newInvoker()
    .execute();

// SPI extension
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);
MyService service = provider.getExtension("default");
```

---

## Advanced Examples

### 1. Unified Protocol Server — ProtocolServer

Multi-protocol server (HTTP/WebSocket/TCP), switchable between Netty, Vert.x, and Armeria.

```java
ProtocolServer server = ProtocolServer.create("http://0.0.0.0:8080");
server.get("/api/users", (request, response) -> {
    response.setContentType("application/json");
    response.setBodyString("{\"users\": []}");
});
server.start();
```

> Docs: [common-starter](utils-support-core-parent/utils-support-common-starter/README.md)

### 2. Unified OSS Storage — FileStorage

Cross-cloud object storage with a single API. Switch between Alibaba, Tencent, Huawei, AWS, etc.

```java
FileStorage storage = FileStorage.createStorage("aliyun",
    FileStorageOption.builder()
        .accessKeyId("your-key")
        .accessKeySecret("your-secret")
        .endpoint("oss-cn-hangzhou.aliyuncs.com")
        .bucketName("your-bucket")
        .build());

storage.putObject("path/to/file.jpg", new File("local.jpg"));
```

> Docs: [cloud-parent](utils-support-cloud-parent/README.md)

### 3. AI Chat — ChatClient

Unified LLM interface supporting OpenAI, Qwen, DeepSeek, ChatGLM, and more.

```java
ChatClient client = ChatClient.create("openai", "your-api-key");
String response = client.chatSync("Hello, introduce yourself");

client.chat("Write a poem", chunk -> System.out.print(chunk.getContent()));
```

> Docs: [cloud](utils-support-cloud-parent/README.md)

### 4. Deep Learning — IdentificationEngine

Unified inference engine for ONNX, PyTorch, TensorFlow, PaddlePaddle. Covers detection, OCR, face recognition.

```java
IdentificationEngine engine = IdentificationEngine.builder()
    .engine("onnx")
    .configuration(DetectionConfiguration.builder()
        .option("modelPath", "/path/to/yolov8.onnx")
        .option("optDevice", "GPU")
        .build())
    .build();

List<PredictResult> results = engine.predict(new File("image.jpg"));
```

> Docs: [deeplearning-parent](utils-support-deeplearning-parent/README.md)

### 5. Task Orchestration — Disruptor

Lock-free, high-performance task orchestration using Disruptor.

```java
DisruptorProvider<TaskEntity> provider = DisruptorProvider
    .builder(TaskEntity.class)
    .build();

Arrange<TaskEntity> arrange = provider.newArrange();
ArrangeGroup<TaskEntity> group = arrange.group();
group.handleEventsWith(new TaskHandler("task1", 1000));
group.after("task1").handleEventsWith(
    new TaskHandler("task2", 500),
    new TaskHandler("task3", 800)
);
group.after("task2", "task3").handleEventsWith(new TaskHandler("task4", 200));

provider.start();
```

> Docs: [common-starter](utils-support-core-parent/utils-support-common-starter/README.md)

### 6. File Conversion — ConvertFileSystem

Auto-detect document format and convert between formats.

```java
ConvertFileSystem converter = ConvertFileSystem.autoDetect(
    new File("document.pdf"), "docx");
converter.convertTo(new File("document.docx"));
```

> Docs: [filesystem-parent](utils-support-filesystem-parent/README.md)

### 7. SPI Extension Mechanism

```java
@Spi
public interface MyService {
    String process(String input);
}

@Spi("default")
public class DefaultMyService implements MyService {
    public String process(String input) {
        return "processed: " + input;
    }
}

ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);
MyService service = provider.getExtension("default");
```

### 8. Event Dispatch — DispatcherFlow

Multi-rule event dispatch supporting local, Chronicle Queue persistence, and service discovery.

```java
// Local dispatch
DispatcherFlow flow = DispatcherFlow.createLocal();
flow.register(new UserEventHandler());
flow.start();
flow.publish(new UserCreatedEvent("user123", "张三"));
flow.close();

// Chronicle Queue persistent dispatch
DispatcherFlow chronicleFlow = DispatcherFlow.createChronicle("/tmp/queue");
chronicleFlow.register(new OrderEventHandler());
chronicleFlow.start();
chronicleFlow.publish(new OrderCreatedEvent("order123", 100.0));
chronicleFlow.close();
```

---

## Installation

### From Maven Central

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>${latest.version}</version>
</dependency>
```

### Build from Source

```bash
git clone https://gitee.com/achtk/utils-support-parent-starter.git
cd utils-support-parent-starter
mvn clean install -DskipTests
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [Cloud Integration](utils-support-cloud-parent/README.md) | Alibaba, Tencent, Huawei, AWS, LLM APIs |
| [Datasource Module](utils-support-datasource-parent/README.md) | 30+ database/datasource connectors |
| [File System Module](utils-support-filesystem-parent/README.md) | Documents, images, video, email |
| [Deep Learning Module](utils-support-deeplearning-parent/README.md) | ONNX, PyTorch, TensorFlow inference |
| [Protocol Module](utils-support-protocol-parent/README.md) | 50+ network protocols |
| [Common Starter](utils-support-core-parent/utils-support-common-starter/README.md) | Core utilities, SPI framework |
| [Capability List](CAPABILITY.MD) | Full capability overview |
| [Changelog](CHANGELOG.md) | Release history |

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes
4. Push and open a Pull Request

## License

Apache License 2.0