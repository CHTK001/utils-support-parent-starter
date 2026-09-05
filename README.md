# Utils Support Parent Starter

[![Java Version](https://img.shields.io/badge/Java-25%2B-blue)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)

一个功能强大、高度可扩展的 Java 工具包集合，通过 SPI 机制实现插件化架构，提供丰富的可复用工具能力。

## 目录

- [项目简介](#项目简介)
- [模块总览](#模块总览)
- [核心模块说明](#核心模块说明)
- [已添加依赖及位置](#已添加依赖及位置)
- [快速开始](#快速开始)
- [高级使用示例](#高级使用示例)
- [构建说明](#构建说明)
- [文档索引](#文档索引)
- [GraalVM 支持](#graalvm-支持)
- [贡献](#贡献)

---

## 项目简介

企业级 Java 工具包集合，模块独立封装，通过 SPI 实现可扩展能力，提供统一接口替换复杂依赖。

### 核心特性

- **插件化架构**：SPI 机制驱动，按需加载扩展
- **模块零耦合**：各 starter 相互独立，可按需选用
- **统一接口**：ProtocolServer / FileStorage / ChatClient / IdentificationEngine 等
- **响应式支持**：集成 Netty、Vert.x、Disruptor
- **动态配置**：支持热更新/灰度发布、Spring 集成

---

## 模块总览

```
utils-support-parent-starter/
├── core-parent/                              # 核心基础模块（必选）
│   ├── common-starter                        # 类型转换、JSON、加密、HTTP 客户端
│   ├── spring-starter                        # Spring 框架支持
│   ├── springboot-starter                    # Spring Boot 自动装配
│   ├── datasource-starter                    # 数据源引擎（JdbcEngine/ReactorEngine）
│   ├── filesystem-starter                    # 文件格式处理（ZIP/TAR/CSV/JSON/XML）
│   ├── network-starter                       # 网络工具（下载器、代理）
│   ├── spider-starter                        # 爬虫引擎
│   ├── auth-starter                          # 认证授权
│   ├── flow-starter                          # 流程引擎
│   ├── payment-starter                       # 支付接入
│   ├── maven-starter                         # Maven 工具
│   └── osgi-starter                          # OSGi 支持
│
├── datasource-parent/                        # 数据库/数据源（30+ 种）
│   ├── mysql-starter / postgresql-starter    # 关系型：MySQL、PG、Oracle、SQL Server
│   ├── redis-starter / mongodb-starter       # NoSQL：Redis、MongoDB
│   ├── milvus-starter / jvector-starter      # 向量库：Milvus、JVector
│   ├── clickhouse-starter / duckdb-starter   # 分析型：ClickHouse、DuckDB
│   ├── hbase-starter / neo4j-starter         # 宽表/图：HBase、Neo4j
│   ├── hibernate-starter                     # ORM：Hibernate
│   ├── calcite-starter / shardingv5-starter  # SQL引擎：Calcite、ShardingSphere
│   ├── debezium-starter                      # CDC：Debezium
│   ├── elasticsearch-starter                 # 搜索引擎：Elasticsearch
│   ├── lucene-starter                        # 全文检索：Lucene
│   ├── parquet-starter / oracle-starter      # 列式存储 / Oracle
│   ├── influxdb-starter / greptimedb-starter # 时序数据库
│   ├── sqlite-starter                        # 嵌入式 SQLite（含 Reactor 引擎）
│   ├── h2-starter                            # H2 内存数据库
│   ├── clickhouse-starter / mysql-starter    # 专用引擎
│   ├── duckdb-starter / postgresql-starter
│   ├── sqlserver-starter / oracle-starter
│   └── tablesaw-starter / solr-starter       # 表格计算 / Solr
│
├── filesystem-parent/                        # 文件系统 & 文件格式
│   ├── excel-starter / pdf-starter           # 文档：Excel、PDF、Word、OFD
│   ├── image-starter / image-apng-starter    # 图片：处理、APNG 动画
│   ├── ffmpeg-starter / ffmpeg-javacv-starter # 音视频：FFmpeg、JavaCV
│   ├── ffmpeg-rust-starter                   # FFmpeg Rust 绑定
│   ├── email-starter                         # 邮件：SMTP/IMAP
│   ├── aspose-starter / libreoffice-starter  # 格式转换：Aspose、LibreOffice、OnlyOffice
│   ├── json-starter                          # JSON 处理
│   ├── groovy-starter                        # Groovy 脚本
│   ├── pandoc-starter                        # Markdown 转换
│   ├── filestorage-starter                   # 文件存储（含压缩格式预览）
│   ├── filesearch-starter                    # 文件搜索（Rust 后端）
│   ├── zxing-starter / geoip2-starter        # 二维码、IP 定位
│   ├── ip2region-starter / qqwry-starter     # IP 地址库
│   ├── syslog-starter / html-starter         # 系统日志、HTML 处理
│   ├── dbf-starter / word-starter / ofd-starter
│   └── ibd-starter                           # MySQL IBD 文件解析
│
├── network-parent/                           # 网络协议（30+ 协议）
│   ├── httpclient-starter / okhttp-starter   # HTTP 客户端
│   ├── armeria-starter / rsocket-starter     # HTTP/2 响应式 RPC
│   ├── rpc-dubbo-starter / rpc-sofa-starter  # RPC 框架
│   ├── ssh-starter / ftp-starter / smb-starter # 远程文件传输
│   ├── git-starter                           # Git 操作
│   ├── vertx-starter                         # Vert.x 异步框架
│   ├── playwright-starter                    # 浏览器自动化
│   ├── webview-starter / webview-jcef-starter # 嵌入式 WebView
│   ├── acme-starter / ngrok-starter          # 证书申请 / 内网穿透
│   ├── docker-starter / quarkus-starter      # Docker / Quarkus
│   ├── socketio-starter / sinch-starter      # Socket.IO / 短信
│   └── tshark-starter / kcp-starter          # 抓包分析 / KCP 协议
│
├── cloud-parent/                             # 云服务商集成
│   ├── alibaba-starter / tencent-starter     # 阿里云 OSS / 腾讯云 COS
│   ├── huawei-starter / baidu-starter        # 华为 OBS / 百度 BOS
│   ├── amazon-starter / google-starter       # AWS S3 / Google Cloud
│   ├── openai-starter / claude-starter       # AI 大模型 API（统一 ChatClient）
│   ├── zhipu-starter / doubao-starter        # 智谱 GLM / 豆包
│   ├── ollama-starter / xunfei-starter       # 本地模型 / 讯飞
│   ├── huggingface-starter / modelscope-starter # HuggingFace / ModelScope
│   ├── dingding-starter / feishu-starter     # 钉钉 / 飞书机器人
│   ├── hikvision-starter / uniview-starter   # 海康威视 / 宇视摄像头
│   └── qiniu-starter / wechat-starter        # 七牛云 / 微信
│
├── deeplearning-parent/                      # 深度学习
│   ├── deeplearning-onnx-starter             # ONNX Runtime 推理
│   ├── deeplearning-pytorch-starter          # PyTorch
│   ├── deeplearning-tensorflow-starter       # TensorFlow
│   ├── deeplearning-opencv-starter           # OpenCV 计算机视觉
│   ├── deeplearning-arcsoft-starter          # ArcSoft 人脸检测
│   ├── deeplearning-tesseract-starter        # Tesseract OCR
│   ├── deeplearning-agentscope-starter       # AgentScope 多 Agent 协作
│   ├── deeplearning-langchain4j-starter      # LangChain4j
│   └── deeplearning-safetensors-starter      # Safetensors 模型加载
│
├── middleware-parent/                        # 中间件
│   ├── kafka-starter / rabbitmq-starter      # 消息队列
│   ├── nacos-starter / zookeeper-starter     # 注册中心 / 配置中心
│   ├── redis-starter                         # Redis 客户端
│   ├── prometheus-starter / sentinel-starter # 监控 / 限流
│   ├── chronicle-starter                     # Chronicle Queue 高性能日志
│   ├── docker-starter                        # Docker 管理
│   ├── mqtt-starter / minio-starter          # MQTT / MinIO
│   └── apollo-starter / undertow-starter     # Apollo 配置 / Undertow
│
├── datatask-parent/                          # 数据任务
│   ├── datasearch-starter                    # 数据搜索（音乐/视频/汇率/IP）
│   ├── datarecovery-starter                  # 数据恢复
│   ├── datasync-starter / datasync-agent-starter # 数据同步
│   ├── quartz-starter                        # 定时任务
│   └── retry-starter                         # 重试机制
│
├── datalake-parent/                          # 数据湖
│   ├── datalake-starter                      # 数据湖核心（服务器）
│   ├── datalake-sink-starter                 # 数据湖写入（Sink SPI）
│   ├── datalake-subscribe-starter            # 数据湖订阅器
│   ├── datalake-query-starter                # 数据湖 HTTP 查询
│   ├── datalake-cdc-starter                  # CDC MySQL 变更捕获
│   ├── datalake-mqtt-starter                 # MQTT 5.0 入站适配器
│   └── datalake-webhook-starter              # HTTP Webhook 入站适配器
│
├── extra-parent/                             # 扩展组件
│   ├── fastjson-starter / gson-starter / serialize-starter # JSON 序列化
│   ├── crypto-starter / captcha-starter      # 加密 / 验证码
│   ├── desktop-starter / oshi-starter        # 桌面 / 系统信息
│   ├── metrics-starter                       # 指标采集
│   ├── tika-starter                          # 文档内容提取
│   └── fory-starter                          # Fory 序列化
│
└── runtime-parent/                           # 运行时增强
    ├── runtime-starter                       # 运行时核心
    ├── runtime-agent                         # Agent 注入
    ├── runtime-apm                           # APM 监控
    └── runtime-shell                         # 命令行工具
```

---

## 核心模块说明

### utils-support-common-starter（核心基础）

`utils-support-common-starter` 是本项目的基础工具集，提供 Java 项目日常开发常用工具。

#### 类型转换 — Converter

```java
Integer num = Converter.convertIfNecessary("123", Integer.class);
Long longVal = Converter.convertIfNecessary("999999", Long.class);
Date date = Converter.convertIfNecessary("2024-01-01", Date.class);
User user = Converter.convertIfNecessary(map, User.class);
```

#### JSON 处理 — Json

```java
String json = Json.toJson(user);
User user = Json.fromJson(json, User.class);
String pretty = Json.toPrettyJson(user);
List<User> users = Json.fromJson(jsonArr, new TypeReference<List<User>>(){});
```

#### 加解密 — Codec / DigestUtils

```java
// AES
Codec aes = CodecBuilder.newBuilder().type(CryptoType.AES).key("1234567890123456").build();
String encrypted = aes.encodeHex("hello");

// MD5 / SHA / SM
String md5 = DigestUtils.md5Hex("hello");
String sm3 = DigestUtils.sm3Hex("abc".getBytes());

// Base64
String base64 = Base64Codec.encode("hello".getBytes());
```

#### SPI 扩展机制 — ServiceProvider

```java
@Spi("default")
public interface MyService { void execute(); }

ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);
MyService service = provider.getExtension("default");
```

#### 工具类

| 工具类 | 说明 |
|--------|------|
| `CollectionUtils` / `MapUtils` | 空判断、合并、遍历 |
| `FileUtils` / `IoUtils` | 文件读写、目录操作 |
| `IdUtils` | UUID、雪花算法 ID |
| `DateTime` | 日期时间格式化与计算 |
| `HttpClient` | HTTP 请求（GET/POST/文件上传） |
| `ProgressBar` | 终端进度条 |
| `ConnectionPool` | 通用连接池 |
| `ExpressionParser` | EL/Groovy/Aviator 表达式 |
| `ReturnResult` | 统一 API 响应封装 |
| `DeadLetterQueue` | 内存/JDBC/文件死信队列 |

> 详细文档见 [common-starter README](utils-support-core-parent/utils-support-common-starter/README.md)

---

## 已添加依赖及位置

以下为本次构建修复及版本适配过程中新增/调整的依赖，按模块整理：

### 1. JUnit 5 测试框架（全局）

| 位置 | 依赖 | 用途 |
|------|------|------|
| `pom.xml`（根） | `junit-jupiter-api:5.11.4` (test) | 全局测试 API |
| `pom.xml`（根） | `junit-jupiter-engine:5.11.4` (test) | 全局测试执行引擎 |
| `utils-support-datasource-starter/pom.xml` | `junit-jupiter-api:5.11.4` (test) | 数据源模块测试 |
| `utils-support-datasource-starter/pom.xml` | `junit-jupiter-engine:5.11.4` (test) | 数据源模块测试执行 |

### 2. Mockito 5（网络模块）

| 位置 | 依赖 | 用途 |
|------|------|------|
| `utils-support-network-starter/pom.xml` | `mockito-core:5.14.2` (test) | 网络模块单元测试 Mock |

### 3. Lombok Java 25 兼容配置

| 位置 | 配置项 | 说明 |
|------|--------|------|
| `utils-support-filesystem-starter/pom.xml` | `<fork>true</fork>` + `<compilerArgs>` 中的 `--add-exports/--add-opens` | Java 25 下 Lombok 注解处理器需要开放编译器内部模块 |
| `utils-support-filesystem-starter/pom.xml` | `<annotationProcessorPaths>` 显式声明 Lombok | 确保 Java 25 下注解处理器正确加载 |

> 注：根 `pom.xml` 的 compiler plugin 已全局配置 `--add-exports` 和 `--add-opens` 参数（`Jdk.compiler` 模块），各子模块可继承。

### 4. Reactor 响应式支持（SQLite 模块）

| 位置 | 依赖 | 用途 |
|------|------|------|
| `utils-support-sqlite-starter/pom.xml` | `reactor-core` | `SqliteReactorEngine` 响应式查询依赖 |
| `utils-support-sqlite-starter/pom.xml` | `reactor-test:${reactor.core.version}` (test) | 响应式测试支持 |
| `utils-support-sqlite-starter/pom.xml` | `r2dbc-spi:0.9.1.RELEASE` (test) | R2DBC 测试接口 |

新增类：`com.chua.sqlite.support.engine.SqliteReactorEngine`（继承 `JdbcReactorEngine`，提供 `boundedElastic` 线程池的非阻塞查询 API）。

### 5. 压缩格式支持（FileStorage 模块）

| 位置 | 依赖 | 用途 |
|------|------|------|
| `utils-support-filestorage-starter/pom.xml` | `xz:${tukaani.xz.version}` | `.tar.xz/.tzst` 压缩包预览 |
| `utils-support-filestorage-starter/pom.xml` | `zstd-jni:${zstd-jni.version}` | `.tar.zst` 压缩包预览 |
| `utils-support-filestorage-starter/pom.xml` | `dec:${brotli.dec.version}` | `.br` 压缩格式支持 |

版本属性定义在根 `pom.xml`：
- `tukaani.xz.version = 1.11`
- `zstd-jni.version = 1.5.7-6`
- `brotli.dec.version = 0.1.2`

---

## 快速开始

### 环境要求

- JDK 25+
- Maven 3.9+

### 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>${latest.version}</version>
</dependency>
```

### 代码示例

```java
// 类型转换
Integer num = Converter.convertIfNecessary("123", Integer.class);

// JSON
String json = Json.toJson(object);
MyClass obj = Json.fromJson(json, MyClass.class);

// HTTP 请求
HttpResponse response = HttpClient.get()
    .url("https://api.example.com/data")
    .header("Authorization", "Bearer xxx")
    .execute();

// SPI 扩展
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);
MyService service = provider.getExtension("default");
```

---

## 高级使用示例

### 1. 统一协议服务器 — ProtocolServer

```java
ProtocolServer server = ProtocolServer.create("http://0.0.0.0:8080");
server.get("/api/users", (request, response) -> {
    response.setContentType("application/json");
    response.setBodyString("{\"users\": []}");
});
server.start();
```

### 2. 统一 OSS 存储 — FileStorage

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

### 3. AI 大模型对话 — ChatClient

```java
// 同步对话
ChatClient client = ChatClient.create("openai", "your-api-key");
String response = client.chatSync("请介绍一下你自己");

// 流式对话
client.chat("写一首诗", chunk -> System.out.print(chunk.getContent()));
```

### 4. 深度学习推理 — IdentificationEngine

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

### 5. 分布式队列 — Disruptor

```java
DisruptorProvider<TaskEntity> provider = DisruptorProvider
    .builder(TaskEntity.class)
    .build();

Arrange<TaskEntity> arrange = provider.newArrange();
ArrangeGroup<TaskEntity> group = arrange.group();
group.handleEventsWith(new TaskHandler("task1", 1000));
group.after("task1").handleEventsWith(new TaskHandler("task2", 500));
provider.start();
provider.publish(entity -> entity.setId(UUID.randomUUID().toString()));
```

### 6. SQLite 响应式查询

```java
SqliteReactorEngine engine = new SqliteReactorEngine();
engine.addDataSource("mydb", "data/app.db");

// 响应式查询（非阻塞）
Flux<Map<String, Object>> rows = engine.query("SELECT * FROM users WHERE age > ?", 18);
rows.subscribe(System.out::println);

// 执行 DDL
Mono<Integer> affected = engine.execute("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY, v TEXT)");
affected.block();
```

---

## 构建说明

### 全量构建（跳过测试 + PMD）

```bash
mvn clean package -DskipTests -Dpmd.skip=true -Dmaven.test.skip=true
```

### 注意事项

- **Java 25 编译**：根 pom.xml 已配置 `--add-exports` / `--add-opens` JVM 参数，Lombok 1.18.46 可在 Java 25 下正常工作
- **PMD 跳过**：构建时加 `-Dpmd.skip=true` 可跳过 Alibaba P3C PMD 规则检查（避免 `aktStatus is NULL` 错误）
- **测试跳过**：加 `-Dmaven.test.skip=true` 可完全跳过测试编译和执行
- **部分模块重新构建**：
  ```bash
  mvn package -DskipTests -Dpmd.skip=true -Dmaven.test.skip=true -rf :utils-support-filestorage-starter
  ```

---

## 文档索引

| 文档 | 说明 |
|------|------|
| [核心模块文档](utils-support-core-parent/utils-support-common-starter/README.md) | 工具类、SPI、Converter、JSON |
| [数据源模块文档](utils-support-datasource-parent/README.md) | 30+ 种数据库/数据源支持 |
| [文件系统模块文档](utils-support-filesystem-parent/README.md) | 文档/图片/音视频/邮件 |
| [深度学习模块文档](utils-support-deeplearning-parent/README.md) | ONNX/PyTorch/TensorFlow 推理 |
| [云服务商集成文档](utils-support-cloud-parent/README.md) | 阿里云/腾讯云/AWS/AI 大模型 |
| [网络协议模块文档](utils-support-network-parent/README.md) | 30+ 网络协议支持 |
| [中间件模块文档](utils-support-middleware-parent/README.md) | Kafka/Redis/Nacos 等 |
| [CHANGELOG](CHANGELOG.md) | 版本变更记录 |

---

## GraalVM 支持

本项目针对 GraalVM / JDK 25 提供了基础支持：

- **GraalVM JVM 模式**：各模块作为 Java 库使用，兼容 GraalVM for JDK 25 直接编译运行（如 `org.graalvm.js:js` GraalJS 脚本引擎）
- **Native Image 元数据**：各模块 jar 包内 `META-INF/native-image/**` 含 reflect / resource / proxy / JNI 配置，Native 编译时自动合并
- **native profile**：`utils-support-core-parent` 提供 `-Pnative` Maven profile，`native-maven-plugin` 1.1.1 配合 `--enable-native-access=ALL-UNNAMED` 支持 `java.lang.foreign` FFM 调用

### 原生镜像构建

```bash
# 先安装所有模块到本地仓库
mvn clean install -DskipTests

# 在目标模块上执行 Native 编译（需安装 GraalVM for JDK 25，并设置 GRAALVM_HOME）
mvn -Pnative native:compile
```

### 注意事项

- `NativeUtils` / `NativeLoader` 通过 classpath `/native/{platform}` 目录枚举加载 Rust cdylib；若目录枚举不可用，调用 `NativeUtils.load(libName, null)` 显式指定
- 涉及 JNI 的动态链接模块（如 `utils-support-native-video-codec`）需在启动参数中设置 `java.library.path`

---

## 贡献

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交改动 (`git commit -m 'Add: some feature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 许可证

Apache License 2.0
