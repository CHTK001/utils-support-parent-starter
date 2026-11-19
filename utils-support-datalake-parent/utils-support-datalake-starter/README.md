# utils-support-datalake-starter

> 数据湖服务端装配模块：把管线引擎、Sink 注册表、订阅管理、Chronicle 数据分发与可选的 DataSync 执行器装配成一个可启动/停止的 `DatalakeServer`，是 `utils-support-datalake-parent` 体系的核心组装点。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.datalake.support`（`server` / `manager` / `executor` 三个子包，共 6 个源文件）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datalake-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 装配 | `DatalakeServerBuilder` 链式构建 `DatalakeServer`，统一拉起 Sink、订阅器、分发器与可选 API 服务 | 不实现任何具体 Sink / 订阅器 / 查询引擎 |
| 执行 | `DatalakeReactorExecutor` 承接 DataSync 调度器的数据，包装成 `DataEnvelope` 交给 `PipelineEngine` | 不定义管线 DSL，不做 Filter/Parser/Cleaner/Standardizer 的具体实现 |
| 集成 | 注入 `DatalakeExecutorManager` 到 `DefaultDataSyncServer`，与 DataSync 共享 `DispatcherProvider` | 不启动 DataSync 服务本身，不创建 HTTP 路由（API 端口必须由宿主注入 `Server`） |
| 依赖 | 只做编排与生命周期管理（`start()` / `stop()` 幂等） | 不是 Spring 自动装配模块（无 `spring.factories` / `AutoConfiguration.imports`），按纯 Java 库使用 |

### 1.3 源文件一览

| 类 | 职责 |
| --- | --- |
| `server.DatalakeServerBuilder` | 链式构建器：管线管理器、Sink 注册、DataSync 接管、Chronicle 目录、API 服务端注入 |
| `server.DatalakeServer` | 数据中台控制中心：`start()` / `stop()` 统一编排生命周期 |
| `manager.SinkManager` | 启停全部已注册 Sink（单个失败不影响其余），查询存储型 Sink |
| `manager.SubscriberManager` | `SubscriberRegistry` 面向应用的门面：登记 / 注销 / 查询 / 主动推送 |
| `executor.DatalakeExecutorManager` | 实现 DataSync 的 `ExecutorManager`，持有唯一的 `DatalakeReactorExecutor`（agentId=`datalake`，服务端模式） |
| `executor.DatalakeReactorExecutor` | 继承 `ReactorDataSyncExecutor`：订阅/发布时把每行 `Map` 包装成 `DataEnvelope` 交给管线引擎或降级直派 Sink |

构建产物内置 GraalVM `native-image` 的 `reflect-config.json`，对上述 6 个类开放全量反射。

---

## 2. 核心 API 与速查表

### 2.1 构建与生命周期

```java
DatalakeServer server = DatalakeServerBuilder.builder()
        .pipelineManager(manager)        // 缺省 new DefaultPipelineManager()
        .registerSink(sink)              // 按 sink.type() 注册；也可 sinks(map) 批量注册
        .dataSyncServer(dataSyncServer)  // 可选：接管 DefaultDataSyncServer 的执行器
        .dataPath("datalake-dispatch")   // Chronicle 共享目录，缺省即此值
        .apiServer(server)               // 可选：宿主注入 API 服务端；不注入则不开 HTTP 端口
        .build();

server.start();   // SinkManager → SubscriberManager → apiServer（幂等，重复调用忽略）
server.stop();    // 反向释放：apiServer → 订阅器（清空注册表）→ Sink → dispatcher → offsetFlow
server.getPipelineManager();  // 拿回管线配置管理器
server.getPipelineEngine();   // 拿回管线执行引擎
```

### 2.2 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 构建数据湖服务端 | `DatalakeServerBuilder.builder()...build()` | 返回 `DatalakeServer` |
| 注册数据下沉通道 | `builder.registerSink(DataSink)` / `builder.sinks(Map)` | 按 `DataSink.type()` 建立类型→实现映射 |
| 接管 DataSync 执行器 | `builder.dataSyncServer(DataSyncServer)` | 仅当实例为 `DefaultDataSyncServer` 时注入 `DatalakeExecutorManager` 并共享分发器 |
| 指定跨进程共享目录 | `builder.dataPath(String)` | Chronicle 目录，缺省 `"datalake-dispatch"` |
| 开设 HTTP 管理面 | `builder.apiServer(Server)` | 不注入则不监听任何端口 |
| 登记 / 注销订阅器 | `new SubscriberManager().register(subscriber)` / `unregister(id)` | 绑定进程级 `SubscriberRegistry` 单例 |
| 主动推送一条数据信封 | `subscriberManager.push(DataEnvelope)` | 返回成功收下的订阅器数量 |
| 查询存储型 Sink | `new SinkManager(sinkMap).storeSinks()` | 非 `AccessSink` 且 `getDataSource() != null` 的 Sink |
| 订阅 / 发布跨进程数据 | `DatalakeReactorExecutor#subscribe` / `#publish` | 每行数据自动包 `DataEnvelope` 并补时间戳 |
| 计算跨进程 topic | `DatalakeExecutorManager#getTopic(sinkId)` / `DatalakeReactorExecutor#topicOf(sinkId)` | 服务端 `server-{agentId}`；客户端 `consumer-{agentId}-{sinkId}` |
| 拿管线管理器 / 引擎 | `server.getPipelineManager()` / `server.getPipelineEngine()` | 进一步做管线配置或执行 |

