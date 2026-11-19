# utils-support-datalake-sink-starter

> 数据湖管线的**数据落地（Sink）模块**：定义 `DataSink` SPI、管线配置模型与执行引擎，内置 `log` / `statistic` / `realtime` / `jdbc` 四个落地实现，把管线处理完成的 `DataEnvelope` 写入日志、计数器、实时通道或关系型数据库。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.datalake.support`

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
    <artifactId>utils-support-datalake-sink-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| SPI 契约 | `DataSink` / `AccessSink`（落地）、`PipelineEngine` / `PipelineManager`（管线）、`RealTimeChannel`（实时通道）、`ActiveCollector` / `DataHandler`（采集） | 不实现具体采集协议（Kafka / MQTT / Chronicle 等由对应模块实现） |
| 内置实现 | `LogSink`、`StatisticSink`、`RealTimeSink`、`JdbcSink` 四个 Sink；`DefaultPipelineManager`、`DefaultPipelineEngine` | 不实现 filter / parser / cleaner / standardizer 处理器（引擎只执行 sink 阶段） |
| 数据模型 | `DataEnvelope`、`CollectData`、`PipelineState`、`PipelineConfig` | 不含 Spring 自动装配与 application 配置文件 |
| 落地行为 | 参数化 INSERT（JDBC）、日志打印、内存计数、实时通道投递 | 不提供建表 / DDL / Schema 管理；不绑定具体实时通道实现 |

**一句话**：管线末端的「落地处」。数据在管线中包装为 `DataEnvelope` 流转，`DefaultPipelineEngine` 按管线 JSON-DSL 把信封分发给一个或多个 `DataSink` 完成落地，并记录 `SINK_OK` / `SINK_FAIL` 状态与 trace。

### 1.3 包结构

| 包 | 内容 |
| --- | --- |
| `spi.sink` | `DataSink`、`AccessSink` |
| `spi.pipeline` | `PipelineEngine`、`PipelineManager`、`PipelineConfig`（含 `PipelineStageConfig`） |
| `spi.realtime` | `RealTimeChannel` |
| `spi.collection` | `ActiveCollector`、`DataHandler` |
| `model` | `DataEnvelope`、`CollectData`、`PipelineState` |
| `sink` | `LogSink`、`StatisticSink`、`RealTimeSink`、`JdbcSink` |
| `pipeline` | `DefaultPipelineManager` |
| `engine` | `DefaultPipelineEngine` |

---

## 2. 核心 API 与速查表

### 2.1 常用入口速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 落库到关系型数据库 | `sink.JdbcSink`（`@Spi("jdbc")`） | 参数化 `INSERT`；`withTable(String)` / `withDataSource(DataSource)` 实例级配置，`write` 的 `config` 可传 `table` / `dataSource` 本次覆盖 |
| 打印到日志 | `sink.LogSink`（`@Spi("log")`） | 输出 `pipelineId` / `traceId` / `parsed` |
| 累计数据量 | `sink.StatisticSink`（`@Spi("statistic")`） | `total()` 总量、`countOf(pipelineId)` 单管线计数、`reset()` 清零 |
| 实时分发 | `sink.RealTimeSink`（`@Spi("realtime")`） | 逐个调用已登记 `RealTimeChannel.deliver(envelope)`；无通道或全部失败返回 `false` |
| 保存 / 查询管线 DSL | `pipeline.DefaultPipelineManager` | `savePipeline` / `getPipeline` / `getCompiledPipeline` / `deletePipeline` / `pipelineIds()` |
| 执行管线 | `engine.DefaultPipelineEngine` | `execute(pipelineId, envelope)`；`invalidateSinkCache(id)` / `invalidateAllSinkCache()` 手动失效编译缓存 |
| 构造流转数据 | `model.DataEnvelope` | `new DataEnvelope(parsed)` 或 `builder()`；`setPipelineId` / `addTrace` / `isLog()` |
| 自定义 Sink | 实现 `DataSink` 或 `AccessSink` | 标注 `@Spi("类型名")`，并在 `META-INF/extensions/com.chua.datalake.support.spi.sink.DataSink` 登记实现类全限定名 |

### 2.2 DataSink 契约

| 方法 | 说明 |
| --- | --- |
| `type()` | Sink 类型标识，管线 DSL 的 `sink[].type` 按此查找实现（如 `jdbc`、`realtime`、`statistic`、`log`） |
| `start()` / `stop()` | 生命周期：建立 / 释放资源 |
| `write(envelope, config)` | 处理一条信封；返回 `true` 表示成功，`false` 表示失败 |
| `getDataSource()` | 接口约定存储型返回 `EngineDataSource`、访问型返回 `null`；当前 4 个内置实现均返回 `null`，`JdbcSink` 通过 `withDataSource` 直接持有 `javax.sql.DataSource` |

`AccessSink` 是标记接口（无额外方法）：访问型 Sink 不落盘，javadoc 注明会被 `DispatcherProvider` 自动绑定到 topic `#`，管线下发的每条数据都会被立即感知处理。

