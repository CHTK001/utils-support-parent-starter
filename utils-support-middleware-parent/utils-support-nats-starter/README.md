# utils-support-nats-starter

> NATS 消息中间件集成：提供 **Core Pub/Sub、Request/Reply、JetStream、Key-Value Store、Object Store** 的全功能链式客户端 `NatsClient`，并附带一个基于 NATS Core 的跨进程消息分发 SPI 实现 `NatsDispatcherProvider`（`@Spi("nats")`）。
>
> 版本：`4.0.0.51`（随父 POM `utils-support-middleware-parent`）｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.nats.support`（`client` / `dispatcher` 两个包）

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 模块性质 | NATS 集成工具库：一个链式 API 客户端 `NatsClient` + 一个消息分发 SPI 实现 `NatsDispatcherProvider` | 不是 Spring Boot 自动配置 starter（无 spring.factories / AutoConfiguration.imports） |
| 能力范围 | Core Pub/Sub、Request/Reply（同步/异步）、JetStream 流管理与收发、Key-Value Store、Object Store | 不含 Kafka / RabbitMQ / MQTT / Redis 等其他中间件（各自在同级 `*-starter`） |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `io.nats:jnats` | 不依赖 Spring，不依赖任何数据库驱动或 AI SDK |
| SPI 角色 | 实现 common-starter 的 `DispatcherProvider` SPI（SPI 名 `nats`） | 不定义新的 SPI 接口 |
| 注册方式 | 客户端直接 `new` / `builder()`；分发器通过 `DispatcherFlow.register(...)` 手动注册 | 本模块 resources 下没有 `META-INF/extensions` 注册文件 |

源码构成：2 个主类（`NatsClient`、`NatsDispatcherProvider`）+ 2 个 `package-info`；作者标记 `@author CH`、引入版本 `@since 4.0.0.42`。另含 GraalVM native-image 的 `reflect-config.json`（对两个主类注册全量反射）。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建并连接客户端 | `NatsClient.builder().url("nats://127.0.0.1:4222").build().start()` | 默认地址 `nats://localhost:4222`（`NatsClient.DEFAULT_URL`）；另有 `create()` / `create(url)` 快捷工厂 |
| 发布消息 | `client.publish().subject("orders.create").body("{\"id\":1}").send()` | 支持字节/字符串消息体、`replyTo`、`header`/`headers`；`sendAsync()` 异步 |
| 订阅消息 | `client.subscribe().subject("orders.>").onString(System.out::println).start()` | 支持队列组 `queue(...)`、`autoAck(...)`；返回 `DispatchedSubscription` |
| 请求/应答 | `client.request("service.calc", "{\"x\":1}")` | 同步默认 5 秒超时；`requestAsync(...)` 返回 `CompletableFuture<byte[]>` |
| JetStream 流 | `client.jetStream().stream("ORDERS").addStream()` / `.publish(body)` | 流管理：`addStream` / `deleteStream` / `getStream` / `listStreams`；订阅走 `subscribe(handler[, consumerName])` |
| Key-Value 存储 | `client.kvStore("users").put("usr_001", json)` / `.getString("usr_001")` | 还有 `createOrGet` / `get` / `delete` / `keys` / `deleteBucket` |
| Object Store | `client.objectStore("files").put("doc.pdf", pdfBytes)` | 上传/查询/删除/列表：`put` / `get` / `delete` / `list` |
| 跨进程事件分发 | `new NatsDispatcherProvider(config)` + `DispatcherFlow` | SPI 名 `nats`；订阅方用 `@Subscribe(topic=..., type="nats")` |

### 2.2 操作入口总览

| 入口（NatsClient 方法） | 返回的操作对象 | 能力 |
| --- | --- | --- |
| `publish()` | `PublishOperation` | Core 发布：`subject` / `body(byte[]或String)` / `replyTo` / `header` / `headers` → `send()`、`sendAsync()` |
| `subscribe()` | `SubscribeOperation` | Core 订阅：`subject` / `queue` / `autoAck` / `handler(Consumer<Message>)` / `onMessage(Consumer<byte[]>)` / `onString(Consumer<String>)` → `start()` |
| `request(...)` / `requestAsync(...)` | `byte[]` / `String` / `CompletableFuture<byte[]>` | Request/Reply；默认超时 5 秒，可传 `Duration` 覆盖 |
| `jetStream()` | `JetStreamOperation` | 流管理：`stream(name)` → `StreamOperation`；`addStream(StreamConfiguration)` / `deleteStream` / `getStream` / `listStreams` |
| `kvStore(bucketName)` | `KvOperation` | KV 存储：`config(KeyValueConfiguration)` / `createOrGet` / `put` / `get` / `getString` / `delete` / `keys` / `deleteBucket` |
| `objectStore(bucketName)` | `ObjectStoreOperation` | 对象存储：`createOrGet` / `put(name, data[, metadata])` / `get` / `delete` / `list` |
| `isConnected()` / `getServerInfo()` | `boolean` / `ServerInfo` | 连接健康检查、服务器信息 |
| `shutdown()` / `close()` | — | 取消全部订阅并关闭连接；实现 `AutoCloseable` |