---

## 3. 快速开始

最短可运行示例：注册一个自定义 Sink，构建并启动数据湖服务端，再登记一个订阅器接收实时投递。

```java
import com.chua.datalake.support.manager.SubscriberManager;
import com.chua.datalake.support.server.DatalakeServer;
import com.chua.datalake.support.server.DatalakeServerBuilder;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.DataSink;
import com.chua.datalake.support.subscriber.Subscriber;
import com.chua.datalake.support.subscriber.PushPayload;

import java.util.Map;

public class DatalakeQuickStart {

    public static void main(String[] args) {
        // 1. 构建并启动数据湖服务端
        DatalakeServer server = DatalakeServerBuilder.builder()
                .dataPath("datalake-dispatch")     // Chronicle 共享目录，缺省即此值
                .registerSink(new ConsoleSink())   // 注册自定义 Sink
                .build();
        server.start();

        // 2. 登记订阅器（绑定进程级 SubscriberRegistry，才会被实时投递链路命中）
        SubscriberManager subscribers = new SubscriberManager();
        subscribers.register(new Subscriber() {
            @Override
            public String subscriberId() {
                return "demo-subscriber";
            }

            @Override
            public void onPush(PushPayload payload) {
                System.out.println("收到推送: " + payload);
            }
        });

        // 3. 停机（反向释放资源）
        server.stop();
    }

    /** 最小 DataSink 实现：打印每条数据 */
    static class ConsoleSink implements DataSink {
        @Override public String type() { return "console"; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public boolean write(DataEnvelope envelope, Map<String, Object> config) {
            System.out.println("sink 收到: " + envelope.getParsed());
            return true;
        }
        @Override public com.chua.common.support.lang.datasource.engine.EngineDataSource<?> getDataSource() {
            return null;   // 非存储型 Sink 返回空
        }
    }
}
```

说明：

- 不调用 `dataSyncServer(...)` 时，本模块独立运行：`build()` 会创建默认 `DefaultPipelineManager`、`ChronicleDispatcherProvider`（基于 `dataPath`）并启动 `OffsetFlow`。
- 通过 SPI（`ServiceProvider.of(DataSink.class)`）声明的 Sink 会在构建期自动装载；显式 `registerSink` 的同类型 Sink 优先（`putIfAbsent`）。
- 只在本地 `new` 出来的 `Subscriber` 若不登记到 `SubscriberManager` / `SubscriberRegistry`，收不到任何数据。

---

## 4. 配置项

本模块**没有**基于配置文件 / Spring 属性的配置项（模块内无 `application.yml` 键、无 `spring.factories` / `AutoConfiguration.imports`），全部配置通过 `DatalakeServerBuilder` 以代码方式给出：

| 构建器方法 | 缺省行为 | 说明 |
| --- | --- | --- |
| `pipelineManager(PipelineManager)` | `new DefaultPipelineManager()` | 管线配置管理器 |
| `dataPath(String)` | `"datalake-dispatch"` | Chronicle 共享目录，用于构造 `DispatcherConfig` 与 `ChronicleDispatcherProvider` |
| `offsetFlow`（未开放 setter） | `OffsetFlow.create().start()` | 位点门面，构建时自动创建并启动 |
| `apiServer(Server)` | `null`（不开 API 端口） | 本库不登记任何路由；自建监听只会暴露全 404 端口，故必须由宿主注入 |
| `dataSyncServer(DataSyncServer)` | 不接管 | 仅 `DefaultDataSyncServer` 实例会被注入 `DatalakeExecutorManager` |
| `registerSink` / `sinks` | 追加 SPI 自动装载的 Sink | 同类型显式注册优先 |

---

## 5. SPI 扩展点

### 5.1 DataSink — 数据下沉（本模块自动装载）

