# utils-support-kafka-starter

Kafka 消息中间件集成：一个模块提供「业务收发客户端 + 只读管理面 SPI + 跨进程事件分发器 + Kafka WAL」四种能力，全部基于原生 `kafka-clients`，不引入 Spring Kafka。

- **模块坐标**：`com.chua:utils-support-kafka-starter`，版本随父模块 `utils-support-middleware-parent`（当前 `4.0.0.51`），核心类自 `4.0.0.42` 起提供
- **JDK**：Java 25（随仓库根 pom 的 `java.version=25` 编译）
- **基础包名**：`com.chua.kafka.support`（子包 `client` / `spi` / `dispatcher` / `wal`）

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界：这些能力不在本模块](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

| 它是 | 它不是 |
|---|---|
| `KafkaClient`：封装 Producer + Consumer + AdminClient 的全功能链式客户端（发布、订阅、Topic 管理） | 不是 Spring Boot Starter：源码未提供任何自动装配类、`@ConfigurationProperties` 或 `application.yml` 配置键 |
| `KafkaClientProvider`：中间件管理面只读 SPI 实现（连接测试、Topic/分区/消费组 lag/Broker/消息预览） | 不是管理控制台：不含 HTTP API、页面或 UI，管理能力只以 SPI 形式暴露给上层宿主 |
| `KafkaDispatcherProvider`：基于 Kafka 的跨进程发布订阅分发器（`DispatcherProvider` SPI） | 不是 Kafka Streams / Connect / Schema Registry：没有流式计算、连接器或 schema 管理能力 |
| `KafkaWalLog`：把 WAL 追加/回放/检查点落在 Kafka 主题上的 `WalLog` SPI 实现 | 不是通用消息网关：不提供重试队列、死信、事务或 Exactly-Once 语义封装 |
| 附带 GraalVM native-image 的反射与资源配置（`META-INF/native-image/com.chua/utils-support-kafka-starter/`） | 不负责 Kafka Broker 的安装、部署与运维 |

---

## 核心 API 与速查表

### KafkaClient（`com.chua.kafka.support.client.KafkaClient`）

链式 API，实现 `AutoCloseable`。三个操作入口：`producer()` / `consumer()` / `admin()`。

```java
KafkaClient client = KafkaClient.builder()
        .bootstrapServers("127.0.0.1:9092")   // 默认 127.0.0.1:9092
        .groupId("my-group")                  // 默认 default-group
        .autoOffsetReset("earliest")          // 也可用 property()/properties() 传任意原生参数
        .build()
        .start();                             // 初始化 Producer(acks=all) 与 AdminClient
```

- **生产**：`producer().topic(t).key(k).value(v).send()` 同步发送并返回 `RecordMetadata`；`sendAsync(callback)` 异步发送；可链式设置 `partition` / `timestamp` / `header(name, value)`。
- **消费**：`consumer().topic(t1, t2).handler(record -> ...)` 或 `onMessage(value -> ...)`，调 `subscribe()` 后在独立守护线程中轮询消费，返回消费者线程名；`commitSync()` 手动提交偏移量。
- **管理**：`admin().createTopic(name, partitions, replicationFactor)`、`deleteTopic(name)`、`listTopics()`、`describeTopic(name)`。
- **关闭**：`shutdown()` 或 `close()`，会中断并关闭全部消费者线程、Producer 与 AdminClient。
- 发送失败抛 `KafkaClient.KafkaClientException`（`RuntimeException`）。

### 速查表

| 你想做什么 | 入口 |
|---|---|
| 创建客户端 | `KafkaClient.builder()...build().start()`，或快捷工厂 `KafkaClient.create(bootstrapServers)` / `create(bootstrapServers, groupId)` |
| 同步发一条消息 | `client.producer().topic(t).key(k).value(v).send()` |
| 异步发一条消息 | `client.producer().topic(t).value(v).sendAsync(metadata -> ...)` |
| 后台线程订阅消费 | `client.consumer().topic(t).onMessage(v -> ...).subscribe()` |
| 建 / 删 / 查 Topic | `client.admin().createTopic(...)` / `deleteTopic(...)` / `listTopics()` / `describeTopic(...)` |
| 中间件管理面（只读查询：topics / brokers / groups / describe / preview） | `KafkaClientProvider`（SPI 名 `kafka`，实现 `MiddlewareClientProvider`） |
| 跨进程事件发布订阅 | `KafkaDispatcherProvider`（SPI 名 `kafka`，实现 `DispatcherProvider`） |
| 追加 / 回放 WAL 日志 | `KafkaWalLog`（SPI 名 `kafka`，实现 `WalLog`） |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-kafka-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最短可运行示例（收、发、管理）

```java
try (KafkaClient client = KafkaClient.builder()
        .bootstrapServers("127.0.0.1:9092")
        .groupId("my-group")
        .build()
        .start()) {

    // 建主题（管理面）
    client.admin().createTopic("order", 3, (short) 1);

    // 发消息（生产面）
    client.producer().topic("order").key("k1").value("{\"id\":1}").send();

    // 订阅消息（消费面，独立守护线程）
    client.consumer().topic("order").onMessage(msg ->
            System.out.println("收到: " + msg)).subscribe();

    // ... 业务处理 ...
}   // close() 自动关闭消费者线程、Producer 与 AdminClient
```

### 3. 通过管理面 SPI 做只读查询

`KafkaClientProvider` 的 `query()` 支持以下只读表达式（其余一律拒绝）：

| 表达式 | 返回内容 |
|---|---|
| `topics` | 主题列表及每个主题的分区数 |
| `brokers` | Broker 列表（id、host:port、rack） |
| `groups` | 消费组在各分区上的 committed / end / lag |
| `describe <topic>` | 主题分区详情（leader、副本数、ISR） |
| `preview <topic>` | 从每个分区队首读取的消息预览（默认 20 条，上限 200 条，单条超 4000 字符截断） |

消息预览使用独立的短生命周期消费者（`enable.auto.commit=false`、`auto.offset.reset=earliest`、`seekToBeginning`），不提交 offset，不影响真实消费位点。

---

## 配置项

**无统一的配置文件或配置键。** 源码未提供任何 Spring 配置项（无 `@ConfigurationProperties`、无 `application.yml/properties` 键），连接参数全部在代码中显式传入：

| 参数 | 传入位置 | 默认值 |
|---|---|---|
| `bootstrapServers` | `KafkaClient.Builder.bootstrapServers(String)` | `127.0.0.1:9092` |
| `groupId` | `KafkaClient.Builder.groupId(String)` | `default-group` |
| 任意原生 Kafka 参数 | `KafkaClient.Builder.property(key, value)` / `properties(Properties)` | 无 |
| 消费起始策略 | `Builder.autoOffsetReset(policy)`；`ConsumerOperation.offsetReset(String)` | Builder 侧未设时由 Kafka 决定；`ConsumerOperation` 默认 `latest` |
| 自动提交 | `Builder.enableAutoCommit(boolean)`；`ConsumerOperation.autoCommit(boolean)` | `ConsumerOperation` 默认 `true` |
| 单次拉取上限 | `Builder.maxPollRecords(int)` | 由 Kafka 默认值决定 |
| 消费轮询超时 | `ConsumerOperation.pollTimeout(long ms)` | `1000` ms |
| 管理面超时 | `KafkaClientProvider` 内部常量 | `3` 秒 |
| 分发器连接地址 / 分组 / 自动提交 | `DispatcherConfig.getUrl()` / `getGroupId()` / `isAutoCommitOffset()` | groupId 为空时用 `default-group` |
| WAL 主题名 / 本地目录 | `WalConfig.namespace()` / `WalConfig.walDir()` | namespace 即 topic；walDir 为空时用 `java.io.tmpdir/wal` |

注意：`KafkaWalLog` 的 bootstrap 地址在源码中硬编码为 `localhost:9092`，不可通过参数修改。

---

## SPI 扩展点

本模块通过 `@Spi` 注解 + `META-INF/extensions` 注册文件向外提供三个扩展点，均以 `kafka` 为 SPI 名：

| SPI 契约 | 实现 | 注册方式 |
|---|---|---|
| `com.chua.datasource.support.middleware.MiddlewareClientProvider` | `com.chua.kafka.support.spi.KafkaClientProvider`（`@Spi(value="kafka", order=200)`） | `META-INF/extensions/com.chua.datasource.support.middleware.MiddlewareClientProvider` → `kafka=com.chua.kafka.support.spi.KafkaClientProvider` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `com.chua.kafka.support.dispatcher.KafkaDispatcherProvider`（`@Spi("kafka")`） | `META-INF/extensions/com.chua.common.support.concurrent.dispatcher.DispatcherProvider` → `kafka=com.chua.kafka.support.dispatcher.KafkaDispatcherProvider` |
| `com.chua.common.support.wal.WalLog` | `com.chua.kafka.support.wal.KafkaWalLog`（`@Spi("kafka")`） | 注解声明，源码中未见对应 extensions 注册文件 |

三个实现的行为要点：

- **KafkaClientProvider（管理面，只读）**：`name()=kafka`、`displayName()=Apache Kafka`、`defaultPort()=9092`。支持连接测试（返回集群 ID、Broker/Topic 数量）、主题树与分区子节点、上述 5 种只读查询、以及 `document()` 元数据（主题 / 消费组 / Broker）。用户名非空时自动启用 `SASL_PLAINTEXT` + `PLAIN`。**刻意不实现任何写操作**：创建 Topic、删除 Topic、生产消息、重置 offset 一律不做——管理面的定位是「看清现状」，写操作留给业务侧。
- **KafkaDispatcherProvider（事件分发）**：`start()` 创建 Producer；`publish(topic, body)` 把 `body.toString()` 发出去（null 发空串）；`subscribe(DispatcherDefinition)` 按 definition 的每个 topic 起一个消费者线程（线程名 `kafka-dispatcher-%d`，守护线程），`auto.offset.reset=earliest`；`unsubscribe()` 移除定义；`close()` 关闭 Producer 与线程池。
- **KafkaWalLog（WAL）**：以 `WalConfig.namespace()` 为 topic；消息 key 为 LSN 字符串，value 为 `[op(1 字节) + payload]`；`append()` 同步发送并返回 LSN，`sync()` 即 `producer.flush()`；检查点持久化到 `(walDir 或 java.io.tmpdir/wal)/<topic>/checkpoint.dat`（8 字节 long）；支持 `replay(fromLsn[, toLsn], handler)`、`appendChain(handler)`、`findByLsn(lsn)`、`markCheckpoint/resetCheckpoint/forceCheckpoint`；`purgeCheckpointed()` 未实现，恒返回 `0`。

---

## 构建与依赖

```text
utils-support-kafka-starter
├── com.chua:utils-support-common-starter          # 基础工具与 SPI 设施
├── org.apache.kafka:kafka-clients                  # 版本由 middleware 父 pom 管理：3.9.0
└── com.chua:utils-support-datasource-starter       # 仅引 MiddlewareClientProvider SPI 契约，不引宿主
```

- 父模块：`com.chua:utils-support-middleware-parent:4.0.0.51`，再上层为 `com.chua:utils-support-parent-starter`。
- 依赖 `utils-support-datasource-starter` 的原因（pom 注释原文）：「中间件 SPI 契约：MiddlewareClientProvider 及其 VO。这里引契约而不是引宿主……实现方在下、宿主在上，反向依赖会把整个宿主拖进 Kafka 的使用方。」
- 编译：在 `utils-support-parent-starter` 下执行 `mvn -o -q compile -DskipTests` 即可；本模块无测试代码目录（源码中未提供）。
- GraalVM：`src/main/resources/META-INF/native-image/com.chua/utils-support-kafka-starter/` 下提供 `reflect-config.json` 与 `resource-config.json`。

---

## 边界：这些能力不在本模块

- **管理后台 / HTTP 接口 / 页面**：本模块只提供 `MiddlewareClientProvider` SPI 实现，管理界面与 REST 端点由上层宿主模块负责。
- **写操作管理 API**：`KafkaClientProvider` 明确不实现创建/删除 Topic、生产消息、重置 offset（源码注释：「只读。创建 Topic、删除 Topic、生产消息、重置 offset 一律不实现」）。
- **Spring Kafka 与 Spring 自动装配**：全部直接使用 `kafka-clients` 原生 API；源码未提供任何 Spring 配置类。
- **流式处理与连接器**：无 Kafka Streams、Kafka Connect、Schema Registry、Avro/Protobuf 序列化——序列化固定为 String（收发、分发器）或 byte[]（WAL）。
- **事务与 Exactly-Once**：未封装 Kafka 事务 API。
- **WAL 清理**：`KafkaWalLog.purgeCheckpointed()` 是空实现，恒返回 0，不做日志清理。
- **WAL 地址配置**：`KafkaWalLog` 的 bootstrap 地址硬编码 `localhost:9092`，需要其它地址时源码未提供配置入口。
- **Broker 运维**：不负责 Kafka 集群的安装、扩缩容、监控告警。