### 2.3 管线执行行为（DefaultPipelineEngine）

- 只读取 `stages.default.sink` 并按配置顺序执行；单个 Sink 抛异常不影响其余 Sink。
- `write` 返回 `true` → 信封置为 `SINK_OK` 并追加 trace；返回 `false` 或抛异常 → `SINK_FAIL`，失败同样写入 trace，不静默丢数据。
- Sink 编译缓存：首次 `execute` 时把 `type → DataSink` 编译为数组缓存；任一 `type` 未解析到则不缓存，晚注册的 Sink 下次执行自动生效。
- `DefaultPipelineManager` 侧「一次解析、多次执行」：`savePipeline` 时即把 DSL JSON 编译为 `PipelineConfig` 缓存（`getCompiledPipeline` 直读缓存）；重新保存会自动通知引擎失效对应管线的 Sink 缓存；另有 `clearCompiledCache()` / `compiledCacheSize()` 用于手动失效与观测。

`PipelineState` 状态枚举：`RECEIVED` → `FILTERED` → `PARSED` → `CLEANED` → `STANDARDIZED` → `SINK_OK` / `SINK_FAIL`（本模块引擎实际推进到 `SINK_OK` / `SINK_FAIL`）。

---

## 3. 快速开始

最短可运行示例（纯 Java，无 Spring）：

```java
import com.chua.datalake.support.engine.DefaultPipelineEngine;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.pipeline.DefaultPipelineManager;
import com.chua.datalake.support.spi.sink.DataSink;
import com.chua.datalake.support.sink.LogSink;
import com.chua.datalake.support.sink.StatisticSink;

import java.util.HashMap;
import java.util.Map;

// 1. Sink 注册表：type → 实例（键需与 DSL 里的 sink[].type 一致）
Map<String, DataSink> sinks = new HashMap<>();
sinks.put("log", new LogSink());
StatisticSink statistic = new StatisticSink();
sinks.put("statistic", statistic);

// 2. 配置管理器 + 执行引擎（dispatcher 当前预留、暂不使用，可传 null）
DefaultPipelineManager manager = new DefaultPipelineManager();
DefaultPipelineEngine engine = new DefaultPipelineEngine(manager, sinks, null);

// 3. 保存管线 JSON-DSL（保存时即编译缓存；重新保存会自动失效旧缓存）
manager.savePipeline("pipeline-001", """
        {
          "id": "pipeline-001",
          "stages": {
            "default": {
              "sink": [ {"type": "log"}, {"type": "statistic"} ]
            }
          }
        }
        """);

// 4. 构造数据信封并执行
DataEnvelope envelope = new DataEnvelope(Map.of("name", "张三"));
envelope.setPipelineId("pipeline-001");
engine.execute("pipeline-001", envelope);

// 5. 读取统计
statistic.total();                     // 总量
statistic.countOf("pipeline-001");     // 单管线计数
```

落库示例（`JdbcSink`）：

```java
import com.chua.datalake.support.sink.JdbcSink;

// 实例级配置：表名 + 数据源
JdbcSink jdbc = new JdbcSink().withTable("t_event").withDataSource(dataSource);

// 或写入时通过 config 本次覆盖（优先级：config > 实例配置）
jdbc.write(envelope, Map.of("table", "t_event", "dataSource", dataSource));
```

> `JdbcSink` 把 `envelope.parsed` 的一层字段拼成参数化 `INSERT`：表名、列名经 `SqlName` 白名单校验，值全部占位符绑定；`_cdc_` 前缀列跳过；嵌套 `Map` / `Iterable` / 数组值序列化为 JSON 字符串落列；影响行数不为 1 视为失败。

---

## 4. 配置项

本模块**没有 Spring 配置前缀与配置文件**（`src/main/resources` 下仅有 SPI 登记文件与 GraalVM native-image 配置）。所有参数来自两处：管线 JSON-DSL（`PipelineConfig`）与 `DataSink.write(envelope, config)` 的 `config` 参数。

### 4.1 管线 DSL（PipelineConfig）

