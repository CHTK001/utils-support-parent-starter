# utils-support-opentelemetry-starter

> OpenTelemetry **可观测性中间件集成模块**：封装 OpenTelemetry Java SDK 的生命周期，默认用内存导出器缓存 Span 并提供本地链路查询；同时以 SPI 方式接入 `Engine`（数据源引擎）与 `OtnlEngine`（APM 查询）两套体系，并提供 Spring Boot 自动装配。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.opentelemetry.support`（4 个包：`client` / `config` / `datasource` / `engine`，共 8 个 Java 源文件）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点与自动装配](#5-spi-扩展点与自动装配)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-opentelemetry-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块位置：`utils-support-parent-starter/utils-support-middleware-parent/utils-support-opentelemetry-starter`。
POM 描述：*「OpenTelemetry 可观测性中间件集成模块，提供 Engine 引擎、OpenTelemetryClient 与自动装配」*。全部公开类自 `4.0.0.50` 引入（各源码文件 `@since 4.0.0.50`）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| SDK 封装 | `OpenTelemetrySdk` / `SdkTracerProvider` 的生命周期封装；未指定导出器时用 `InMemorySpanExporter` 把 Span 缓存到进程内存 | 不是 APM 后端，也不含 OTLP/Collector 传输实现（`opentelemetry.url` 当前只是占位参数） |
| 查询模型 | 把 SDK `SpanData` 转成 `utils-support-runtime-protocol` 的 `Span` / `Trace` / `SpanEvent` / `StatusCode`，提供 `traces()` / `spans()` 等轻量查询入口 | 不提供指标时序、拓扑、告警、日志的存储与查询（OtnlEngine 侧明确返回空/不支持） |
| 接入方式 | Spring Boot 自动装配（`opentelemetry.enabled`，默认 `true`）；`OpenTelemetryClient.builder()` 裸用；`Engine.create("opentelemetry")` SPI 装配 | 不提供 Java Agent / 字节码增强自动埋点，Span 需通过 `startSpan()` / `tracer()` 自行创建 |
| 引擎能力 | `OpenTelemetryEngine` 实现只读数据源引擎；`OpenTelemetryOtnlEngine` 提供服务/实例/端点/链路四类 APM 查询 | 不支持 SQL 执行、元数据、Lambda 增删改与数据写入（相关方法一律抛 `UnsupportedOperationException` 或返回 null） |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `OpenTelemetryClient.builder()` | `serviceName` / `endpoint` / `sampler` / `spanExporter` / `spanLimits` / `resource` / `attributes` 链式配置 |
| 快速建 Span | `client.startSpan(name)`、`startSpan(name, SpanKind)` | 复杂场景用 `client.tracer()` 拿原生 `Tracer` |
| 查链路 | `client.traces()` / `client.trace(traceId)` | 内存 Span 按 traceId 聚合成 `Trace`（含根 Span、起止时间） |
| 查 Span | `client.spans()` / `client.spans(name)` / `client.spansByTraceId(traceId)` | 返回协议模型 `Span` 列表 |
| 采样策略 | `OpenTelemetrySampler.of(name, ratio)` | `always_on` / `always_off` / `parent_based` / `trace_id_ratio`，非法名直接抛异常 |
| 当数据源引擎 | `OpenTelemetryEngine`（`@Spi("opentelemetry")`） | 多数据源管理 + `traces()` / `spans()` 委托，只读 |
| APM 查询 | `OpenTelemetryOtnlEngine`（`@Spi("opentelemetry")`） | `services` / `instances` / `endpoints` / `traces` / `trace` / `health` |
| Spring Boot 接入 | `opentelemetry.*` 配置 + `OpenTelemetryAutoConfiguration` | 默认注册 `OpenTelemetryClient` 与 `OpenTelemetryEngine` 两个 Bean |

---

## 2. 核心 API 与速查表

### 2.1 OpenTelemetryClient —— SDK 生命周期 + 本地查询

`com.chua.opentelemetry.support.client.OpenTelemetryClient`，实现 `AutoCloseable`。

**构建**：`OpenTelemetryClient.builder()` 返回 `OpenTelemetryClientBuilder`：

| 构建方法 | 说明 |
| --- | --- |
| `serviceName(String)` | 服务名，同时作为内部 Tracer 的标识，默认 `unknown-service` |
| `endpoint(String)` | OTLP 接收机地址（当前版本仅记录，本地查询模式下不生效） |
| `sampler(Sampler)` | 采样器，默认 `Sampler.alwaysOn()` |
| `spanExporter(SpanExporter)` | 指定后进入「传输模式」（`SimpleSpanProcessor` 转发）；不指定则用 `InMemorySpanExporter`（「本地查询模式」） |
| `resource(Resource)` / `attributes(Attributes)` / `attribute(String, String)` | 资源与属性；设置过 attributes 时会合并进 Resource |
| `spanLimits(SpanLimits)` | Span 限制，默认 `SpanLimits.getDefault()` |
| `build()` | 构建客户端 |

**创建 Span**：`startSpan(String name)`、`startSpan(String name, SpanKind kind)`；复杂用法通过 `tracer()` 获取 `io.opentelemetry.api.trace.Tracer`。

**查询**（本地查询模式下从内存导出器读取，查询前会 `forceFlush`，最多等 5 秒）：

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `traces()` | `List<Trace>` | 全部 Trace，按 traceId 分组拼装，含根 Span 与起止时间 |
| `trace(String traceId)` | `Trace` | 指定 Trace，无则 null |
| `spans()` | `List<Span>` | 全部 Span |
| `spans(String name)` | `List<Span>` | 按 Span 名称过滤 |
| `spansByTraceId(String traceId)` | `List<Span>` | 指定 Trace 的 Span |
| `isClosed()` | `boolean` | 是否已关闭 |

**属性映射**：Span 属性 `class` / `method` / `descriptor` / `message` / `error.type` / `error.message` 分别写入协议模型的 `className` / `methodName` / `descriptor` / `message` / `errorType` / `errorMessage`；其余属性不参与协议模型。状态码 `OK` / `ERROR` / 其余分别映射为 `StatusCode.OK` / `StatusCode.ERROR` / `StatusCode.UNSET`。

**关闭语义**：`close()` 幂等（`AtomicBoolean` 保证），依次关闭 `SdkTracerProvider` 与内存导出器（各等 5 秒）。**关闭后** `startSpan(...)` / `tracer()` / `tracerProvider()` 抛 `IllegalStateException`；查询方法返回空列表，`trace(traceId)` 返回 null。

**访问器**：`getServiceName()` / `getEndpoint()` / `getOpenTelemetry()`。

### 2.2 OpenTelemetryEngine —— Engine SPI 引擎

`com.chua.opentelemetry.support.engine.OpenTelemetryEngine`，`@Spi("opentelemetry")`，实现 `com.chua.common.support.lang.datasource.engine.Engine`；也可直接 `new` 后手动装数据源（自动装配就是这么做的）。

| 方法 | 说明 |
| --- | --- |
| `addDataSource(String name, OpenTelemetryClient client)` | 登记客户端；同名替换时关闭旧数据源；首个登记的成为默认数据源 |
| `addDataSource(String name, String baseUrl)` | 传地址字符串，内部以该名称作为服务名创建客户端 |
| `addDataSource(String name, EngineDataSource<?> ds)` | 底层源仅接受 `OpenTelemetryClient` 或 URL 字符串，其它类型抛 `IllegalArgumentException` |
| `client()` / `client(String name)` | 取默认/指定数据源的客户端；未注册任何数据源、名字不存在或数据源已关闭时抛异常（`name` 为 null 返回 null） |
| `traces()` / `trace(traceId)` / `spans()` / `spans(name)` / `spansByTraceId(traceId)` | 委托给默认数据源的客户端 |
| `dataSourceNames()` | 已注册数据源名称快照 |
| `setDefaultDataSourceName(String)` / `getDefaultDataSourceName()` | 默认数据源 |
| `getDataSource()` / `getDataSource(String)` | 取数据源封装，未注册返回 null（由调用方判空） |
| `close()` / `isClosed()` | 关闭全部数据源（幂等，单个失败不影响其余）；关闭后禁止再变更或获取数据源 |

**只读边界**（实现 `Engine` 接口的其余方法）：`store(...)`、`execute(...)`、`query(Class)`、`update(Class)`、`delete(Class)`、`meta()` 一律抛 `UnsupportedOperationException`；`supportsMeta()` 恒为 `false`；`getExecutor(...)`（已 `@Deprecated`）与 `getDialect(...)` 恒为 null。

### 2.3 OpenTelemetryOtnlEngine —— APM 查询 SPI

`com.chua.opentelemetry.support.engine.OpenTelemetryOtnlEngine`，`@Spi("opentelemetry")`，实现 `com.chua.common.support.apm.OtnlEngine`。Span 数据来自 `OpenTelemetryClientHolder` 中登记的客户端；**一个客户端就是一个服务**。

| 方法 | 行为 |
| --- | --- |
| `name()` | 返回 `"opentelemetry"` |
| `capabilities()` | 服务、实例、端点、链路四项（`CAP_SERVICES` / `CAP_INSTANCES` / `CAP_ENDPOINTS` / `CAP_TRACES`） |
| `status()` | 未装配客户端（`opentelemetry.enabled=false` 或模块未引入）或已关闭 → `OtnlStatus.unavailable`；否则 `available`（SDK 版本、`in-memory`、`agent`） |
| `services(OtnlQuery)` | 单个 `OtnlService`：按时间窗口聚合 Span 的调用数、avg/p95/p99、错误率，标签 `source=opentelemetry-sdk` |
| `instances(serviceName, query)` | 单个 `OtnlInstance`：平台 `JAVA`，附 `host` / `pid` 标签 |
| `endpoints(serviceName, query)` | 按 Span 名聚合的 `OtnlEndpoint` 列表，支持关键字过滤；Span 名为空时用 `class#method` |
| `traces(OtnlQuery)` | 链路摘要列表：时间窗口 / 服务 / 端点 / 仅错误（`OtnlTraceState.ERROR`）过滤；按耗时或开始时间降序，分页 |
| `trace(String traceId)` | 完整链路：`OtnlSpan` 列表（根 Span 标记 `Entry`，其余 `Local`），附 `class` / `method` / `error.type` 标签；错误 Span 生成 `event=error` 的日志 |
| `health(OtnlQuery)` | 无 Span → `OtnlHealth.empty`；错误率 > 1% → `DEGRADED`，否则 `HEALTHY` |
| `topology(query)` / `metrics(query)` | SDK 侧无此数据，返回 `OtnlTopology.empty()` / `OtnlMetricSeries.empty()` |
| `alarms(query)` / `logs(query)` | 返回空列表 |
| `close()` | 空实现（客户端由 Spring 容器管理） |

