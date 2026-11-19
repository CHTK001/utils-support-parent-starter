# utils-support-rabbitmq-starter

> utils 体系的 **RabbitMQ 消息中间件集成**模块：提供全功能链式客户端 `RabbitmqClient`（连接、交换机、队列、绑定、发布、消费、事务），并基于 `utils-support-common-starter` 的 `DispatcherProvider` SPI 提供跨进程发布订阅实现（SPI 名 `rabbitmq`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.rabbitmq.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 跨进程发布订阅（DispatcherProvider SPI）](#4-跨进程发布订阅dispatcherprovider-spi)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rabbitmq-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 客户端 | `RabbitmqClient`：对 `com.rabbitmq:amqp-client` 的链式封装，覆盖 Exchange / Queue / Binding / Publish / Consume / 事务 | 不是 AMQP 协议的重新实现，也不替换底层连接实现 |
| 分发 | `RabbitmqDispatcherProvider`：`DispatcherProvider` SPI 的 `rabbitmq` 实现，基于 fanout 交换机做跨进程发布订阅 | 不是 Spring Boot starter：无自动配置类、无 `application.properties` 配置键 |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `com.rabbitmq:amqp-client` | 不依赖 Spring，不依赖其它中间件客户端 |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 建连 / 关闭 | `RabbitmqClient.builder()...build()` → `start()` / `close()` | 口令必须显式配置；实现 `AutoCloseable` |
| 声明 / 删除交换机 | `client.exchange().name("order").topic().durable(true).declare()` | direct / topic / fanout / headers；另有 `exists()` / `delete()` |
| 声明 / 删除 / 清空队列 | `client.queue().name("q").ttl(60000).deadLetterExchange("dlx").declare()` | 支持 TTL、死信、优先级、长度/大小上限；`declare()` 返回实际队列名 |
| 绑定 / 解绑 | `client.bind().queue("q").exchange("order").routingKey("order.*").bind()` | `unbind()` 解绑 |
| 同步发布 | `client.publish().exchange("order").routingKey("k").body(json).persistent(true).confirm(true).send()` | Confirm 超时默认 10000ms，可用 `confirmTimeout` 调整 |
| 异步发布 | `.sendAsync(ok -> ...)` | 开启 `confirm(true)` 时按 ack / nack 回调 |
| 消费 | `client.consume().queue("q").prefetch(10).autoAck(false).handler(msg -> { ...; msg.ack(); }).start()` | 返回 consumerTag；`stop(tag)` 停止消费 |
| 事务 | `client.tx().execute(ch -> { ...; return null; })` | TX 模式，异常自动回滚 |
| 跨进程发布订阅 | `RabbitmqDispatcherProvider`（SPI `rabbitmq`） | 配合 common-starter 的 `DispatcherFlow` / `@Subscribe` 使用 |

---

## 2. 核心 API 与速查表

模块只有两个公开类型：

| 类型 | 说明 |
| --- | --- |
| `com.chua.rabbitmq.support.client.RabbitmqClient` | RabbitMQ 全功能链式客户端（`@since 4.0.0.42`） |
| `com.chua.rabbitmq.support.dispatcher.RabbitmqDispatcherProvider` | RabbitMQ 分发器提供者，SPI 名 `rabbitmq`（`@since 4.0.0.42`） |

### 2.1 生命周期与 Channel

| 方法 | 说明 |
| --- | --- |
| `RabbitmqClient.builder()` / `create()` | 构建器 / 默认实例（见下方注意） |
| `start()` | 建立 `Connection`；连接与握手超时均为 `connectionTimeout`；自动恢复开启时网络恢复间隔固定 5000ms、拓扑恢复开启；失败抛 `RabbitmqClientException` |
| `shutdown()` / `close()` | 关闭全部缓存 Channel 与连接（幂等）；实现 `AutoCloseable`，可用 try-with-resources |
| `getChannel()` / `getChannel(name)` | 按名字缓存 Channel；`prefetch > 0` 时自动 `basicQos` |
| `closeChannel(name)` | 关闭并移除指定 Channel |

> 注意：`create()` 走默认 Builder（未设置口令），而 `build()` 强制显式口令，因此 `create()` 会抛出 `IllegalArgumentException`；实际使用请走 `builder().password(...)`。

### 2.2 六个操作入口

`exchange()` / `queue()` / `bind()` / `publish()` / `consume()` / `tx()`，均返回链式操作对象。

**ExchangeOperation**：`name` / `type`（默认 `direct`）/ `durable`（默认 `true`）/ `autoDelete`（默认 `false`）/ `arguments`；快捷类型 `direct()` / `topic()` / `fanout()` / `headers()`；动作 `declare()` / `delete()` / `exists()`（passive 声明，失败返回 `false`）。

**QueueOperation**：`name` / `durable`（默认 `true`）/ `exclusive`（默认 `false`）/ `autoDelete`（默认 `false`）/ `arguments`；队列参数快捷方法（写入对应 `x-*` 参数）：

| 方法 | 参数键 |
| --- | --- |
| `ttl(int ms)` | `x-message-ttl` |
| `queueTtl(int ms)` | `x-expires` |
| `deadLetterExchange(String)` | `x-dead-letter-exchange` |
| `deadLetterRoutingKey(String)` | `x-dead-letter-routing-key` |
| `maxLength(int)` | `x-max-length` |
| `maxLengthBytes(long)` | `x-max-length-bytes` |
| `priority(int)` | `x-max-priority` |
| `messageSizeLimit(long)` | `x-message-size-limit` |

动作：`declare()`（返回服务器实际队列名，匿名队列自动命名）/ `delete()` / `purge()` / `messageCount()` / `consumerCount()`。

**BindOperation**：`queue(q)` / `exchange(e)` / `routingKey(k)`（默认 `""`）/ `arguments(a)`；动作 `bind()` / `unbind()`。

**PublishOperation**：

- 目标与内容：`exchange` / `routingKey` / `body(String)`（按 UTF-8 编码）/ `body(byte[])`
- 消息属性：`contentType` / `messageId` / `correlationId` / `replyTo` / `expiration(ms)` / `priority` / `type` / `headers(Map)` / `header(key, value)`
- 投递模式：`persistent(true)` → deliveryMode=2；`nonPersistent(true)` → deliveryMode=1
- Confirm：`confirm(boolean)` + `confirmTimeout(long ms)`（默认 10000）
- 发送：
  - `send()`：同步发布；开启 confirm 后 `waitForConfirmsOrDie`
  - `sendAsync(Consumer<Boolean>)`：异步发布；confirm 模式下 ack → `true`、nack → `false`，未开启 confirm 时发布后立即回调 `true`
  - `sendAndReceive(long timeoutMs)`：RPC 模式（临时应答队列 + UUID correlationId）；返回值仅表示应答是否在超时内到达（非 null = 到达），不携带应答内容

**ConsumeOperation**：`queue` / `autoAck`（默认 `true`）/ `consumerTag` / `noLocal` / `exclusive` / `arguments` / `prefetch` / `channel(name)`；消息回调三选一：`handler(Consumer<MessageHandler>)` / `onMessage(Consumer<String>)`（UTF-8 解码）/ `onBytes(Consumer<byte[]>)`；`start()` 返回 consumerTag，`stop(consumerTag)` 取消消费。

**TxOperation**：`execute(TransactionCallback<T>)` —— `txSelect` → 回调 → `txCommit`，异常时 `txRollback` 并抛 `RabbitmqClientException`；`sendBatch(Runnable)` 为批量发送的事务包装。`TransactionCallback<T>#doInTransaction(Channel)` 为函数式接口。

### 2.3 MessageHandler（消费消息包装）

| 方法 | 说明 |
| --- | --- |
| `getBody()` / `getBodyAsString()` | 原始字节 / UTF-8 字符串 |
| `getDeliveryTag()` / `getExchange()` / `getRoutingKey()` | 信封信息 |
| `getProperties()` / `getHeaders()` / `getHeader(name)` | 消息属性与头 |
| `ack()` / `ackMultiple()` | 手动 ACK 单条 / 批量（含之前未确认消息） |
| `nack(boolean requeue)` | NACK，可选择是否重新入队 |
| `reject()` / `rejectAndDrop()` | 拒绝并重新入队 / 拒绝并丢弃 |

异常类型：`RabbitmqClient.RabbitmqClientException`（`RuntimeException` 子类）。

---

## 3. 快速开始

添加依赖：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rabbitmq-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

最小可运行示例（声明交换机 / 队列 → 绑定 → 发布 → 消费）：

```java
import com.chua.rabbitmq.support.client.RabbitmqClient;

try (RabbitmqClient client = RabbitmqClient.builder()
        .host("127.0.0.1").port(5672)
        .username("guest").password("guest")      // 口令必须显式配置，否则 build() 抛 IllegalArgumentException
        .virtualHost("/")
        .automaticRecovery(true)
        .connectionTimeout(5000)
        .heartbeat(30)
        .build()) {

    client.start();

    // 交换机
    client.exchange().name("order").topic().durable(true).declare();

    // 队列（TTL + 死信）
    client.queue().name("order.created").durable(true)
            .ttl(60000)
            .deadLetterExchange("order.dlx")
            .deadLetterRoutingKey("order.dead")
            .declare();

    // 绑定
    client.bind().queue("order.created").exchange("order").routingKey("order.*").bind();

    // 发布（持久化 + Confirm）
    client.publish().exchange("order").routingKey("order.created")
            .body("{\"id\":1}").persistent(true)
            .confirm(true)
            .send();

    // 消费（手动 ACK + prefetch）
    client.consume().queue("order.created")
            .prefetch(10)
            .autoAck(false)
            .handler(msg -> {
                System.out.println("收到: " + msg.getBodyAsString());
                msg.ack();
            })
            .start();
}
```

---

## 4. 跨进程发布订阅（DispatcherProvider SPI）

`RabbitmqDispatcherProvider` 实现了 common-starter 的 `com.chua.common.support.concurrent.dispatcher.DispatcherProvider`（SPI 名 `rabbitmq`）：每个 topic 映射为一个 **durable fanout 交换机**，订阅者各自持有自动删除的匿名队列绑定其上，实现一对多广播。

```java
import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherFlow;
import com.chua.common.support.concurrent.dispatcher.Subscribe;
import com.chua.rabbitmq.support.dispatcher.RabbitmqDispatcherProvider;

// 1. 组装分发器（本实现只读取 url 与 connectionTimeoutMillis 两个配置）
RabbitmqDispatcherProvider rabbitmq = new RabbitmqDispatcherProvider(
        DispatcherConfig.builder()
                .url("amqp://guest:guest@127.0.0.1:5672/")   // 交给 ConnectionFactory.setUri(...)
                .connectionTimeoutMillis(5000)
                .build());

DispatcherFlow flow = new DispatcherFlow(false);   // false = 不注册默认内存分发器
flow.register(rabbitmq);
rabbitmq.start();                                  // 必须手动 start() 建立连接，DispatcherFlow 不会自动调用

// 2. 订阅者：@Subscribe 方法必须有且仅有一个入参
public class OrderSubscriber {
    @Subscribe(topic = "order", type = "rabbitmq")
    public void onOrder(String message) {
        System.out.println("收到: " + message);
    }
}

OrderSubscriber subscriber = new OrderSubscriber();
flow.register(subscriber);        // 扫描 @Subscribe 自动订阅
flow.publish("order", "hello");   // 广播到所有已注册 Provider（消息体转 UTF-8 字符串）
flow.unregister(subscriber);
flow.close();
```

行为要点（均来自源码）：

- `publish(topic, body)`：以 topic 为交换机名、路由键为 `""` 发布；消息体 `body.toString()` 按 UTF-8 编码；IO 异常仅记录日志，不抛出。
- `subscribe(definition)`：对 definition 的每个 topic 依次「声明 fanout 交换机（durable）→ 声明匿名队列 → 绑定 → 自动 ACK 消费」；消息以 UTF-8 字符串交给 `DispatcherDefinition.dispatch(body)`，由 common-starter 转换为 `@Subscribe` 方法入参类型后反射调用。
- `unsubscribe(definition)`：仅从内存订阅表移除定义，不注销 RabbitMQ 消费者。
- 消费线程池：核心 0、最大无界、空闲 60s 回收、守护线程，名称 `rabbitmq-dispatcher-%d`。
- `close()`：置关闭标记 → 关闭 Channel / Connection → 关闭线程池 → 清空订阅表。
- 注意：交换机只在有订阅者注册时声明；没有任何订阅者时直接 `publish` 到未声明交换机，会被 RabbitMQ 拒绝（通道关闭）。

---

## 5. 配置项

**无 Spring Boot 配置键**：本模块资源目录只有 SPI 注册文件与 GraalVM native-image 配置，没有自动配置类，也没有 `application.properties` / `application.yml` 键，全部配置通过代码传入。

`RabbitmqClient.Builder` 默认值：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `host` | String | `127.0.0.1` | 主机 |
| `port` | int | `5672` | AMQP 端口 |
| `username` | String | `guest` | 用户名 |
| `password` | String | 无 | **必须显式配置**，否则 `build()` 抛 `IllegalArgumentException` |
| `virtualHost` | String | `/` | 虚拟主机 |
| `connectionTimeout` | int | `10000` | 连接与握手超时（毫秒） |
| `heartbeat` | int | `60` | 心跳（秒） |
| `automaticRecovery` | boolean | `true` | 连接自动恢复（网络恢复间隔固定 5000ms，拓扑恢复开启） |
| `prefetch` | int | `0` | 全局 Channel 预取数，>0 时 `basicQos` |

`DispatcherConfig`（common-starter 契约）：本模块的 `RabbitmqDispatcherProvider` 只读取 `url`（AMQP URI）与 `connectionTimeoutMillis`（默认 5000）；其余字段（`dataPath`、`offsetPath`、`sessionTimeoutMillis`、`maxRetries`、`groupId`、`clientId`、`autoCommitOffset`、`maxBatchSize`、`blockSize`、`serializer`）由契约定义，但未被本实现使用。

---

## 6. SPI 扩展点

- 本模块是**扩展实现方**：为 common-starter 的 `DispatcherProvider` SPI 提供 `rabbitmq` 实现。
  - 注解：`@Spi("rabbitmq")`
  - 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.concurrent.dispatcher.DispatcherProvider`，内容为 `rabbitmq=com.chua.rabbitmq.support.dispatcher.RabbitmqDispatcherProvider`
  - 基类：`AbstractDispatcherProvider`（持有 `DispatcherConfig`）
- 本模块自身**不提供**可扩展的 SPI 接口：除上述分发器实现外，`META-INF/extensions/` 下没有其它注册文件，源码未提供其它扩展点。
- 如需接入其它中间件（Kafka、NATS、MQTT 等），请在对应 starter 实现 `DispatcherProvider` 并按同样方式注册；`DispatcherFlow` 会按 `@Subscribe(type = ...)` 匹配 SPI 名分发。

---

## 7. 构建与依赖

```text
utils-support-rabbitmq-starter（com.chua:utils-support-rabbitmq-starter:4.0.0.51）
├── com.chua:utils-support-common-starter        （SPI 契约、DispatcherFlow、Wraps 等）
└── com.rabbitmq:amqp-client:5.37.0              （版本由 utils-support-middleware-parent 统一管理）
```

- 父模块：`com.chua:utils-support-middleware-parent:4.0.0.51` → `com.chua:utils-support-parent-starter:4.0.0.51`。
- 编译（在本模块目录执行）：

```bash
mvn -o -q compile -DskipTests
```

- GraalVM native-image 配置：`reflect-config.json` 注册 `RabbitmqDispatcherProvider` 的全部构造器 / 方法 / 字段；`resource-config.json` 打包 `META-INF/extensions/.*`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| Kafka / NATS / MQTT / Redis / Chronicle 等其它中间件接入 | 同级 `utils-support-kafka-starter`、`utils-support-nats-starter`、`utils-support-mqtt-starter`、`utils-support-redis-starter`、`utils-support-chronicle-starter` |
| `DispatcherProvider` 契约、`DispatcherFlow` 门面、`@Subscribe` 注解、内存分发实现 | `utils-support-common-starter` |
| AMQP 协议栈本身（帧、信道、握手） | `com.rabbitmq:amqp-client` |
| 消费重试策略、幂等、死信处理流程 | 源码未提供（本模块仅支持声明死信队列参数） |
| Spring Boot 自动装配 / 配置绑定 | 源码未提供 |
| RabbitMQ 管理控制台、运维监控 | 源码未提供 |
