# utils-support-runtime-protocol

> `utils-support-runtime-*` 体系的**运行时协议抽象层**：把 APM / 链路追踪需要的「端点、协议、软件栈、传输记录、Span、Trace、依赖边」沉淀为一组共享值对象与枚举，并附带一个 SkyWalking `sw8` 头传播器。本模块只定义数据长什么样、怎么互相换算，不做字节码插桩、不做数据上报。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 pom `utils-support-runtime-parent` 设定 `maven.compiler.release=25`）｜包名：`com.chua.runtime.protocol`（单包，共 11 个源文件）

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 规模与构成](#13-规模与构成)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 类型总览](#22-类型总览)
  - [2.3 枚举常量](#23-枚举常量)
  - [2.4 关键方法](#24-关键方法)
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
    <artifactId>utils-support-runtime-protocol</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

pom 中的官方描述：*运行时协议抽象：Endpoint/Protocol/Software/EndpointKind/SpanKind/Direction/StatusCode/TransmissionRecord/Span/Trace 等值对象与枚举，供所有 APM Handler 共享。*

> 注意：描述里提到的 `SpanKind` / `Direction` 在当前源码中没有对应文件（源码未提供）；实际存在的类型以下文 2.2 为准。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 内容 | APM 传输链路与链路追踪的**共享值对象 + 枚举**：`Endpoint`、`EndpointKind`、`Protocol`、`Software`、`TransmissionRecord`、`Span`、`SpanEvent`、`Trace`、`StatusCode`、`DependencyEdge` | 不包含 APM Handler、Agent 入口或字节码插桩逻辑 |
| 行为 | 端口号→协议推断（`Protocol.inferByPort`）、端点去重标识（`Endpoint.nodeId`）、依赖边聚合统计（`DependencyEdge.record/avgDuration`）、SkyWalking `sw8` 头生成/解析/上下文恢复（`SkyWalkingPropagator`） | 不做链路数据存储、上报、查询或可视化 |
| 依赖 | 仅依赖同版本 `utils-support-runtime-plugin`、`utils-support-common-starter` 与 `lombok`（provided） | 不依赖 Spring、数据库驱动、具体中间件客户端 |

**判断口径**：所有 APM Handler（套接字层、应用层）都往这里放"协议数据模型"；需要采集、插桩、上报能力的，去 runtime 体系的其他模块（见第 7 节边界）。

### 1.3 规模与构成

- Java 源文件 **11** 个，全部位于单包 `com.chua.runtime.protocol`：
  - 值对象 6 个：`Endpoint`、`TransmissionRecord`、`Span`、`SpanEvent`、`Trace`、`DependencyEdge`（均为 Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`）；
  - 枚举 4 个：`Protocol`（47 个常量）、`Software`（81 个常量）、`EndpointKind`（5 个）、`StatusCode`（3 个）；
  - 工具类 1 个：`SkyWalkingPropagator`（`final`，静态方法，`@since 4.1.0`；其余类型 `@since 4.0.0.42`）。
- 资源文件仅 3 个 GraalVM native-image 配置：`reflect-config.json`、`proxy-config.json`（空数组）、`resource-config.json`（放行 `META-INF/services/.*` 与 `META-INF/extensions/.*`）。
- 无测试源码目录。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 根据端口号推断协议 | `Protocol.inferByPort(port)` | 如 `80/8080→HTTP`、`443→HTTPS`、`6379→REDIS`、`3306→MYSQL`；`port<=0` 或未知返回 `UNKNOWN` |
| 描述一次传输的源/目标节点 | `Endpoint.builder()` | 字段 `kind/protocol/software/host/port/path/ssl/instanceId/attributes`；`nodeId()` 用于依赖图节点去重，`displayLabel()` 用于展示 |
| 记录一次网络/进程内传输 | `TransmissionRecord.builder()` | 同时服务链路追踪（traceId/spanId/parentSpanId）与传输链路（source/target），含协议、软件栈、操作、状态、耗时、收发字节数 |
| 聚合依赖图的一条边 | `DependencyEdge.record(duration, isError, error)` | 累计 `callCount/totalDuration/errorCount`，`avgDuration()` 算平均耗时，`edgeId()` 生成稳定边标识 |
| 描述方法级调用 Span | `Span.builder()` + `addEvent(name)` / `recordException(type, msg)` | `recordException` 自动把 `status` 置为 `ERROR` 并追加 `exception` 事件 |
| 组织一整条调用链 | `Trace.builder()` | `traceId + root(Span) + spans(List<Span>) + 起止时间 + finished` |
| Span 生命周期事件 | `SpanEvent.builder()` | `eventName / timestamp / attributes`，如 `exception`、`log`、`checkpoint` |
| SkyWalking 跨进程串联 | `SkyWalkingPropagator.inject()` / `extract(header)` / `extractAndRestore(header)` | 生成/解析 `sw8` 头；`extractAndRestore` 反射恢复 `RuntimeSpy` 追踪栈，spy 不在场时返回 `false` |

### 2.2 类型总览

| 类型 | 种类 | 职责 | 关键字段 / 方法 |
| --- | --- | --- | --- |
| `Protocol` | 枚举 | 传输层/应用层协议；套接字层按端口推断，应用层 Handler 显式声明 | `displayName()`、`defaultPort()`、`isText()`、`inferByPort(int)` |
| `Software` | 枚举 | 区分同一协议下的具体软件栈实现（如 Redis 的 Jedis/Lettuce/Redisson） | `displayName()` |
| `EndpointKind` | 枚举 | 端点角色（OpenTelemetry 语义） | `SERVER`、`CLIENT`、`PRODUCER`、`CONSUMER`、`INTERNAL` |
| `StatusCode` | 枚举 | Span / 传输状态码 | `UNSET`、`OK`、`ERROR` |
| `Endpoint` | 值对象 | 一次传输链路中的节点（来源/目标） | `kind`、`protocol`、`software`、`host`、`port`、`path`、`ssl`、`instanceId`、`attributes`；`nodeId()`、`displayLabel()` |
| `TransmissionRecord` | 值对象 | 一次网络/进程内传输的完整记录 | `traceId/spanId/parentSpanId`、`source/target`、`protocol/software`、`operation`、`status/statusCode`、`startTime/endTime/duration`、`bytesOut/bytesIn`、`errorMessage/errorType` |
| `Span` | 值对象 | 一次方法调用的执行单元（对齐 Jaeger 模型） | `traceId/spanId/parentSpanId`、`name/className/methodName/descriptor`、`startTime/endTime/duration`、`status`、`message`、`errorType/errorMessage`、`events`；`addEvent(String)`、`recordException(String, String)` |
| `SpanEvent` | 值对象 | Span 生命周期内的标记点 | `eventName`、`timestamp`、`attributes` |
| `Trace` | 值对象 | 一个 traceId 对应的一整条调用链 | `traceId`、`root`、`spans`、`startTime`、`endTime`、`finished` |
| `DependencyEdge` | 值对象 | 依赖图的边（源→目标）及聚合统计 | `source/target`、`protocol/software`、`callCount/totalDuration/errorCount`、`lastError`、`lastCallTime`；`record(...)`、`avgDuration()`、`edgeId()` |
| `SkyWalkingPropagator` | 工具类 | SkyWalking 8.x `sw8` 头的生成、解析与上下文恢复 | `HEADER_SW8`、`inject()`、`extract(String)`、`extractAndRestore(String)`、`inject(Map)`、`extract(Map)`、`setServiceName/setInstanceName/setEndpointName`、内部类 `Sw8Context` |

### 2.3 枚举常量

**`Protocol`（47 个）**，按用途分组：

| 分组 | 常量 |
| --- | --- |
| 传输/通用 | `TCP`、`UDP`、`INTERNAL`（进程内）、`SQL`（通用数据库协议）、`MESSAGE`（跨协议消息通道）、`UNKNOWN` |
| Web / RPC | `HTTP`(80)、`HTTPS`(443)、`GRPC`、`DUBBO`(20880)、`THRIFT`(9090)、`RSOCKET`(7000)、`WEBSOCKET` |
| 数据库 | `MYSQL`(3306)、`POSTGRESQL`(5432)、`ORACLE`(1521)、`SQLSERVER`(1433)、`DB2`(50000)、`CLICKHOUSE`(8123)、`DAMENG`(达梦, 5236)、`KINGBASE`(金仓, 54321)、`CASSANDRA`(9042)、`HBASE`(16020)、`MONGODB`(27017)、`NEO4J`(7687)、`INFLUXDB`(8086)、`H2`(8082)、`SHARDING_SPHERE` |
| 缓存 | `REDIS`(6379)、`MEMCACHED`(11211)、`HAZELCAST`(5701)、`COUCHBASE`(8091)、`IGNITE`(10800) |
| 消息 | `KAFKA`(9092)、`ROCKETMQ`(9876)、`RABBITMQ`(5672)、`MQTT`(1883)、`JMS`(61616)、`PULSAR`(6650)、`NATS`(4222) |
| 注册/配置/搜索 | `ZOOKEEPER`(2181)、`NACOS`(8848)、`EUREKA`(8761)、`CONSUL`(8500)、`ELASTICSEARCH`(9200)、`SOLR`(8983)、`ETCD`(2379) |

括号内为 `defaultPort()`；`isText()` 为 `true` 的只有 `TCP`、`HTTP`、`HTTPS`、`GRPC`。

**`Software`（81 个）**：同一协议的不同实现，命名规律为「产品 + 角色」，例如 `JEDIS`/`LETTUCE`/`REDISSON`（Redis 客户端三件套）、`KAFKA_PRODUCER`/`KAFKA_CONSUMER`、`MYSQL_DRIVER`/`POSTGRESQL_DRIVER`/`ORACLE_DRIVER` 等 JDBC 驱动、`TOMCAT`/`JETTY`/`NETTY`/`UNDERTOW`（服务端容器）、`APACHE_HTTPCLIENT`/`OKHTTP`/`WEB_CLIENT`/`FEIGN`/`REST_TEMPLATE`（HTTP 客户端）、`ZOOKEEPER_NATIVE`/`CURATOR`、`SPRING_MVC`/`MYBATIS`/`HIBERNATE`/`SPRING_DATA_JPA`，直至 `KUBERNETES`、`AWS_SDK`、`THREAD`、`UNKNOWN`。完整清单见 `Software.java`。

**`EndpointKind`（5 个）**：`SERVER`（接收连接）、`CLIENT`（发起连接）、`PRODUCER`/`CONSUMER`（异步消息）、`INTERNAL`（进程内）。

**`StatusCode`（3 个）**：`UNSET`（未设置，`TransmissionRecord`/`Span` 的 builder 默认值）、`OK`、`ERROR`。

### 2.4 关键方法

| 方法 | 行为 |
| --- | --- |
| `Protocol.inferByPort(int)` | `switch` 端口映射；`port<=0` → `UNKNOWN`；未知端口 → `UNKNOWN`。完整映射表见源码（如 `2181/3181/4181→ZOOKEEPER`、`8848/9848→NACOS`、`15672→RABBITMQ`） |
| `Endpoint.nodeId()` | `protocol|software|host:port|path` 拼接，空值以 `?` 占位；同一进程内相同端点视为同一依赖图节点 |
| `Endpoint.displayLabel()` | `协议显示名/软件栈显示名 host:port path`；`software` 为 `UNKNOWN` 时不拼接，`path` 为空不拼接 |
| `DependencyEdge.record(duration, isError, error)` | `callCount++`、`totalDuration+=duration`、`lastCallTime=System.currentTimeMillis()`；出错时 `errorCount++` 并记录 `lastError`；返回 `this` 支持链式 |
| `DependencyEdge.avgDuration()` | `totalDuration / callCount`（`callCount==0` 时返回 `0`） |
| `DependencyEdge.edgeId()` | `source.nodeId() + " -> " + target.nodeId()` |
| `Span.addEvent(String)` | 追加一个以当前时间戳为 `timestamp` 的 `SpanEvent` |
| `Span.recordException(String, String)` | `status=ERROR`，写入 `errorType/errorMessage`，并 `addEvent("exception")` |
| `SkyWalkingPropagator.inject()` | 生成 `sw8` 头：traceId/spanId 优先取 `RuntimeSpy` 当前上下文（反射），取不到则随机生成（traceId 32 位 hex、spanId 16 位 hex） |
| `SkyWalkingPropagator.extract(String)` | 解析 `sw8` 头为 `Sw8Context(traceId, spanId)`；格式非法/为空返回 `null` |
| `SkyWalkingPropagator.extractAndRestore(String)` | `extract` 成功后反射调用 `RuntimeSpy.restore(...)` 恢复当前线程追踪栈；成功 `true`，任何异常 `false` |
| `SkyWalkingPropagator.inject(Map) / extract(Map)` | 面向 header 集合的封装：注入 `sw8` 键 / 大小写不敏感地查找 `sw8` 并恢复 |

`sw8` 头格式（与 SkyWalking 8.x 兼容，Base64 均为 URL-safe 无填充）：

```text
sw8: 1-b64(traceId)-b64(serviceName)-b64(instanceName)-b64(endpointName)-b64(空)-b64(endpointName)-1-b64(segmentId|spanId|1|serviceName)
```

---

## 3. 快速开始

以下示例只用本模块与 Lombok 生成的 builder，可直接编译运行（`SkyWalkingPropagator` 在没有 spy 模块时会自动降级为随机 traceId，不抛异常）：

```java
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.SkyWalkingPropagator;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;

public class ProtocolDemo {

    public static void main(String[] args) {
        // 1. 端口号推断协议（套接字层 Handler 的典型用法）
        Protocol inferred = Protocol.inferByPort(6379);          // REDIS

        // 2. 构造源 / 目标端点
        Endpoint source = Endpoint.builder()
                .kind(EndpointKind.CLIENT)
                .protocol(Protocol.HTTP)
                .software(Software.OKHTTP)
                .host("127.0.0.1").port(8080)
                .path("/api/order")
                .build();
        Endpoint target = Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(inferred)
                .software(Software.JEDIS)
                .host("redis.internal").port(6379)
                .build();
        System.out.println(source.nodeId());        // HTTP|OKHTTP|127.0.0.1:8080|/api/order
        System.out.println(target.displayLabel());  // Redis/Jedis redis.internal:6379

        // 3. 记录一次传输
        long start = System.currentTimeMillis();
        TransmissionRecord record = TransmissionRecord.builder()
                .traceId("trace-1").spanId("span-1").parentSpanId(null)
                .source(source).target(target)
                .protocol(inferred).software(Software.JEDIS)
                .operation("GET user:1")
                .status(StatusCode.OK)
                .startTime(start).endTime(start + 12).duration(12)
                .bytesOut(64).bytesIn(1024)
                .build();

        // 4. 聚合为依赖图的一条边（同一对端点的多次传输累计）
        DependencyEdge edge = DependencyEdge.builder()
                .source(source).target(target)
                .protocol(inferred).software(Software.JEDIS)
                .build();
        edge.record(record.getDuration(), false, null);
        System.out.println(edge.edgeId());        // source nodeId -> target nodeId
        System.out.println(edge.avgDuration());   // 12.0

        // 5. SkyWalking sw8 头：注入 → 解析
        String sw8 = SkyWalkingPropagator.inject();
        SkyWalkingPropagator.Sw8Context ctx = SkyWalkingPropagator.extract(sw8);
        System.out.println(ctx.getTraceId() + " / " + ctx.getSpanId());
    }
}
```

---

## 4. 配置项

**无。**

依据：

- `src/main/resources` 下只有 GraalVM native-image 的三个 JSON（`reflect-config.json`、`proxy-config.json`、`resource-config.json`），没有任何 `application*.yml` / `*.properties` 配置文件；
- 源码中没有 `@ConfigurationProperties`、环境变量读取或配置键。

唯一可编程的"设置点"是 `SkyWalkingPropagator` 的三个静态 setter（代码级调用，不是配置键）：

| Setter | 默认值 | 说明 |
| --- | --- | --- |
| `setServiceName(String)` | `hotsport-agent` | 写入 `sw8` 头的本服务名 |
| `setInstanceName(String)` | `pid@hostname`（进程 PID + 本机主机名） | 本实例名 |
| `setEndpointName(String)` | `/agent` | 本服务入口端点 |

三个 setter 对空入参不生效（保持原值）。

---

## 5. SPI 扩展点

**无。**

依据：

- `src/main/resources` 下没有 `META-INF/extensions/` 注册文件（`resource-config.json` 放行该目录只是给上层模块用的通配规则）；
- 源码中没有任何 `@Spi` / `@Extension` 注解，也没有可供实现的扩展接口——本模块是**被消费的契约/值对象库**，不是扩展点提供方。

一个**可选协作点**（非 SPI）：`SkyWalkingPropagator` 通过反射软依赖 `com.chua.runtime.spy.RuntimeSpy`（`getCurrentTraceId` / `getCurrentSpanId` / `restore`，均为反射调用）。spy 模块不在 classpath 时自动降级：`inject()` 使用随机 traceId/spanId，`extractAndRestore(...)` 返回 `false`，不会抛异常。

另注：`reflect-config.json` 中登记的 `com.chua.runtime.protocol.W3CTraceContext` 与 `com.chua.runtime.protocol.ProtocolHandler` 在当前源码中已无对应文件（历史残留条目，源码未提供这两个类型）。

---

## 6. 构建与依赖

父链：`utils-support-runtime-protocol` ← `utils-support-runtime-parent`（4.0.0.51）← `utils-support-parent-starter`（4.0.0.51）。

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-runtime-plugin` | `${project.version}`（4.0.0.51） | runtime 插件基础 |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | 基础工具：`StringUtils`、`ReflectUtils`、`Wraps` 等 |
| `org.projectlombok:lombok` | `${lombok.version}`，`provided` | 生成 `@Data` / `@Builder` 等 |
| `org.slf4j:slf4j-api` | 由父 pom 统一管理 | 日志门面（继承自 `utils-support-runtime-parent`） |

编译（仓库约定命令）：

```bash
cd utils-support-parent-starter/utils-support-runtime-parent/utils-support-runtime-protocol
mvn -o -q compile -DskipTests    # 期望 EXIT=0
```

或从 `utils-support-parent-starter` 根目录按模块构建：

```bash
mvn -o -q -pl utils-support-runtime-parent/utils-support-runtime-protocol -am compile -DskipTests
```

> 源码使用 Lombok 注解，IDE 需启用注解处理；JDK 按 `maven.compiler.release=25` 编译。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 / 状态 |
| --- | --- |
| APM Handler 的定义、注册、调度与执行 | 不在本模块。pom 描述称本模块"供所有 APM Handler 共享"，但 Handler 体系源码未提供于本模块（见 `utils-support-runtime-plugin` / `utils-support-runtime-core`） |
| 字节码插桩、`RuntimeSpy` 追踪上下文 | 在 `utils-support-runtime-spy`；本模块仅通过反射软引用，不直接依赖 |
| Agent 入口（premain/agentmain） | 在 `utils-support-runtime-agent` |
| runtime 主入口与依赖装配 | 在 `utils-support-runtime-starter` |
| W3C traceparent 传播器实现 | 源码未提供。`SkyWalkingPropagator` 的 javadoc 提到"与 W3C traceparent 双写"，但本模块没有对应实现类 |
| pom 描述中的 `SpanKind` / `Direction` | 当前源码无对应文件（源码未提供） |
| 链路数据的存储、查询、上报与可视化 | 不在本模块 |
| 各中间件（Redis/MySQL/Kafka 等）的具体探针实现 | 不在本模块；这里只有协议/软件栈枚举与数据模型，无任何客户端采集代码 |