时间窗口：`OtnlQuery` 未提供时间时，默认取「当前时间往前 30 分钟」。

### 2.4 支撑组件

| 类 | 职责 |
| --- | --- |
| `client.OpenTelemetryClientHolder` | 进程级客户端登记处（`register` / `get` / `unregister`）。SPI 引擎是无参构造，拿不到 Spring Bean；由自动装配在创建客户端后登记，容器关闭时注销 |
| `config.OpenTelemetrySampler` | `of(String name, double ratio)` 把策略字符串转 `Sampler`：`always_on` / `always_off` / `parent_based` / `trace_id_ratio`（ratio 限 0.0~1.0，越界抛 `IllegalArgumentException`）；空名回落 `alwaysOn`，未识别名在装配时立即抛异常 |
| `datasource.OpenTelemetryDataSource` | `EngineDataSource<OpenTelemetryClient>` 封装：`name()` / `getSource()` / `setSource(Object)`（仅接受 `OpenTelemetryClient`）/ `url()`；`getDialect()` 恒 null、`setDialect(非null)` 抛异常；`username()` / `password()` 恒 null（凭据封装在客户端内）；`close()` 幂等 |

---

## 3. 快速开始

### 3.1 Spring Boot 自动装配（推荐）

引入依赖：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-opentelemetry-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