- 接口：`com.chua.datalake.support.spi.sink.DataSink`（位于 `utils-support-datalake-sink-starter`）
- 方法：`type()`、`start()`、`stop()`、`write(DataEnvelope, Map<String,Object>)`、`getDataSource()`
- 装载时机：`DatalakeServerBuilder.build()` 内部先经 `ServiceProvider.of(DataSink.class)` 装载全部 SPI Sink，**再**装配引擎与执行器——构建期内即可能被执行的管线要看到全量 Sink；未解析到的类型不会进编译缓存，晚注册的 Sink 下次执行才生效。
- 标记接口：`AccessSink`（访问型 Sink，如实时/日志/统计类，`getDataSource()` 返回空）；`SinkManager.storeSinks()` 以「非 `AccessSink` 且有数据源」筛选存储型 Sink。

### 5.2 Subscriber — 实时订阅

- 接口：`com.chua.datalake.support.subscriber.Subscriber`（位于 `utils-support-datalake-subscribe-starter`）
- 方法：`subscriberId()`、`topics()`（缺省空集=全量接收）、`onPush(PushPayload)`
- 登记：必须经 `SubscriberManager.register(...)`（或直接操作进程级单例 `SubscriberRegistry.getInstance()`）才会被投递链路命中；单个订阅器抛错只影响自身（位点不推进、可重投）。

### 5.3 PipelineEngine / PipelineManager — 管线契约

- `com.chua.datalake.support.spi.pipeline.PipelineEngine`：`execute(String pipelineId, DataEnvelope envelope)`，按 Filter → Parser → Cleaner → Standardizer → Sink 顺序执行。
- `com.chua.datalake.support.spi.pipeline.PipelineManager`：`savePipeline` / `getPipeline` / `deletePipeline` / `pipelineIds`，负责管线 JSON-DSL 的持久化与查询。
- 本模块默认装配 `DefaultPipelineEngine` 与 `DefaultPipelineManager`（来自 `utils-support-datalake-query-starter` 等兄弟模块），可经构建器整体替换。

---

## 6. 构建与依赖

```bash
mvn -o -q compile -DskipTests
```

父 POM：`com.chua:utils-support-datalake-parent:4.0.0.51`（上级 `com.chua:utils-support-parent-starter:4.0.0.51`）。

| 依赖 | 作用域 | 用途 |
| --- | --- | --- |
| `utils-support-common-starter` | compile | 契约层：`ServiceProvider`、`DispatcherProvider`、`OffsetFlow`、`Server` 等公共 SPI |
| `utils-support-datalake-sink-starter` | compile | Datalake SPI：`DataSink` / `AccessSink` / `PipelineEngine` / `PipelineManager` / `DataEnvelope` |
| `utils-support-datalake-subscribe-starter` | compile | 订阅：`Subscriber` / `SubscriberRegistry` / `PushPayload` |
| `utils-support-datalake-query-starter` | compile | 管线执行：`DefaultPipelineEngine` / `DefaultPipelineManager` |
| `utils-support-datasync-starter` | compile | `DataSyncServer` / `DefaultDataSyncServer` / `ExecutorManager` 契约 |
| `utils-support-datasync-agent-starter` | compile | `ReactorDataSyncExecutor` 等执行器基类 |
| `utils-support-chronicle-starter` | compile | `ChronicleDispatcherProvider` 跨进程分发 |
| `utils-support-calcite-starter` | provided | Calcite SQL 查询引擎（可选） |

同父模块下的兄弟模块：`utils-support-datalake-sink-starter`（SPI 与模型）、`utils-support-datalake-subscribe-starter`（订阅器）、`utils-support-datalake-query-starter`（查询/管线）、`utils-support-datalake-mqtt-starter`、`utils-support-datalake-webhook-starter`、`utils-support-datalake-cdc-starter`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 具体 Sink 实现（JDBC / Redis / 日志 / 统计等） | `utils-support-datalake-sink-starter` 及各中间件 `*-starter` |
| 具体订阅通道（MQTT / Webhook / CDC） | `utils-support-datalake-mqtt-starter` / `-webhook-starter` / `-cdc-starter` |
| 管线 DSL 解析与默认执行实现 | `utils-support-datalake-query-starter` |
| Calcite SQL 查询引擎 | `utils-support-calcite-starter`（本模块仅 provided 引用） |
| DataSync 服务端与采集调度本身 | `utils-support-datasync-starter` / `-agent-starter`（本模块只注入执行器管理器） |
| HTTP API 路由与端口监听 | 宿主应用；必须经 `builder.apiServer(Server)` 注入，本模块不登记任何路由 |
| Spring 自动装配 | 无；本模块是纯 Java 装配库，按 `DatalakeServerBuilder` 显式构建 |