```json
{
  "id": "pipeline-001",
  "stages": {
    "default": {
      "filter": [{"name": "null-filter"}],
      "parser": [{"name": "json"}],
      "sink": [
        {"type": "jdbc"},
        {"type": "realtime"}
      ]
    }
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String | 管线唯一标识 |
| `stages` | Map | 阶段名 → `PipelineStageConfig`；本模块引擎当前读取键 `default` |
| `stages.<name>.filter` | List&lt;Map&gt; | 过滤器列表（仅承载配置；本模块不执行该阶段） |
| `stages.<name>.parser` | List&lt;Map&gt; | 解析器列表（同上） |
| `stages.<name>.cleaner` | List&lt;Map&gt; | 清洗器列表（同上） |
| `stages.<name>.standardizer` | List&lt;Map&gt; | 标准化器列表（同上） |
| `stages.<name>.sink` | List&lt;Map&gt; | Sink 列表，每项至少含 `type` |

### 4.2 sink 条目键

| 键 | 适用 Sink | 说明 |
| --- | --- | --- |
| `type` | 全部 | Sink 类型标识：内置 `log` / `statistic` / `realtime` / `jdbc`，或自定义注册名 |
| `table` | `jdbc` | 目标表名；本次 `config` 优先于 `withTable` 实例配置 |
| `dataSource` | `jdbc` | `javax.sql.DataSource` 实例；本次 `config` 优先于 `withDataSource` 实例配置 |

其余 sink 条目键会随整条 sink 配置 Map 原样透传给 `DataSink.write(envelope, config)`，由对应 Sink 自行解释。

---

## 5. SPI 扩展点

| SPI | 登记文件（`META-INF/extensions/`） | 本模块内置实现 |
| --- | --- | --- |
| `spi.sink.DataSink` | `com.chua.datalake.support.spi.sink.DataSink` | `LogSink`、`StatisticSink`、`RealTimeSink`、`JdbcSink`（每行一个实现类全限定名） |
| `spi.sink.AccessSink` | 随 `DataSink` 登记（标记接口） | `LogSink`、`StatisticSink`、`RealTimeSink` |
| `spi.realtime.RealTimeChannel` | `com.chua.datalake.support.spi.realtime.RealTimeChannel`（本模块 resources 未内置该文件） | 无；按 javadoc 支持写「实现类全限定名」或「别名=实现类全限定名」，实现类需无参构造 |
| `spi.pipeline.PipelineEngine` / `PipelineManager` | 可直接编码替换实现 | `DefaultPipelineEngine` / `DefaultPipelineManager` |
| `spi.collection.ActiveCollector` / `DataHandler` | 由数据源 / 中间件模块提供实现 | 无 |

自定义 Sink 示例：

```java
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.DataSink;

import java.util.Map;

@Spi("my-sink")
public class MySink implements DataSink {

    @Override
    public String type() {
        return "my-sink";
    }

    @Override
    public void start() { }

    @Override
    public void stop() { }

    @Override
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        // 处理 envelope.getParsed()；返回 false 会被引擎记为 SINK_FAIL
        return true;
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}
```

登记（`src/main/resources/META-INF/extensions/com.chua.datalake.support.spi.sink.DataSink` 追加一行）：

```text
com.example.MySink
```

实时通道示例（把管线下发的数据推给 WebSocket / SSE / 订阅器等）：

```java
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.realtime.RealTimeChannel;

public class MyChannel implements RealTimeChannel {

    @Override
    public String name() {
        return "my-channel";
    }

    @Override
    public boolean deliver(DataEnvelope envelope) {
        // 至少有一个下游收下返回 true；无人认领或全部失败返回 false
        return false;
    }
}
```

---

## 6. 构建与依赖

```bash
# 在 utils-support-parent-starter 仓库根目录编译
mvn -o -q compile -DskipTests

# 仅编译本模块
mvn -o -q -pl utils-support-datalake-parent/utils-support-datalake-sink-starter compile -DskipTests
```

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | SPI 基础设施（`@Spi`、`ServiceProvider`）、`Json`、`Wraps`、`SqlName`、`EngineDataSource`、`DispatcherProvider`、`StringUtils` |
| `org.projectlombok:lombok` | provided | `@Data` / `@Builder` / `@Slf4j` 等注解处理 |

- 父模块：`utils-support-datalake-parent`（数据中台父模块：数据采集、订阅、分析、查询等）。
- 顶层：`utils-support-parent-starter` `4.0.0.51`，`maven.compiler.source/target=25`。
- GraalVM native-image：`META-INF/native-image/com.chua/utils-support-datalake-sink-starter/` 下 `reflect-config.json`（4 个 Sink 的反射注册）与 `resource-config.json`（保留 `META-INF/extensions/.*`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| 采集协议实现 | `ActiveCollector` 只是契约；Kafka / Chronicle / MQTT 等采集实现由对应中间件 / datalake 模块提供 |
| filter / parser / cleaner / standardizer 的执行 | 引擎只读取并执行 `stages.default.sink`；这些阶段在 `PipelineStageConfig` 中仅承载配置 |
| 实时通道具体实现 | `RealTimeSink` 通过 `ServiceProvider` 发现 `RealTimeChannel` 实现；javadoc 提及的订阅端 `SubscriberChannel` 不在本模块 |
| Dispatcher 路由 | `DefaultPipelineEngine` 的 `DispatcherProvider` 参数当前预留、暂不使用 |
| Spring Boot 自动装配 / 配置文件 | 无 `application*.yml`、无 `@ConfigurationProperties`、无自动配置类 |
| 建表 / DDL / Schema 管理 | `JdbcSink` 只做参数化 `INSERT`，不建表、不改表 |
| 数据查询与分析 | 属于 `utils-support-datalake-parent` 下其它模块（如 `utils-support-datalake-query-starter`） |