`StreamOperation`（`client.jetStream().stream(name)`）细则：

- `config(StreamConfiguration)`：不配置时默认按流名创建 `StorageType.File` 的流。
- `addStream()`：创建或更新流，返回 `StreamInfo`。
- `publish(byte[])` / `publish(String)` / `publish(byte[], Map<String,String> headers)`：发布到流，返回 `PublishAck`。
- `subscribe(handler)` / `subscribe(handler, consumerName)`：推送模式订阅；消费者默认名 `default-consumer`；内部以虚拟线程轮询消费，处理成功 `ack`、异常 `nak`；返回 `DispatchedSubscription`。

`DispatchedSubscription`：`unsubscribe()` 取消订阅；`pause()` / `resume()` 当前为空实现（jnats 未提供暂停/恢复接口）。

异常模型：所有失败统一抛 `NatsClientException`（`RuntimeException` 子类）。Core 订阅的 handler 抛异常只记录日志、不中断分发；`autoAck(true)` 时仅对 JetStream 消息自动 `ack`。

---

## 3. 快速开始

最短可运行示例（与源码 javadoc 用法一致）：

```java
import com.chua.nats.support.client.NatsClient;

// 1. 创建客户端并连接
NatsClient client = NatsClient.builder()
        .url("nats://127.0.0.1:4222")
        .build();
client.start();

// 2. Core Pub/Sub
client.subscribe().subject("orders.>").onString(msg ->
        System.out.println("收到: " + msg)).start();
client.publish().subject("orders.create").body("{\"id\":1}").send();

// 3. Request/Reply
String reply = client.request("service.calc", "{\"x\":1}");

// 4. JetStream
client.jetStream().stream("ORDERS").addStream();
client.jetStream().stream("ORDERS").publish("{\"id\":2}");

// 5. Key-Value Store
client.kvStore("users").put("usr_001", "{\"name\":\"Alice\"}");

// 6. Object Store
byte[] pdfBytes = /* 文件字节 */;
client.objectStore("files").put("doc.pdf", pdfBytes);

// 7. 用完关闭（AutoCloseable）
client.close();
```

跨进程事件分发（`NatsDispatcherProvider` + `DispatcherFlow`）：

```java
import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherFlow;
import com.chua.common.support.concurrent.dispatcher.Subscribe;
import com.chua.nats.support.dispatcher.NatsDispatcherProvider;

// 1. 订阅方：@Subscribe 方法必须有且仅有一个入参
public class OrderSubscriber {
    @Subscribe(topic = "orders.create", type = "nats")
    public void onOrder(String body) {
        System.out.println("收到订单: " + body);
    }
}

// 2. 组装：先 start 建立连接，再注册到 DispatcherFlow
NatsDispatcherProvider provider = new NatsDispatcherProvider(
        DispatcherConfig.builder().url("nats://127.0.0.1:4222").build());
provider.start();

DispatcherFlow flow = new DispatcherFlow(false); // false = 不挂默认内存分发器
flow.register(provider);
flow.register(new OrderSubscriber());

// 3. 发布（body 会以 toString() 转为字符串消息）
flow.publish("orders.create", "{\"id\":1}");

// 4. 关闭
flow.close();
```

> 注意：`NatsDispatcherProvider` 必须先调用 `start()` 再注册订阅者；未连接时 `publish` 只会告警并丢弃消息。

---

## 4. 配置项

**Spring 配置属性：无。** 依据：本模块 `src/main/resources` 下仅有 `META-INF/native-image/com.chua/utils-support-nats-starter/reflect-config.json`，不存在 `application*.yml`/`*.properties`、`spring.factories`、`AutoConfiguration.imports`，源码中也没有 `@ConfigurationProperties` 类。所有配置都通过代码显式传入。