配置：

```yaml
opentelemetry:
  enabled: true            # 默认 true，可省略
  service-name: demo-service
  sampler: always_on       # always_on / always_off / parent_based / trace_id_ratio
  # ratio: 1.0             # 仅 trace_id_ratio 需要
```

使用：

```java
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class, args);
        OpenTelemetryClient client = context.getBean(OpenTelemetryClient.class);

        // 1. 记录一个 Span
        io.opentelemetry.api.trace.Span span = client.startSpan("demo-request");
        span.end();

        // 2. 查询内存中的链路
        List<Trace> traces = client.traces();
        System.out.println("traces = " + traces.size());

        context.close();   // 触发 client/engine 的 destroyMethod="close" 与 Holder 注销
    }
}
```

自动装配行为（`OpenTelemetryAutoConfiguration`，经 `AutoConfiguration.imports` 注册）：

- `opentelemetry.enabled=false` 时整体关闭（`@ConditionalOnProperty`，缺省视为开启）。
- 注册 `OpenTelemetryClient` Bean（`@ConditionalOnMissingBean`，`destroyMethod="close"`）：服务名取 `opentelemetry.service-name`，采样器由 `OpenTelemetrySampler.of(sampler, ratio)` 生成；未配置导出器 → 内存导出器。
- 注册 `OpenTelemetryEngine` Bean（`destroyMethod="close"`），并把客户端以 `"default"` 名登记为数据源。
- 客户端创建后登记到 `OpenTelemetryClientHolder`（供 SPI 引擎读取）；容器关闭时 `unregister(null)` 注销。
- 初始化日志：`[OpenTelemetry] 客户端初始化: {服务名} (采样器 {}, 采样比 {})`。

