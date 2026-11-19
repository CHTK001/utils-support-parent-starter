# utils-support-datalake-cdc-starter

> MySQL CDC **被动推送入站适配器**：通过 MySQL Binlog Protocol 实时捕获行级变更（INSERT/UPDATE/DELETE），包装为 `DataEnvelope` 交给 `PipelineEngine` 处理。
>
> 版本：`4.0.0.51`（类 `@since 4.0.0.42`）｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.datalake.support.cdc`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 事件数据结构](#4-事件数据结构)
- [5. 断点与至少一次语义](#5-断点与至少一次语义)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datalake-cdc-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | MySQL binlog 的 CDC **被动推送**入站适配器：数据库变更实时推给管线 | 不是主动拉取采集器（`Collector` / `ScheduledCollector` 不在本模块） |
| 职责 | 行级事件 → `DataEnvelope` → `PipelineEngine.execute(pipelineId, envelope)` | 不做过滤、解析、清洗、标准化、下沉（管线层与 Sink 在 `utils-support-datalake-sink-starter`） |
| 投递语义 | **至少一次**：先投递成功再推进断点，重启最坏是重复消费，不会跳过事件 | 不提供精确一次（exactly-once），不做幂等去重 |
| 形态 | 纯 Java 库，`Builder` 构造，守护线程承载 binlog 流 | 不是 Spring Boot starter：无自动配置、无配置属性文件 |
| 范围 | 仅 MySQL | 不支持其它数据库的 CDC（PostgreSQL/Oracle 等；Debezium 通用 CDC 在 `utils-support-debezium-starter`） |
| 数据形态 | 行数据按 binlog 原始 `Serializable[]` 传递，附加 `_cdc_op` / `_cdc_table_id` / `_cdc_ts` 元数据 | 不做列名映射、不做表结构（Schema）解析 |

本模块只有一个公开类：`MysqlCdcListener`（含内嵌 `MysqlCdcListener.Builder`），底层使用 `com.zendesk:mysql-binlog-connector-java:0.25.6` 的 `BinaryLogClient`。

---

## 2. 核心 API 与速查表

### 2.1 生命周期与状态（`MysqlCdcListener`）

| 方法 | 说明 |
| --- | --- |
| `static Builder builder()` | 创建构建器（构造器私有，只能经 Builder 构建） |
| `synchronized void start()` | 启动 CDC 监听：在守护线程 `datalake-mysql-cdc` 内建立 binlog 流，最多等待 `connectTimeout` 毫秒；握手失败、通信异常或超时都会断开并抛 `IllegalStateException`，不会在没跑起来的情况下正常返回 |
| `synchronized void stop()` | 幂等停止：无论是否已连上都断开客户端并回收监听线程（等待线程退出上限 3000 毫秒） |
| `boolean isRunning()` | 是否运行中：`true` 表示 binlog 流真实连通（`start()` 之后、连接断开即 `false`） |

### 2.2 指标与断点读取

| 方法 | 说明 |
| --- | --- |
| `long getEventCount()` | 已处理事件数（含投递失败的事件），同时用于生成 `traceId` |
| `long getFailureCount()` | 进入管线失败的事件数（这些事件不会推进断点） |
| `String currentBinlogFile()` | 当前 binlog 文件名（断点用；未连接时返回构造配置值，可能为 `null`） |
| `long currentBinlogPosition()` | 当前 binlog 位点（断点用；未连接时返回构造配置值） |

### 2.3 构建器（`MysqlCdcListener.Builder`）

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `host(String)` | `127.0.0.1` | MySQL 主机 |
| `port(int)` | `3306` | MySQL 端口 |
| `username(String)` | `root` | 复制账号用户名 |
| `password(String)` | 空字符串 | 复制账号密码 |
| `serverId(int)` | `1` | 服务器标识，集群内唯一 |
| `tableId(Long)` | `null`（全部表） | 只监听指定 binlog 表 id 的事件 |
| `pipelineId(String)` | 无（**必填**） | 目标管线标识 |
| `pipelineEngine(PipelineEngine)` | 无（**必填**） | 管线引擎 |
| `resumeFrom(String file, long position)` | 未设置 | 恢复起点：binlog 文件名与位点，必须成对给出，否则从服务端当前位置开始 |
| `connectTimeout(long)` | `5000`（毫秒） | 建立连接的等待上限 |
| `checkpoint(BiConsumer<String, Long>)` | `null` | 断点回调：每次事件成功进入管线后回传 `(binlog 文件名, 位点)` |
| `build()` | — | 校验必填项后构建；`pipelineId`/`pipelineEngine` 缺失，或只给 `resumeFrom` 文件名而位点非正数，抛 `IllegalArgumentException` |

### 2.4 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建监听器 | `MysqlCdcListener.builder()...build()` | `pipelineId` 与 `pipelineEngine` 必填 |
| 启动监听 | `start()` | 阻塞至连接建立，失败抛 `IllegalStateException` |
| 停止监听 | `stop()` | 幂等，可重复调用 |
| 只监听某张表 | `Builder.tableId(Long)` | 按 binlog 表 id 过滤；`null` = 全部表 |
| 断点续传 | `Builder.checkpoint(...)` + `Builder.resumeFrom(file, position)` | 先持久化断点，重启时续读 |
| 查看处理量 / 失败量 | `getEventCount()` / `getFailureCount()` | 失败事件不推进断点 |
| 查看当前位点 | `currentBinlogFile()` / `currentBinlogPosition()` | 供断点持久化与监控使用 |
| 判断是否在线 | `isRunning()` | 反映客户端真实连通状态 |

---

## 3. 快速开始

### 3.1 前置条件（来自源码 Javadoc）

MySQL 侧需开启 ROW 格式 binlog，并创建具备复制权限的账号：

```properties
log-bin=mysql-bin
binlog-format=ROW
```

```sql
CREATE USER 'datalake'@'%' IDENTIFIED BY 'xxx';
GRANT REPLICATION SLAVE ON *.* TO 'datalake'@'%';
```

### 3.2 最短可运行示例

`PipelineEngine` 是单方法接口，可直接用 Lambda 演示；生产环境请使用 `utils-support-datalake-sink-starter` 提供的管线引擎。

```java
import com.chua.datalake.support.cdc.MysqlCdcListener;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;