有效配置入口一：`NatsClient.Builder`：

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `url(String)` | `nats://localhost:4222`（`DEFAULT_URL`） | NATS 服务器地址 |
| `credentials(String username, String password)` | 无 | 用户名/密码认证 |
| `token(String)` | 无 | 令牌认证 |
| `connectionTimeout(Duration)` / `connectionTimeoutMillis(long)` | 5 秒 | 连接超时 |
| `reconnectWait(Duration)` | 2 秒 | 重连等待间隔 |
| `maxReconnects(int)` | 60 | 最大重连次数 |
| `pedantic(boolean)` | `false` | pedantic 协议模式 |

有效配置入口二：`DispatcherConfig`（`NatsDispatcherProvider` 构造参数）。本实现只读取其中的 `url`；连接超时 5 秒、重连间隔 2 秒、`maxReconnects = -1`（无限重连）均为源码固定值，不可通过配置修改。`DispatcherConfig` 的其余字段（`dataPath`、`offsetPath`、`groupId`、`clientId`、`serializer` 等）在本实现中未被使用。

---

## 5. SPI 扩展点

本模块**不定义新的 SPI 接口**，它做两件事：

1. **提供 common-starter `DispatcherProvider` SPI 的 NATS 实现**：`NatsDispatcherProvider`，注解 `@Spi("nats")`，继承 `AbstractDispatcherProvider`，基于 NATS Core Pub/Sub 实现跨进程发布订阅。
   - 生命周期方法：`start()`（建立连接并创建 Dispatcher）、`publish(topic, body)`、`subscribe(definition)`、`unsubscribe(definition)`、`close()`。
   - 消息语义：发布时消息体以 `body.toString()` 转 UTF-8 字节（`null` 视为空串）；收到消息后按主题分发给该主题注册的所有 `DispatcherDefinition`。
   - 注册方式：本模块没有 `META-INF/extensions` 注册文件（resources 下仅 `reflect-config.json`），需手动注册：`dispatcherFlow.register(new NatsDispatcherProvider(config))`；`DispatcherFlow` 在扫描 `@Subscribe` 注解时按 `type` 与 `@Spi` 的值 `nats` 匹配路由。
2. **GraalVM native-image 反射登记**：`reflect-config.json` 对 `NatsClient` 与 `NatsDispatcherProvider` 注册了全部构造器、方法、字段的反射访问。

如需扩展其他中间件的分发器，请实现 common-starter 的 `DispatcherProvider`（或继承 `AbstractDispatcherProvider`）并打上自己的 `@Spi` 名称，注册进 `DispatcherFlow` 即可，与本模块无耦合。

---

## 6. 构建与依赖

Maven 坐标：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-nats-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}` = `4.0.0.51`（随根 POM 托管） | 提供 `DispatcherProvider` / `DispatcherConfig` / `DispatcherDefinition` / `@Spi` 等契约 |
| `io.nats:jnats` | `2.26.4`（middleware parent 的 `nats.version` 属性） | NATS 官方 Java 客户端 |

- 父模块链：`utils-support-parent-starter` → `utils-support-middleware-parent` → 本模块。
- JDK：`25`（根 POM `maven.compiler.source/target=25`）；源码编码 UTF-8。
- 模块内构建：在仓库根执行 `mvn -o -q compile -DskipTests`，EXIT=0 为通过。
- License / 维护团队信息：源码未提供（本模块 POM 无 license 节点）。

---

## 7. 边界：这些能力不在本模块

- **不是 Spring Boot starter**：没有自动配置类、没有配置属性前缀、没有条件装配；引依赖不会自动连 NATS，必须显式 `build().start()` 或注册 `NatsDispatcherProvider`。
- **其他中间件**：Kafka、RabbitMQ、MQTT、Redis、Chronicle、Nacos、Zookeeper、Apollo 等在 `utils-support-middleware-parent` 下的各自 `*-starter`，本模块只管 NATS。
- **分发器不支持 JetStream**：`NatsDispatcherProvider` 只走 Core Pub/Sub 的字符串消息；JetStream / KV / Object Store 能力只在 `NatsClient` 上。
- **订阅暂停/恢复不可用**：`DispatchedSubscription.pause()` / `resume()` 是空实现（jnats 当前版本未提供对应接口）。
- **Object Store 元数据有限**：`put(name, data, metadata)` 把 `Map` 拼接为对象描述（`description`）保存，不是独立的键值元数据存储。
- **运维能力缺失**：不提供 NATS 服务器部署、账号/权限管理、监控大盘等运维能力。
- **超时与重连口径**：`request` 默认 5 秒超时；`NatsClient` 重连参数见第 4 节 Builder 默认值；`NatsDispatcherProvider` 固定无限重连（`maxReconnects = -1`）。