### 3.2 纯 SDK（不依赖 Spring）

```java
import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.Trace;
import io.opentelemetry.api.trace.SpanKind;

try (OpenTelemetryClient client = OpenTelemetryClient.builder()
        .serviceName("demo-service")
        .build()) {                       // 未指定 spanExporter → InMemorySpanExporter

    client.startSpan("demo-request", SpanKind.INTERNAL).end();

    List<Span> spans = client.spans();
    List<Trace> traces = client.traces();
}                                         // close() 释放 SdkTracerProvider 与内存导出器
```

### 3.3 SPI / 引擎方式

```java
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import com.chua.opentelemetry.support.engine.OpenTelemetryEngine;
import com.chua.runtime.protocol.Trace;

// SPI 方式（META-INF/extensions 登记：opentelemetry=...OpenTelemetryEngine）
OpenTelemetryEngine engine = (OpenTelemetryEngine) Engine.create("opentelemetry");

// 或与自动装配等价的手动装配
OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("demo-service").build();
engine.addDataSource("default", client);

List<Trace> traces = engine.traces();
OpenTelemetryClient defaultClient = engine.client();

engine.close();
```

`OpenTelemetryOtnlEngine` 以同名 SPI（`opentelemetry`）注册在 `META-INF/extensions/com.chua.common.support.apm.OtnlEngine`，读取的是 `OpenTelemetryClientHolder` 里登记的同一个客户端；未装配客户端时 `status()` 明确报告不可用。

---

## 4. 配置项

前缀 `opentelemetry`（`com.chua.opentelemetry.support.config.OpenTelemetryProperties`，`@ConfigurationProperties`）：

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `opentelemetry.enabled` | `boolean` | `true` | 是否启用自动装配；`false` 时关闭，且 OtnlEngine 的 `status()` 报告不可用 |
| `opentelemetry.service-name` | `String` | `unknown-service` | 服务名，用于客户端内部 `InstrumentationScopeInfo` 的标识 |
| `opentelemetry.sampler` | `String` | `always_on` | 采样策略：`always_on` / `always_off` / `parent_based` / `trace_id_ratio` |
| `opentelemetry.ratio` | `double` | `1.0` | 采样概率，仅 `trace_id_ratio` 有效，须在 0.0~1.0 |
| `opentelemetry.url` | `String` | 无（null） | OTLP/日志接收地址；**当前版本仅作参数占位**，Span 实际缓存到内存（源码注释：已归档，OTA 升级后用于 OTLP 推送） |

> 键名说明：`service-name` 对应字段 `serviceName`，Spring Boot 宽松绑定下两种写法均可；其余键与字段同名。

---

## 5. SPI 扩展点与自动装配

本模块提供 3 个注册文件（`src/main/resources/META-INF/`）：