// 演示用引擎：打印进入管线的信封数据
PipelineEngine engine = (pipelineId, envelope) ->
        System.out.println(envelope.getParsed());

MysqlCdcListener listener = MysqlCdcListener.builder()
        .host("127.0.0.1")
        .port(3306)
        .username("datalake")          // 具备 REPLICATION SLAVE 权限的账号
        .password("xxx")
        .serverId(1001)                // 集群内唯一
        .pipelineId("mysql-cdc-demo")
        .pipelineEngine(engine)
        .checkpoint((file, position) -> saveCheckpoint(file, position)) // 断点持久化（可选）
        .connectTimeout(5000)          // 建连等待上限（毫秒）
        .build();

listener.start();   // 阻塞至 binlog 连接建立；失败抛 IllegalStateException
// ... 宿主线程可继续做其它事情，事件由守护线程推入管线
listener.stop();    // 幂等停止，释放连接与监听线程
```

### 3.3 从断点恢复

```java
// lastFile / lastPosition 来自上次 checkpoint 回调的持久化结果
MysqlCdcListener listener = MysqlCdcListener.builder()
        // ... 连接与管线配置同上
        .resumeFrom(lastFile, lastPosition)   // 从上次断点续读
        .build();
listener.start();
```

---

## 4. 事件数据结构

binlog 的 `WriteRows` / `UpdateRows` / `DeleteRows` 事件被转换为 `Map<String, Object>`，作为 `DataEnvelope.parsed` 进入管线：

| 键 | 类型 | 出现于 | 说明 |
| --- | --- | --- | --- |
| `_cdc_op` | `String` | 全部操作 | `INSERT` / `UPDATE` / `DELETE` |
| `_cdc_table_id` | `long` | 全部操作 | binlog 表 id |
| `_cdc_ts` | `long` | 全部操作 | 事件头时间戳 |
| `row` | `Serializable[]` | INSERT / DELETE | 行数据（binlog 原始列值数组） |
| `before` / `after` | `Serializable[]` | UPDATE | 变更前 / 变更后的行数据 |

`DataEnvelope` 的组装方式（与源码一致）：

```java
DataEnvelope envelope = DataEnvelope.builder()
        .parsed(data)                                  // 上表内容
        .pipelineId(pipelineId)
        .traceId("cdc-" + eventCount.incrementAndGet()) // 如 cdc-1、cdc-2
        .timestamp(System.currentTimeMillis())          // 受理时刻（毫秒）
        .build();
envelope.addTrace("[CDC] op=" + data.get("_cdc_op"));   // 首条迹信息

