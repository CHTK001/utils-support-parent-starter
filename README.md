# Utils Support Parent Starter

[![Java Version](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)

一个功能强大、高度可扩展的 Java 工具包集合，基于 SPI 机制实现插件化架构，提供丰富的开箱即用功能。

## 目录

- [项目简介](#项目简介)
- [模块概览](#模块概览)
- [快速开始](#快速开始)
- [高级功能示例](#高级功能示例)
- [安装](#安装)
- [文档索引](#文档索引)
- [贡献](#贡献)

---

## 项目简介

企业级 Java 工具包集合，模块化设计，通过 SPI 实现扩展。所有功能组件均可替换和扩展。

### 核心特性

- **插件化架构**：SPI 机制，热插拔扩展
- **模块化设计**：按需引入，降低依赖冲突
- **统一接口**：ProtocolServer、FileStorage、ChatClient、IdentificationEngine 等
- **高性能**：基于 Netty、Vert.x、Disruptor
- **动态插件**：运行时加载/卸载插件，Spring 集成

---

## 模块概览

```
utils-support-parent-starter/
│
├── core-parent/                              # ★ 核心基础（必选）
│   ├── common-starter                        # 类型转换、JSON、日期、加密、HTTP 客户端
│   ├── spring-starter                        # Spring 集成
│   ├── extension-starter                     # 扩展功能
│   ├── spider-starter                        # 爬虫框架
│   └── example-starter                       # 使用示例
│
├── cloud-parent/                             # 云服务集成
│   ├── alibaba-starter / tencent-starter     # 阿里云 OSS/短信/大模型
│   ├── huawei-starter / baidu-starter        # 华为 OBS、百度云
│   ├── amazon-starter / google-starter       # AWS S3、Google Cloud
│   ├── openai-starter / claude-parent        # AI 大模型 API（OpenAI 兼容服务统一使用 openai-starter）
│   ├── zhipu-starter / doubao-starter        # 智谱 GLM、豆包
│   ├── ollama-starter / xunfei-starter       # 本地推理、讯飞星火
│   ├── dingding-starter / feishu-starter     # 钉钉、飞书机器人
│   ├── hikvision-starter / uniview-starter   # 海康/宇视安防
│   └── qiniu-starter / unionpay-starter      # 七牛云、银联支付
│
├── datasource-parent/                        # 数据源（30+ 种）
│   ├── mysql-starter / postgresql-starter    # 关系型：MySQL、PG、Oracle、SQL Server
│   ├── redis-starter / mongodb-starter       # NoSQL：Redis、MongoDB、ES
│   ├── milvus-starter / jvector-starter      # 向量：Milvus、JVector
│   ├── clickhouse-starter / duckdb-starter   # 列存：ClickHouse、DuckDB
│   ├── hbase-starter / neo4j-starter         # 宽列：HBase、Neo4j 图
│   ├── hibernate-starter / mybatis-plus      # ORM：Hibernate、MyBatis-Plus
│   ├── calcite-starter / shardingv5          # SQL：Calcite 解析、ShardingSphere
│   └── debezium-starter / r2dbc-starter      # CDC、响应式 JDBC
│
├── protocol-parent/                          # 多协议通信（50+ 协议）
│   ├── netty-starter / vertx-starter         # 高性能 TCP/UDP
│   ├── armeria-starter / rsocket-starter     # HTTP/2、响应式流
│   ├── rpc-dubbo-starter / rpc-sofa-starter  # RPC 框架
│   ├── ssh-starter / ftp-starter             # 远程访问
│   ├── docker-starter / git-starter          # 容器与工具
│   ├── socketd-starter / socketio-starter    # Socket 框架
│   ├── datalake-starter                      # 数据湖
│   └── httpclient-starter / retrofit-starter # HTTP 客户端
│
├── filesystem-parent/                        # 文件系统
│   ├── excel-starter / pdf-starter           # 文档：Excel、PDF、Word、OFD
│   ├── image-starter / ffmpeg-starter        # 媒体：图片、SVG、FFmpeg 音视频
│   ├── email-starter                         # 邮件：SMTP/IMAP
│   ├── aspose-starter / libreoffice-starter  # 转换：Aspose、LibreOffice、OnlyOffice
│   ├── markdown-starter / groovy-starter     # 标记与脚本
│   └── zxing-starter / geoip2-starter        # 二维码、IP 地理定位
│
├── middleware-parent/                         # 中间件
│   ├── kafka-starter / rabbitmq-starter      # 消息队列
│   ├── nacos-starter / zookeeper-starter     # 注册中心/配置
│   ├── etcd-starter / consul-starter         # 分布式 KV
│   └── skywalking-starter                    # 链路追踪
│
├── deeplearning-parent/                      # 深度学习
│   ├── deeplearning-onnx-starter             # ONNX Runtime 推理
│   ├── deeplearning-pytorch-starter          # PyTorch
│   ├── deeplearning-tensorflow-starter       # TensorFlow
│   ├── deeplearning-opencv-starter           # OpenCV 计算机视觉
│   ├── deeplearning-arcface-starter          # ArcFace 人脸识别
│   ├── deeplearning-tesseract-starter        # OCR 文字识别
│   ├── deeplearning-speech-starter           # 语音识别
│   └── deeplearning-langchain4j-starter      # LangChain4j
│
├── derive-parent/                            # 衍生扩展
│   ├── cli-starter / javafx-starter          # CLI 应用、JavaFX 桌面
│   ├── oshi-starter / hanlp-starter          # 系统信息、中文 NLP
│   ├── pinyin-starter / jdk15on-starter      # 拼音、Bouncy Castle 加密
│   └── tiktoken-starter / perf-rust-starter  # Token 计数、性能工具
│
├── task-parent/                              # 任务调度
│   ├── quartz-starter                        # 企业级定时任务
│   ├── resilience4j-starter                  # 熔断/限流/重试
│   ├── chronicle-starter                     # 高性能持久化队列
│   └── guava-retrying-starter                # 重试机制
│
├── test-parent/                              # 浏览器自动化
│   ├── playwright-starter / selenium-starter # 浏览器自动化
│   └── htmlunit-starter                      # 无头浏览器
│
├── network-parent/                           # 网络支持
├── data-parent/                              # 数据处理
```

---

## 核心功能 — common-starter（必选模块）

`utils-support-common-starter` 是本项目的核心基础库，提供日常 Java 开发中最常用的工具功能。

### 类型转换 — Converter

统一类型转换器，支持任意类型间的智能转换：

```java
// 字符串 → 数字
Integer num = Converter.convertIfNecessary("123", Integer.class);
Long longVal = Converter.convertIfNecessary("999999", Long.class);
Boolean boolVal = Converter.convertIfNecessary("true", Boolean.class);

// 日期转换
Date date = Converter.convertIfNecessary("2024-01-01", Date.class);

// Map → Bean
User user = Converter.convertIfNecessary(map, User.class);

// 带默认值
Integer value = Converter.convertIfNecessary(null, Integer.class, 0);
```

### JSON 处理 — Json

基于 Jackson 封装，支持 JSON5 注释、泛型、格式化：

```java
// 对象 ↔ JSON
String json = Json.toJson(user);
User user = Json.fromJson(json, User.class);

// 格式化输出
String pretty = Json.toPrettyJson(user);

// 泛型
List<User> users = Json.fromJson(jsonArr, new TypeReference<List<User>>(){});

// JsonObject 操作
JsonObject obj = Json.parse(json);
String name = obj.getString("name");
```

### 日期时间 — DateTime

```java
DateTime now = DateTime.now();
String formatted = now.format("yyyy-MM-dd HH:mm:ss");

DateTime dt = DateTime.parse("2024-01-01 12:00:00");
DateTime tomorrow = now.plusDays(1);
now.isBefore(tomorrow);  // true
```

### HTTP 客户端 — HttpClient

```java
// GET
HttpResponse res = HttpClient.get("https://api.example.com/users")
    .header("Authorization", "Bearer token")
    .queryParam("page", "1")
    .execute();

// POST JSON
HttpClient.post("https://api.example.com/users")
    .contentType("application/json")
    .body(Json.toJson(user))
    .execute();

// 文件上传
HttpClient.post("https://api.example.com/upload")
    .file("file", new File("test.pdf"))
    .execute();

// 文件下载
HttpClient.get("https://example.com/file.zip")
    .downloadTo("./downloads/file.zip");
```

### 加解密 — Codec / DigestUtils

```java
// AES 加解密
Codec aes = CodecBuilder.newBuilder()
    .type(CryptoType.AES)
    .key("1234567890123456")
    .build();
String encrypted = aes.encodeHex("明文数据");
String decrypted = aes.decodeHex(encrypted);

// MD5 / SHA
String md5 = DigestUtils.md5Hex("hello");
String sha256 = DigestUtils.sha256Hex("hello");

// 国密 SM3 / SM4
String sm3 = DigestUtils.sm3Hex("abc".getBytes());
byte[] sm4enc = DigestUtils.sm4EncryptEcb(data, key);

// RSA
byte[] encrypted = rsa.encrypt("数据".getBytes());
byte[] decrypted = rsa.decrypt(encrypted);

// Base64
String base64 = Base64Codec.encode("hello".getBytes());
byte[] decoded = Base64Codec.decode(base64);
```

### SPI 服务发现 — ServiceProvider

基于 SPI 的插件化扩展机制，支持按名称获取、优先级排序、条件过滤：

```java
// 定义 SPI 接口
@Spi("default")
public interface MyService { void execute(); }

// 实现类
@Extension("impl1")
public class MyServiceImpl1 implements MyService { ... }

// 获取实现
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);
MyService service = provider.getExtension("impl1");

// 获取默认（最高优先级）
MyService defaultService = provider.getDefault();

// 获取所有
List<MyService> all = provider.list();
```

### 其他重要工具

| 功能 | 核心类 | 说明 |
|------|--------|------|
| 集合工具 | `CollectionUtils` / `MapUtils` | 判空、分区、扁平化、交集并集 |
| 文件工具 | `FileUtils` / `IoUtils` | 读写、复制、删除、目录遍历 |
| ID 生成 | `IdUtils` | UUID、雪花算法、短 ID |
| 版本号 | `Version` | 语义化版本解析与比较 |
| 进度条 | `ProgressBar` | 终端进度条，多种样式 |
| 对象池 | `ConnectionPool` | 通用连接池化管理 |
| 表达式引擎 | `ExpressionParser` | EL/Groovy/Aviator 表达式 |
| 返回结果 | `ReturnResult` | 统一 API 响应封装 |
| 任务编排 | `DisruptorProvider` | 基于 Disruptor 的无锁编排 |
| 数据同步 | `SyncFlow` | 多种数据源间的同步框架 |
| 事件分发 | `DispatcherFlow` | 本地/Chronicle Queue 事件分发 |
| IoC 容器 | `ConfigureObjectContext` | 轻量级 IoC 容器 |
| 动态 SQL | `OpenFactory` | 类 APIJSON 的动态查询 |
| ACME 证书 | `StandardAcmeServerAdaptor` | Let's Encrypt 证书自动申请 |
| 死信队列 | `DeadLetterQueue` | 内存/JDBC/文件死信队列 |

> 详细文档：[common-starter README](utils-support-core-parent/utils-support-common-starter/README.md)

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+

### 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>${latest.version}</version>
</dependency>
```

### 基础示例

```java
// 类型转换
Integer num = Converter.convertIfNecessary("123", Integer.class);

// 日期处理
DateTime now = DateTime.now();
String formatted = now.toString("yyyy-MM-dd HH:mm:ss");

// JSON
String json = Json.toJson(object);
MyClass obj = Json.fromJson(json, MyClass.class);

// HTTP 请求
HttpResponse response = HttpClient.get()
    .url("https://api.example.com/data")
    .newInvoker()
    .execute();

// SPI 扩展
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);
MyService service = provider.getExtension("default");
```

---

## 高级功能示例

### 1. 统一协议服务器 — ProtocolServer

支持 HTTP/WebSocket/TCP 等多协议的服务器，一行切换底层实现（Netty / Vert.x / Armeria）。

```java
// 创建 HTTP 协议服务器
ProtocolServer server = ProtocolServer.create("http://0.0.0.0:8080");

server.get("/api/users", (request, response) -> {
    response.setContentType("application/json");
    response.setBodyString("{\"users\": []}");
});

server.start();
```

> 详细文档：[common-starter](utils-support-core-parent/utils-support-common-starter/README.md)

### 2. 统一 OSS 存储 — FileStorage

跨云存储统一接口，阿里云/腾讯云/华为云/AWS 等 OSS 一键切换。

```java
FileStorage storage = FileStorage.createStorage("aliyun",
    FileStorageOption.builder()
        .accessKeyId("your-key")
        .accessKeySecret("your-secret")
        .endpoint("oss-cn-hangzhou.aliyuncs.com")
        .bucketName("your-bucket")
        .build());

PutResult result = storage.putObject("path/to/file.jpg", new File("local.jpg"));
```

> 详细文档：[cloud-parent](utils-support-cloud-parent/README.md)

### 3. AI 大模型对话 — ChatClient

统一 LLM 调用接口，支持 OpenAI、通义千问、DeepSeek、智谱 GLM 等。

```java
// 同步对话
ChatClient client = ChatClient.create("openai", "your-api-key");
String response = client.chatSync("你好，请介绍一下自己");

// 流式对话
client.chat("写一首诗", chunk -> System.out.print(chunk.getContent()));
```

> 详细文档：[cloud 模块](utils-support-cloud-parent/README.md)

### 4. 深度学习推理 — IdentificationEngine

统一推理引擎，支持 ONNX / PyTorch / TensorFlow / PaddlePaddle，覆盖目标检测、OCR、人脸识别。

```java
// YOLO 目标检测
IdentificationEngine engine = IdentificationEngine.builder()
    .engine("onnx")
    .configuration(DetectionConfiguration.builder()
        .option("modelPath", "/path/to/yolov8.onnx")
        .option("optDevice", "GPU")
        .build())
    .build();

List<PredictResult> results = engine.predict(new File("image.jpg"));

// OCR 识别
IdentificationEngine ocr = IdentificationEngine.builder()
    .engine("tesseract")
    .configuration(DetectionConfiguration.builder()
        .option("language", "chi_sim+eng")
        .build())
    .build();

ocr.predict(new File("document.png"));
```

> 详细文档：[deeplearning-parent](utils-support-deeplearning-parent/README.md)

### 5. 分布式任务编排 — Disruptor

基于 Disruptor 的高性能任务编排，无锁并发，毫秒级调度。

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
provider.publish(entity -> entity.setId(UUID.randomUUID().toString()));
```

> 详细文档：[common-starter](utils-support-core-parent/utils-support-common-starter/README.md)

### 6. 文件转换 — ConvertFileSystem

文档格式互转，自动检测转换器。

```java
ConvertFileSystem converter = ConvertFileSystem.autoDetect(
    new File("document.pdf"), "docx");
converter.convertTo(new File("document.docx"));
```

> 详细文档：[filesystem-parent](utils-support-filesystem-parent/README.md)

### 7. SPI 扩展机制

```java
// 定义 SPI 接口
@Spi
public interface MyService {
    String process(String input);
}

// 实现
@Spi("default")
public class DefaultMyService implements MyService {
    public String process(String input) {
        return "processed: " + input;
    }
}

// 获取实现
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);
MyService service = provider.getExtension("default");
```

### 8. 事件分发 — DispatcherFlow

多规则事件分发框架，支持本地、Chronicle Queue 持久化、服务发现。

```java
// 本地分发
DispatcherFlow flow = DispatcherFlow.createLocal();
flow.register(new UserEventHandler());
flow.start();
flow.publish(new UserCreatedEvent("user123", "张三"));
flow.close();

// Chronicle Queue 持久化分发
DispatcherFlow chronicleFlow = DispatcherFlow.createChronicle("/tmp/queue");
chronicleFlow.register(new OrderEventHandler());
chronicleFlow.start();
chronicleFlow.publish(new OrderCreatedEvent("order123", 100.0));
chronicleFlow.close();
```

### 9. 动态 SQL 查询 — JsonAPI

类 APIJSON 的动态查询语法。

```java
OpenFactory factory = OpenBuilder.newBuilder()
    .writerWithDefaultPrettyPrinter()
    .addDataSource(dataSource)
    .build();

String result = factory.get("""
{
    "User[]": {
        "@count": 10,
        "@page": 1,
        "status": 1,
        "@order": "createTime-"
    }
}
""");
```

> 各模块更多示例请查看对应子模块 README。

---

## 安装

### 从 Maven 中央仓库

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>${latest.version}</version>
</dependency>
```

### 从源码构建

```bash
git clone https://gitee.com/achtk/utils-support-parent-starter.git
cd utils-support-parent-starter
mvn clean install -DskipTests
```

---

## 文档索引

### 父模块文档

| 文档 | 说明 |
|------|------|
| [云服务集成](utils-support-cloud-parent/README.md) | 阿里云、腾讯云、华为云、AWS、百度云、AI 大模型等 |
| [数据源模块](utils-support-datasource-parent/README.md) | 30+ 种数据库/数据源支持 |
| [文件系统模块](utils-support-filesystem-parent/README.md) | 文档、图片、视频、邮件处理 |
| [深度学习模块](utils-support-deeplearning-parent/README.md) | ONNX、PyTorch、TensorFlow 推理 |
| [协议模块](utils-support-protocol-parent/README.md) | 50+ 网络协议支持 |
| [核心模块](utils-support-core-parent/utils-support-common-starter/README.md) | 基础工具、SPI 框架 |
| [能力清单](CAPABILITY.MD) | 项目能力总览 |
| [更新日志](CHANGELOG.md) | 版本更新记录 |

---

## 贡献

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改
4. 推送到分支并提交 Pull Request

## 许可证

Apache License 2.0