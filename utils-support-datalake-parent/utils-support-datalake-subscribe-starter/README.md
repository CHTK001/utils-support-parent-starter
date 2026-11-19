# utils-support-datalake-subscribe-starter

> 数据湖家族的**订阅端模块**：定义订阅器（`Subscriber`）模型与进程级注册表，把管线 Sink 投递出的实时数据按主题分发给业务消费方，并在消费成功后推进消费位点（至少一次语义）。
>
> 版本：`4.0.0.51`（继承父级 `utils-support-datalake-parent`）｜JDK：`25`（`maven.compiler.release=25`，继承仓库父级）｜包名：`com.chua.datalake.support.subscriber`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 数据投递链路与语义](#4-数据投递链路与语义)
- [5. 位点管理](#5-位点管理)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datalake-subscribe-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 职责 | 数据湖实时数据的**订阅端**：订阅器模型、进程级注册表、主题过滤、消费位点管理 | 不是采集端（Collector）、不是管线引擎、也不是 Sink 存储实现 |
| 投递 | 实现 sink-starter 的 `RealTimeChannel` SPI（通道名 `subscriber`），把 `DataEnvelope` 按主题分发给已登记订阅器 | 不生产数据、不缓存数据、不直接对接 MQ/WebSocket 等具体传输 |
| 依赖 | 编译期只依赖 `utils-support-common-starter`；`utils-support-datalake-sink-starter` 为 `provided` | 不依赖 Spring、不依赖数据库驱动、不依赖具体中间件客户端 |

**判断口径**：业务侧要消费数据湖实时数据 → 在业务代码里创建订阅器并 `subscribe()` 登记；数据如何采集、清洗、落库，均不在本模块。

### 1.3 规模

- Java 源文件 **6** 个，全部位于单一包 `com.chua.datalake.support.subscriber`，全部 `@since 4.0.0.42`。
- SPI 登记文件 **1** 个：`META-INF/extensions/com.chua.datalake.support.spi.realtime.RealTimeChannel`。
- 内置 GraalVM `native-image` 的 `reflect-config.json`，登记 4 个类型的全量反射元数据。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 写一个订阅器 | `new RealTimeDatalakeSubscriber(id, offsetFlow, consumer)` | 传入 `Consumer<DataEnvelope>`，每条数据回调一次 |
| 让投递命中自己 | `subscriber.subscribe()` | 内部登记到 `SubscriberRegistry`；只 `new` 不登记收不到任何数据 |
| 只收部分主题 | 构造第 4 参传 `Set<String> topics` | 空集合或不传 = 全量接收 |
| 自定义订阅器 | 实现 `Subscriber` 或继承 `AbstractDatalakeSubscriber` | 自行决定投递与位点推进时机 |
| 查询/推进位点 | `currentOffset()` / `advance()` | 位点存于 `OffsetFlow`，可持久化 |
| 回溯/清理位点 | `reset(newValue)` / `remove()` | 重放历史数据、退订清理 |
| 反应式消费 | `push(DataEnvelope)` / `pushBatch(Flux<DataEnvelope>)` | 返回 `Mono<Void>`，消费成功才推进位点 |
| 退订 | `SubscriberRegistry.getInstance().unregister(id)` | 返回被注销的订阅器；未登记返回 `null` |

---

## 2. 核心 API 与速查表

### 2.1 Subscriber —— 订阅器契约（接口）

| 成员 | 说明 |
| --- | --- |
| `String subscriberId()` | 订阅器唯一标识 |
| `default Set<String> topics()` | 订阅主题集合，用于投递前的路由过滤；空集合表示接收全部主题 |
| `void onPush(PushPayload payload)` | 推送一条数据；实现方消费失败应抛异常，位点不得推进，该条数据可重投 |

### 2.2 AbstractDatalakeSubscriber —— 带位点管理的抽象基类

| 构造器 | 说明 |
| --- | --- |
| `(String subscriberId, OffsetFlow offsetFlow)` | 不限主题 |
| `(String subscriberId, OffsetFlow offsetFlow, Set<String> topics)` | 指定主题；null/空归一为空集合（全量接收），否则包装为不可变有序集合 |

| 方法 | 说明 |
| --- | --- |
| `String subscriberId()` / `Set<String> topics()` | 契约实现，直接返回构造入参 |
| `long currentOffset()` | 当前已推送的最大偏移量（内部调 `offsetFlow.current(subscriberId)`） |
| `long advance()` | 推进偏移量并返回新值（内部调 `offsetFlow.advance(subscriberId)`） |
| `void reset(long newValue)` | 重置偏移量 |
| `void remove()` | 删除该订阅器的偏移量 |
| `abstract void subscribe()` | 订阅器实际订阅/推送实现，由子类落地 |

### 2.3 RealTimeDatalakeSubscriber —— 实时订阅器（最常用入口）

在抽象基类之上绑定一个 `Consumer<DataEnvelope>` 业务消费函数。两条投递入口语义同源：响应式侧走 `push(DataEnvelope)`，由 `RealTimeSink` 经 `SubscriberChannel` 触发的同步侧走 `onPush(PushPayload)`。

| 方法 | 说明 |
| --- | --- |
| `subscribe()` | 把自己登记到 `SubscriberRegistry`，并输出 subscriberId/topics/当前位点日志 |
| `void onPush(PushPayload payload)` | 同步入口：载荷或内含信封为空直接忽略，否则消费信封 |
| `Mono<Void> push(DataEnvelope envelope)` | 响应式入口：`Mono.fromRunnable(...)` + `Schedulers.boundedElastic()`；消费成功才推进位点 |
| `Mono<Void> pushBatch(Flux<DataEnvelope> envelopes)` | 批量推送：`concatMap` **串行**消费后 `then()`。位点为共享自增序号，并发消费会让快的那条先把位点推过去，慢的那条失败时已"看起来被消费过"，至少一次语义随即失效 |

消费语义：`consumer.accept(envelope)` 成功返回后 `advance()`；消费方抛错时位点保持原位，本条数据重启后重投。

### 2.4 SubscriberRegistry —— 进程级订阅器注册表（投递落点）

进程级单例（`getInstance()`）。Sink 侧由 SPI 实例化、拿不到应用持有的管理器实例，因此注册表是投递的唯一事实源，`SubscriberManager`（位于 datalake-starter）只是它面向应用的门面。

| 方法 | 说明 |
| --- | --- |
| `static SubscriberRegistry getInstance()` | 进程级实例 |
| `SubscriberRegistry register(Subscriber subscriber)` | 登记，同标识直接覆盖；订阅器或 subscriberId 为空抛 `IllegalArgumentException`；返回 this 支持链式 |
| `Subscriber unregister(String subscriberId)` | 注销，返回被注销的订阅器；未登记返回 `null` |
| `boolean contains(String subscriberId)` | 是否已登记 |
| `Collection<Subscriber> subscribers()` | 当前已登记订阅器快照（`List.copyOf`） |
| `int size()` | 已登记订阅器数量 |
| `void clear()` | 清空注册表，用于测试与停机 |
| `int dispatch(DataEnvelope envelope)` | 按主题过滤后逐个调用 `onPush(PushPayload.of(...))`，返回成功收下的订阅器数量；信封为 `null` 返回 0 |

主题匹配规则（内部 `interests` 判定）：

| 订阅器 topics | 信封 topics | 是否投递 |
| --- | --- | --- |
| 空 / null | 任意 | 是（全量接收） |
| 非空 | 空 / null | 否（无主题信封只能被全量接收者命中） |
| 非空 | 非空 | 有交集才投递 |

单个订阅器抛错只影响它自己（位点不推进、可重投），不会波及其他订阅器。

### 2.5 SubscriberChannel —— Sink 到订阅端的投递通道（SPI 名 `subscriber`）

`RealTimeChannel` 的默认实现，标注 `@Spi("subscriber")`，经 `META-INF/extensions/com.chua.datalake.support.spi.realtime.RealTimeChannel` 由 SPI 加载，因此 **Sink 侧无需依赖订阅模块**。

| 方法 | 说明 |
| --- | --- |
| `String name()` | 返回 `"subscriber"` |
| `boolean deliver(DataEnvelope envelope)` | 委托 `SubscriberRegistry.dispatch(envelope)`；信封为空或没有订阅器成功收下时返回 `false`，让上层 Sink 明确感知"数据未被消费" |

### 2.6 PushPayload —— 推送载荷

由 `DataEnvelope` 派生的载荷（Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`）：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `String subscriberId` | 投递目标 | 接收本条数据的订阅器标识 |
| `long offset` | `envelope.getTimestamp()` | 信封受理时间戳（毫秒），全序单调递增，用作偏移量 |
| `Map<String, Object> data` | `envelope.getParsed()` | 管线当前阶段的业务数据 |
| `DataEnvelope envelope` | 原始信封 | 便于订阅方读取 `traceId`/`pipelineId`/`state` 等元数据 |

静态工厂 `PushPayload.of(String subscriberId, DataEnvelope envelope)` 按上表完成派生。

---

## 3. 快速开始

依赖坐标见 [1.1](#11-maven-坐标)。最短可运行示例（依赖本模块 + sink-starter + common-starter）：

```java
import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.subscriber.RealTimeDatalakeSubscriber;
import com.chua.datalake.support.subscriber.SubscriberRegistry;

import java.util.Map;
import java.util.Set;

public class SubscribeDemo {

    public static void main(String[] args) {
        // 1. 位点流：负责每个订阅器 offset 的存储与推进（默认文件存储）
        OffsetFlow offsetFlow = OffsetFlow.create()
                .basePath("./data/offsets")   // 可选：指定持久化目录
                .start();

        // 2. 创建订阅器：只订阅 "order" 主题；不传 topics 则全量接收
        RealTimeDatalakeSubscriber subscriber = new RealTimeDatalakeSubscriber(
                "demo-subscriber",
                offsetFlow,
                envelope -> System.out.println("收到数据: " + envelope.getParsed()),
                Set.of("order"));

        // 3. 登记到注册表：此后 RealTimeSink 投递的数据才会命中本订阅器
        subscriber.subscribe();
        System.out.println("当前位点: " + subscriber.currentOffset());

        // 4. 模拟一次投递（真实场景由 datalake-starter 的 Sink 投递链路触发）
        DataEnvelope envelope = DataEnvelope.builder()
                .pipelineId("order-pipeline")
                .timestamp(System.currentTimeMillis())
                .topics(Set.of("order"))
                .parsed(Map.of("orderId", "1001", "amount", 99.9))
                .build();
        int delivered = SubscriberRegistry.getInstance().dispatch(envelope);
        System.out.println("本次投递命中订阅器数量: " + delivered);

        // 5. 退订与资源清理
        SubscriberRegistry.getInstance().unregister("demo-subscriber");
        offsetFlow.close();
    }
}
```

运行后：`subscribe()` 会输出 `[datalake-subscribe] 订阅器已订阅: subscriberId=demo-subscriber, topics=[order], offset=0`（具体日志格式取决于日志框架配置）；随后消费函数打印数据内容，`dispatch` 返回 1。

反应式入口等价写法：

```java
// 单条：消费成功后自动推进位点
subscriber.push(envelope).subscribe();

// 批量：内部 concatMap 串行消费，保证位点语义
subscriber.pushBatch(Flux.fromIterable(envelopes)).subscribe();
```

---

## 4. 数据投递链路与语义

```text
业务侧                               本模块                              sink-starter
──────                               ────────                            ────────────
new RealTimeDatalakeSubscriber
        │ subscribe()
        ▼
SubscriberRegistry.register ◄── SubscriberChannel.deliver(envelope) ◄── RealTimeSink
        │ dispatch(envelope)               （SPI 名 subscriber）
        ├─ 主题过滤（interests 判定）
        ├─ onPush(PushPayload.of(id, envelope))
        │      └─ consumer.accept(envelope) → advance()
        └─ 单个订阅器异常仅自己位点不推进，可重投
```

- **至少一次**：消费成功才推进位点；失败位点原位，重启后重投。
- **两条入口同源**：响应式 `push(DataEnvelope)` 与同步 `onPush(PushPayload)` 最终都走同一个 `consume(envelope)`（消费 + 推进）。
- **投递隔离**：`dispatch` 逐个订阅器调用并捕获单点异常，互不影响。
- **无人认领**：`deliver` 返回 `false`，上层 Sink 可感知"数据未被消费"。

---

## 5. 位点管理

位点能力来自 common-starter 的 `OffsetFlow`（`com.chua.common.support.concurrent.offset`，`@since 4.0.0.43`，`AutoCloseable`），本模块只做持有与调用：

```java
OffsetFlow offsetFlow = OffsetFlow.create()
        .persistent(true)            // 是否持久化
        .basePath("./data/offsets")  // 文件存储根目录
        .provider("file")            // OffsetStore SPI 名称；找不到时回退内置 FileOffsetStore
        .start();

offsetFlow.current("demo-subscriber");   // 查询当前位点
offsetFlow.advance("demo-subscriber");   // 原子 +1（内部失败重试 3 次，间隔 100ms）
offsetFlow.reset("demo-subscriber", 0);  // 重置
offsetFlow.remove("demo-subscriber");    // 删除单个订阅器位点
offsetFlow.truncate();                   // 清空全部位点
offsetFlow.close();                      // 释放存储
```

也可直接用订阅器门面：`currentOffset()` / `advance()` / `reset(newValue)` / `remove()`。

---

## 6. 配置项

**无。** 依据：本模块源码（6 个 Java 文件与 2 个资源文件）不读取任何配置文件、环境变量或系统属性——没有 `application.yml`、没有 `@ConfigurationProperties`、没有 `System.getProperty` 调用。订阅器行为完全由构造参数（`subscriberId`、`topics`、消费函数）与传入的 `OffsetFlow` 决定；位点存储的目录、持久化开关、provider 等选项属于 `utils-support-common-starter` 的 `OffsetConfig` 体系，不在此重复。

---

## 7. SPI 扩展点

| 方向 | 扩展点 | 说明 |
| --- | --- | --- |
| 业务实现 → 本模块 | `Subscriber` | 自定义订阅器契约：`subscriberId()` + `topics()` + `onPush(PushPayload)` |
| 业务实现 → 本模块 | `AbstractDatalakeSubscriber` | 已带 `OffsetFlow` 位点管理的抽象基类，子类实现 `subscribe()` 与 `onPush()` |
| 本模块 → sink-starter | `RealTimeChannel`（实现类 `SubscriberChannel`，SPI 名 `subscriber`） | 登记于 `META-INF/extensions/com.chua.datalake.support.spi.realtime.RealTimeChannel`，Sink 侧经 SPI 发现，无需依赖订阅模块 |

相关可替换 SPI：位点存储可经 common-starter 的 `OffsetStore` SPI 替换（通过 `OffsetFlow.provider("...")` 指定，内置 `FileOffsetStore` 兜底）。

自定义订阅器示例：

```java
public class AuditSubscriber extends AbstractDatalakeSubscriber {

    public AuditSubscriber(OffsetFlow offsetFlow) {
        super("audit-subscriber", offsetFlow, Set.of("audit"));
    }

    @Override
    public void subscribe() {
        SubscriberRegistry.getInstance().register(this);
    }

    @Override
    public void onPush(PushPayload payload) {
        // 业务处理；失败应抛异常 → 位点不推进，数据可重投
        audit(payload.getData());
        advance();
    }
}
```

GraalVM：`META-INF/native-image/com.chua/utils-support-datalake-subscribe-starter/reflect-config.json` 登记了 `Subscriber`、`AbstractDatalakeSubscriber`、`RealTimeDatalakeSubscriber`、`PushPayload` 四个类型的全量构造器/方法/字段反射元数据。

---

## 8. 构建与依赖

`pom.xml` 依赖清单（父级：`com.chua:utils-support-datalake-parent:4.0.0.51`）：

| 依赖 | 范围 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `OffsetFlow` 位点门面、`Wraps` 集合、`@Spi` 注解、SPI 装载 |
| `com.chua:utils-support-datalake-sink-starter` | provided | `DataEnvelope`、`RealTimeChannel` 等契约 |
| `org.projectlombok:lombok` | provided | `@Data`/`@Builder`/`@Slf4j` |

> sink-starter 为 `provided`：运行期需由宿主（如 `utils-support-datalake-starter`）带入类路径；否则 `SubscriberChannel` 的 SPI 装载会因缺少 `RealTimeChannel` 而不可用，直接使用 `RealTimeDatalakeSubscriber` 也需要 `DataEnvelope` 在类路径上。

构建命令（仓库根目录或本模块目录）：

```bash
mvn -o -q compile -DskipTests
```

---

## 9. 边界：这些能力不在本模块

| 能力 | 所在模块 |
| --- | --- |
| `DataEnvelope` / `PipelineState` / `RealTimeChannel` / `RealTimeSink`（信封模型与访问型 Sink） | `utils-support-datalake-sink-starter` |
| `SubscriberManager`（面向应用的登记门面）、`DatalakeServer`、管线引擎、采集端（Collector） | `utils-support-datalake-starter` |
| `OffsetFlow` / `OffsetStore` / `OffsetConfig`（位点存储与持久化实现） | `utils-support-common-starter` |
| 查询引擎 | `utils-support-datalake-query-starter` |
| MQTT / Webhook / CDC 等具体接入 | `utils-support-datalake-mqtt-starter` / `utils-support-datalake-webhook-starter` / `utils-support-datalake-cdc-starter` 等兄弟模块 |
| HTTP 服务、数据库、对象存储等通用能力 | `utils-support-common-starter` 及各 `*-starter` |

本模块自身：只有订阅端 6 个类，不含网络服务、不含存储实现、不含任何业务规则。