pipelineEngine.execute(pipelineId, envelope);           // 交给管线执行
```

> 本模块**不做列名映射与表结构解析**：`row` / `before` / `after` 保持 binlog 驱动给出的原始 `Serializable[]`，列语义由下游管线阶段处理。

---

## 5. 断点与至少一次语义

- **先投递、后记断点**：只有 `pipelineEngine.execute(...)` 成功返回后，才以 `checkpoint.accept(currentBinlogFile(), currentBinlogPosition())` 回调位点；由调用方决定如何持久化。
- **失败不推进断点**：进入管线失败的事件计入 `getFailureCount()`，断点保持原位，重启后最坏是**重复消费**，不会跳过未处理事件（至少一次投递）。
- **恢复必须成对**：`resumeFrom(file, position)` 需同时给出文件名与正数位点；只给文件名而位点非正数时 `build()` 直接抛 `IllegalArgumentException`；两者都未设置时从服务端当前位置开始。
- **单事件失败不中断流**：事件处理或反序列化失败仅记录 `[datalake-cdc]` 错误日志，不会断开 binlog 流。

---

## 6. 配置项

**无配置文件与属性键。** 依据：模块 `src/main/resources` 下没有任何资源文件（无 `META-INF/extensions`、无 `spring.factories`、无 properties/yml），源码中也未读取任何配置属性；全部参数通过 `Builder` 以代码方式给出，默认值见 [2.3 构建器](#23-构建器mysqlcdclistenerbuilder)。

| 参数 | Builder 方法 | 默认值 |
| --- | --- | --- |
| 主机 | `host(String)` | `127.0.0.1` |
| 端口 | `port(int)` | `3306` |
| 用户名 | `username(String)` | `root` |
| 密码 | `password(String)` | 空字符串 |
| 服务器标识 | `serverId(int)` | `1` |
| 表过滤 | `tableId(Long)` | `null`（监听全部表） |
| 管线标识 | `pipelineId(String)` | 无（必填） |
| 管线引擎 | `pipelineEngine(PipelineEngine)` | 无（必填） |
| 恢复起点 | `resumeFrom(String, long)` | 未设置（从服务端当前位置开始） |
| 建连超时 | `connectTimeout(long)` | `5000` 毫秒 |
| 断点回调 | `checkpoint(BiConsumer<String, Long>)` | `null`（不回调） |

---

## 7. SPI 扩展点

本模块自身**未声明任何 SPI 扩展文件**（`src/main/resources/META-INF/extensions` 不存在；模块仅含 `pom.xml` 与 1 个源文件 `MysqlCdcListener.java`）。它接入的外部契约有两个：

| 扩展点 | 类型 | 说明 |
| --- | --- | --- |
| `PipelineEngine` | SPI 接口（定义于 `utils-support-datalake-sink-starter`，包 `com.chua.datalake.support.spi.pipeline`） | 本模块只调用 `execute(pipelineId, envelope)`；同仓 `utils-support-datalake-sink-starter` 提供 `DefaultPipelineEngine` 实现，亦可自行实现注入 |
| 断点回调 | `BiConsumer<String, Long>`（JDK 函数式接口） | 由使用方实现 `(binlog 文件名, 位点)` 的持久化逻辑，实现断点续传 |

> 想接入其它数据库的 CDC（如 PostgreSQL），不是在本模块扩展；本模块与 binlog 驱动直接耦合，见 [边界](#9-边界这些能力不在本模块)。

---

## 8. 构建与依赖

- 构建：JDK 25 + Maven，随父模块 `utils-support-datalake-parent`（`4.0.0.51`）聚合构建；编译命令 `mvn -o -q compile -DskipTests`。
- 模块规模：1 个源文件（`MysqlCdcListener.java`，约 585 行）。

依赖清单（来自 `pom.xml`）：

| 依赖 | 版本 | Scope | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父版本 `4.0.0.51` | compile | 通用工具（`Wraps`、`StringUtils` 等） |
| `com.chua:utils-support-datalake-sink-starter` | `${project.version}` | **provided** | `DataEnvelope` / `PipelineEngine` 契约，由宿主自行提供 |
| `com.zendesk:mysql-binlog-connector-java` | `0.25.6` | compile | MySQL binlog 协议客户端（`BinaryLogClient`） |
| `com.fasterxml.jackson.core:jackson-databind` | 随父依赖管理 | compile | JSON（pom 声明） |
| `org.projectlombok:lombok` | 随父依赖管理 | provided | 编译期生成（如 `@Slf4j` 日志） |

> 因 `utils-support-datalake-sink-starter` 为 `provided`，把本模块引入宿主时，需在宿主侧自行引入该模块（或其聚合 starter），以获得 `DataEnvelope`、`PipelineEngine` 等类型。

---

## 9. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| 管线 DSL、过滤器、解析器、清洗器、标准化器与执行引擎 | `utils-support-datalake-sink-starter`（`PipelineEngine` SPI、`DefaultPipelineEngine`） |
| 数据下沉 Sink（JDBC / 日志 / 实时 / 统计） | `utils-support-datalake-sink-starter`（`JdbcSink`、`LogSink`、`RealTimeSink`、`StatisticSink`） |
| 其它入站适配（MQTT、Webhook） | `utils-support-datalake-mqtt-starter`、`utils-support-datalake-webhook-starter` |
| 通用 CDC / 其它数据库（Debezium 体系） | `utils-support-debezium-starter`（位于 `utils-support-datasource-parent`） |
| Spring 自动配置、配置属性绑定 | 无 —— 本模块不是 Spring Boot starter，仅提供 `Builder` 编程式入口 |
| 列名映射、表结构（Schema）解析、精确一次（exactly-once） | 无 —— 行数据按 binlog 原始 `Serializable[]` 传递，投递语义为至少一次 |