| 文件 | 注册内容 |
| --- | --- |
| `spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | `com.chua.opentelemetry.support.config.OpenTelemetryAutoConfiguration` |
| `extensions/com.chua.common.support.lang.datasource.engine.Engine` | `opentelemetry=com.chua.opentelemetry.support.engine.OpenTelemetryEngine` |
| `extensions/com.chua.common.support.apm.OtnlEngine` | `opentelemetry=com.chua.opentelemetry.support.engine.OpenTelemetryOtnlEngine` |

两个引擎类均标注 `@Spi("opentelemetry")`；SPI 注册文件格式为「名称=实现类全限定名」。

扩展指引：

- **换导出方式（如接 OTLP）**：通过 `OpenTelemetryClientBuilder.spanExporter(...)` 注入自定义 `SpanExporter`；本模块自身未内置 OTLP 传输依赖。
- **接别的 APM 后端**：实现 `OtnlEngine` 契约（契约在 `utils-support-common-starter` 的 `com.chua.common.support.apm` 包），按「名称=实现类」格式登记到对应 extensions 文件即可；本模块的 `OpenTelemetryOtnlEngine` 可作参考实现。
- **数据源引擎侧**：实现 `com.chua.common.support.lang.datasource.engine.Engine` 并登记 SPI。

---

## 6. 构建与依赖

```bash
mvn -o -q compile -DskipTests   # 在 utils-support-parent-starter 下
```

依赖清单（`pom.xml`）：

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | 基础工具与 SPI 契约（`Engine` / `OtnlEngine` / `Wraps` / `StringUtils`） |
| `com.chua:utils-support-datasource-starter` | compile | 数据源 SPI：`Dialect` / `EngineDataSource` |
| `com.chua:utils-support-runtime-protocol` | compile（`${project.version}`） | 运行时协议模型：`Span` / `Trace` / `SpanEvent` / `StatusCode` |
| `io.opentelemetry:opentelemetry-sdk` | compile | OpenTelemetry SDK 聚合包（POM 注释标注 1.66.0，提供 `OpenTelemetrySdk`/Builder；实际版本由父工程统一管理） |
| `io.opentelemetry:opentelemetry-sdk-trace` | compile | `1.62.0`，Trace API 与 Sdk 实现 |
| `io.opentelemetry:opentelemetry-sdk-testing` | compile | `1.62.0`，`InMemorySpanExporter`（默认无 OTLP 传输时缓存 Span） |
| `org.springframework.boot:spring-boot-autoconfigure` | compile | `@AutoConfiguration` / `@ConfigurationProperties` |
| `org.springframework.boot:spring-boot-configuration-processor` | provided | 配置元数据生成 |
| `org.projectlombok:lombok` | provided | `@Slf4j` / `@Data` |

版本口径：模块版本随父工程 `utils-support-middleware-parent`（当前 `4.0.0.51`）；`opentelemetry-sdk` 未在本 POM 显式声明版本号（POM 注释标注为 1.66.0，精确仲裁版本源码未提供）；`opentelemetry-sdk-trace` / `opentelemetry-sdk-testing` 显式 `1.62.0`。

模块当前无 `src/test` 测试目录；POM 注释提示：单元测试仅使用 `InMemorySpanExporter` 缓存，不依赖外部服务。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| OTLP / Collector 外发 | 未内置传输实现；`opentelemetry.url` 当前只是占位，Span 默认缓存在进程内存。需要外发时自行通过 `OpenTelemetryClientBuilder.spanExporter(...)` 注入 `SpanExporter` |
| 指标时序 / 拓扑 / 告警 / 日志 | `OpenTelemetryOtnlEngine` 明确不支持：`metrics()` / `topology()` 返回 empty，`alarms()` / `logs()` 返回空列表（这些需要 Collector/后端） |
| SQL / 元数据 / 写操作 | `OpenTelemetryEngine` 为只读数据源：`store` / `execute` / `query` / `update` / `delete` / `meta` 抛 `UnsupportedOperationException`；`getExecutor` / `getDialect` 返回 null |
| 自动埋点 | 无 Java Agent / 字节码增强；Span 需通过 `startSpan(...)` 或 `tracer()` 手动创建 |
| 多实例 Span 互通 | 每个 `OpenTelemetryClient` 持有独立 `TracerProvider`，Span 不互通；SPI 引擎只读 `OpenTelemetryClientHolder` 登记的那一个 |
