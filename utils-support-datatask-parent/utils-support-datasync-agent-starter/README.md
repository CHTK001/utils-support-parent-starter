# utils-support-datasync-agent-starter

> 数据同步 **Agent 侧**契约与内置实现：定义 `Source` / `Sink` 抽象、偏移量（水位）模型与文件存储、进程内 Chronicle 分发执行器，以及连接数据同步服务端的 HTTP / WebSocket 远程 Agent。
>
> 版本：`4.0.0.51`｜JDK：`25`（仓库根 `pom.xml` 中 `java.version` / `maven.compiler.source` / `maven.compiler.target` 均为 `25`）｜包名：`com.chua.datasync.agent.support`
>
> 全部公开类型的 Javadoc 标记均为 `@since 4.0.0.42`。

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 契约接口](#22-契约接口)
  - [2.3 内置 Source 实现](#23-内置-source-实现)
  - [2.4 内置 Sink 实现](#24-内置-sink-实现)
  - [2.5 偏移量（水位）模型与存储](#25-偏移量水位模型与存储)
  - [2.6 进程内分发执行器 ReactorDataSyncExecutor](#26-进程内分发执行器-reactordatasyncexecutor)
  - [2.7 远程 Agent（连接数据同步服务端）](#27-远程-agent连接数据同步服务端)
  - [2.8 异常与错误码](#28-异常与错误码)
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
    <artifactId>utils-support-datasync-agent-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块名 `DataSync Agent Starter`，POM 描述：`DataSync Agent：Source/Sink 定义与传输协议实现`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 契约 | 定义数据同步的 Agent / Source / Sink 接口与方向、偏移量模型 | 不定义服务端编排与调度流程 |
| 实现 | 内置 CSV / TSV / 定界符文件、JDBC 数据库的读写实现；文件水位存储；进程内 Chronicle 分发执行器；HTTP / WebSocket 远程 Agent | 不内置任何数据库驱动（JDBC 驱动需宿主自备） |
| 装配 | 通过构造参数与方法注入组装 | 不是 Spring Boot 自动装配 starter（`src/main/resources` 下仅有 `META-INF/native-image` 的 `reflect-config.json`，无 `spring.factories` / `META-INF/extensions`） |

**判断口径**：需要「把数据从 A 搬到 B」的 Agent 侧能力（读、写、水位、进程内/远程分发）放本模块；服务端编排、任务调度、管理界面不在本模块。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 从 CSV 文件读数据 | `new CsvDataSyncAgentSource(sourceId, inputId, filePath)` | 首行为表头，按行产出 `Map<String, Object>` |
| 从 TSV 文件读数据 | `new TsvDataSyncAgentSource(sourceId, inputId, filePath)` | 分隔符固定为 `\t` |
| 任意定界符文件 | `new DelimitedDataSyncAgentSource(sourceId, inputId, filePath, delimiter)` | CSV / TSV 的公共基类 |
| 从数据库读（全量/增量） | `new JdbcDataSyncAgentSource(...)` | HikariCP 连接池；传增量列即支持水位增量 |
| 写 CSV / TSV / 定界符文件 | `new CsvDataSyncAgentSink(...)` / `new TsvDataSyncAgentSink(...)` / `new DelimitedDataSyncAgentSink(...)` | 可选覆盖 / 追加模式 |
| 批量写数据库 | `new JdbcDataSyncAgentSink(sinkId, jdbcUrl, username, password, sql, batchSize, columnNames...)` | 事务 + 批量提交，失败回滚 |
| 组装并启动一个 Agent | `new AbstractDataSyncAgent(agentId) {{ addSource(...); addSink(...); }}` 后调 `start()` / `stop()` | 统一管理源/汇生命周期 |
| 增量水位持久化 | `SyncDataOffsetStorage`（内置 `FileSyncDataOffsetStorage`） | 按源 + 映射隔离水位文件 |
| 进程内发布/订阅 | `new ReactorDataSyncExecutor(agentId, serverMode)` | `subscribe` / `publish` / `unsubscribe` |
| 连接数据同步服务端 | `new DefaultHttpDataSyncAgent(agentId, serverUrl)` 或 `new DefaultWebSocketDataSyncAgent(agentId, serverUrl)` | 注册、订阅 `source:` / `sink:` topic |
| 错误码定位问题 | `DataSyncErrorCode`、`DataSyncAgentException#getErrorCodeString()` | 错误码格式 `DSYNC-{编号}` |

### 2.2 契约接口

**`DataSyncAgent`**（Agent 统一契约）：

| 方法 | 说明 |
| --- | --- |
| `String agentId()` | Agent 唯一标识 |
| `List<DataSyncAgentSource> sources()` | 关联的所有源（默认空列表） |
| `DataSyncAgentSource getSource(String sourceId)` | 按源 id 取源（默认返回 `null`） |
| `List<DataSyncAgentSink> sinks()` | 关联的所有 Sink（默认空列表） |
| `DataSyncAgentSink getSink(String sinkId)` | 按 sink id 取 Sink（默认返回 `null`） |
| `void onDataReceived(List<Map<String, Object>> data)` | 数据到达监听回调（默认空实现） |
| `DataSyncSource toSource()` | 当前 Agent 对应的源门面（默认返回 `null`） |
| `DataSyncSink toSink()` | 当前 Agent 对应的 Sink 门面（默认返回 `null`） |
| `boolean isRunning()` | 是否正在运行（默认 `false`） |
| `String dataUrl()` | 数据源 URL，供调试（默认空串） |
| `void start()` / `void stop()` | 启动 / 停止 Agent |

**`AbstractDataSyncAgent`**（抽象基类）：构造参数为 `agentId`；`addSource(...)` / `addSink(...)` 为 `protected`，供子类登记源与汇；`sources()` / `sinks()` 返回不可修改列表；`stop()` 依次关闭所有 source 与 sink，单个关闭异常仅记录 warn 日志、不中断其余关闭。

**`DataSyncAgentSource`**（源，读取侧）：

| 方法 | 说明 |
| --- | --- |
| `String sourceId()` | 源实例唯一标识 |
| `String inputId()` | 输入标识，用于 Mapping 绑定 |
| `Flux<Map<String, Object>> read(Map<String, Object> params)` | 读取数据流 |
| `SyncDataOffset readOffset(Map<String, Object> params)` | 读取偏移量，默认返回 `null` |
| `SyncDataOffset readOffset(Map<String, Object> params, String mappingId)` | 按映射读取偏移量（每目标独立水位），默认退回按源读取 |
| `void writeOffset(SyncDataOffset offset)` | 持久化偏移量，默认空实现 |
| `Object getLastReadOffset()` | 本次读取的最后偏移量，默认返回 `null` |
| `void close()` | 释放资源 |

**`DataSyncAgentSink`**（汇，写入侧）：`sinkId()`、`write(Flux<Map<String, Object>>)`、`close()`。

**`DataSyncSource` / `DataSyncSink`**（门面接口）：`DataSyncSource` 含常量 `INPUT_ID = "sourceId"`，方法 `direction()` / `sourceId()` / `agentId()` / `read(SyncDataOffset, Map)` / `currentOffset()` / `write(Flux)` / `close()`；`DataSyncSink` 含常量 `SINK_ID = "sinkId"`，方法 `sinkId()` / `write(Flux)` / `close()`。**本模块没有这两个接口的内置实现**，它们通过 `DataSyncAgent#toSource()` / `toSink()` 暴露。

**`Direction` / `Directional`**（方向模型）：`Direction` 枚举取值 `INPUT`（输入）/ `OUTPUT`（输出）；`Directional` 仅声明 `Direction direction()`。内置 Source 实现均返回 `INPUT`，内置 Sink 实现均返回 `OUTPUT`。

### 2.3 内置 Source 实现

| 实现 | 构造参数 | 增量能力 |
| --- | --- | --- |
| `DelimitedDataSyncAgentSource` | `(sourceId, inputId, filePath, delimiter)`、`(sourceId, inputId, filePath, delimiter, SyncDataOffsetStorage)` | `params["offset"]` 传数字时跳过对应行数；`getLastReadOffset()` 返回本次读取后的行号 |
| `CsvDataSyncAgentSource` | `(sourceId, inputId, filePath)`（分隔符 `,`）、`(sourceId, inputId, filePath, delimiter)` | 继承定界符实现 |
| `TsvDataSyncAgentSource` | `(sourceId, inputId, filePath)`（分隔符 `\t`） | 继承定界符实现 |
| `JdbcDataSyncAgentSource` | `(sourceId, inputId, jdbcUrl, username, password, sql, columnNames...)`（全量）、`(sourceId, inputId, jdbcUrl, username, password, sql, List<String> incColumns, SyncDataOffsetStorage, columnNames...)`（增量） | 传 `incColumns` 后按增量列生成复合水位 `WHERE` 条件 |

补充说明（均来自源码实现）：

- 定界符源以 **UTF-8** 流式读取，首行作为表头，按 `String.split(Pattern.quote(分隔符), -1)` 切分（分隔符按字面量处理，非正则）产出每行 `Map`。
- JDBC 源基于 **HikariCP**，连接池在**首次读取时才创建**（配置阶段不建立连接）；读取时 `fetchSize=500`、正向遍历。
- JDBC 增量读取：`params["offset"]` 有值且配置了增量列（原 SQL 为 `SELECT`）时，把 SQL 包装为 `SELECT * FROM (原SQL) __sync_inc WHERE <复合条件> ORDER BY 增量列 ASC`；复合条件展开为「前 n 列相等且第 n+1 列更大」的 `OR` 分支（不依赖数据库行值比较语法）；水位值支持 JSON 数组串（多列）或裸值（单列）。
- JDBC 源的 `readOffset` / `writeOffset` 委托给构造时传入的 `SyncDataOffsetStorage`（`null` 时用默认文件存储）。

### 2.4 内置 Sink 实现

| 实现 | 构造参数 | 默认写入模式 |
| --- | --- | --- |
| `DelimitedDataSyncAgentSink` | `(sinkId, filePath, delimiter)`、`(sinkId, filePath, delimiter, boolean append)` | `append=false`（覆盖） |
| `CsvDataSyncAgentSink` | `(sinkId, filePath)`、`(sinkId, filePath, boolean append)` | 覆盖模式（重复导出不累积历史数据） |
| `TsvDataSyncAgentSink` | `(sinkId, filePath)`、`(sinkId, filePath, boolean append)` | 追加模式（`append=true`） |
| `JdbcDataSyncAgentSink` | `(sinkId, jdbcUrl, username, password, sql, columnNames...)`（`batchSize=100`）、`(sinkId, jdbcUrl, username, password, sql, int batchSize, columnNames...)` | 事务批量写入 |

补充说明：

- 文件 Sink 以 UTF-8 `BufferedWriter` 写入，每行写 `row.toString()` + 系统换行符；追加模式用 `CREATE + APPEND`，覆盖模式用 `CREATE + TRUNCATE_EXISTING`；每次 `write` 用 try-with-resources 自动关闭。
- JDBC Sink 构造时即创建 HikariCP 连接池；`write` 中关闭自动提交，按 `batchSize` 缓冲后 `executeBatch()` + `commit()`，出错回滚并以 `DataSyncErrorCode.DB_WRITE_FAILED` 记录日志。

### 2.5 偏移量（水位）模型与存储

**`SyncDataOffset`**（不可变对象，`final` 字段、无 setter，实现 `Serializable`；非 Java `record`）：构造参数 `(String sourceId, Object offsetValue, long timestamp, String mappingId)`，访问器 `sourceId()` / `offsetValue()` / `timestamp()` / `mappingId()`。

**`SyncDataOffsetStorage`**（存储契约）：

| 方法 | 说明 |
| --- | --- |
| `SyncDataOffset read(String sourceId, SyncDataOffset defaultValue)` | 按源读取，失败/不存在返回默认值 |
| `SyncDataOffset read(String sourceId, String mappingId, SyncDataOffset defaultValue)` | 按「源 + 映射」读取（每目标独立水位）；接口默认实现退回按源读取 |
| `void write(SyncDataOffset offset)` | 写入偏移量 |
| `void delete(String sourceId)` | 删除指定源的偏移量 |
| `List<SyncDataOffset> listAll()` | 列出所有偏移量 |

**`FileSyncDataOffsetStorage`**（内置文件实现）：

- 默认构造使用 `${java.io.tmpdir}/datasync-offsets` 目录；也可传 `Path` 指定目录（目录不存在会自动创建，创建失败抛 `IllegalStateException`）。
- 水位键 = **源 + 映射**：文件名为 `<sourceId>__<mappingId>.offset`；映射为空时为 `<sourceId>.offset`。读取时若映射级文件不存在，会回落到源级文件（兼容历史数据）。
- 文件内容为一行 CSV 文本：`sourceId,offsetValue,timestamp,mappingId`（按 4 段解析）。
- `delete(sourceId)` 只删除 `<sourceId>.offset` 源级文件；`listAll()` 扫描目录下所有 `.offset` 文件并解析内容，解析失败的文件跳过。

> 为什么水位要按「源 + 映射」隔离（源码注释口径）：一个源写多个目标时，各目标水位必须互相独立，否则 A 目标推进水位会导致 B 目标漏读数据。

### 2.6 进程内分发执行器 ReactorDataSyncExecutor

基于响应式分派器（Chronicle）的发布/订阅封装。

```java
ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor("agent-1", false);

executor.setDirectDispatch(true);   // 同 JVM 直连派发，绕过 Chronicle；必须在 start() 之前调用
executor.subscribe("k1", rows -> System.out.println("收到 " + rows));   // 同一 sinkId 重复订阅 = 替换旧消费者
executor.start();
executor.publish("k1", java.util.List.of(java.util.Map.of("id", 1)));
executor.unsubscribe("k1");         // 直连模式下可取消订阅
executor.stop();
```

| 方法 | 说明 |
| --- | --- |
| `ReactorDataSyncExecutor(String agentId, boolean serverMode)` | 构造；`serverMode` 决定 topic 前缀 |
| `setDirectDispatch(boolean)` | 启用直连派发（同 JVM 内发布直接调用订阅者）；必须在 `start()` 之前调用 |
| `setDispatcherProvider(DispatcherProvider)` | 注入外部分派器；不注入时 `start()` 回退本地 Chronicle 实现 |
| `start()` / `stop()` | 启动 / 停止；直连模式下 `stop()` 清空消费者表 |
| `subscribe(String sinkId, Consumer<List<Map<String, Object>>> consumer)` | 订阅 sinkId 对应 topic；重复订阅替换旧消费者 |
| `unsubscribe(String sinkId)` | 取消订阅（仅直连模式生效） |
| `publish(String sinkId, List<Map<String, Object>> data)` | 向 sinkId 对应 topic 发布数据；直连模式找不到订阅者时告警并丢弃 |

topic 规则（源码 `buildTopic`）：服务端模式为 `server-{agentId}-{sinkId}`，客户端模式为 `consumer-{agentId}-{sinkId}`。topic 必须带 sinkId：一源多目标时多个 sink 挂在同一 agent 下，共用一个 topic 会互相抢消息。

### 2.7 远程 Agent（连接数据同步服务端）

`AbstractRemoteDataSyncAgent` 封装与数据同步服务端的注册、订阅与收发协议（底层使用 `com.chua.common.support.network.sync.SyncClient`）：

1. `start()`：连接服务端 → 向 `register:{agentId}` 发送注册消息（`{"agentId":"...","sources":[...],"sinks":[...]}`）→ 订阅 `source:{agentId}` 与 `sink:{agentId}` → 启动内部 `ReactorDataSyncExecutor(agentId, false)` 并为每个 sink 注册消费者。
2. 服务端拉取请求到达时：按 `sourceId` 找源，`source.read(Map.of()).buffer(100)` 分批，把每批通过 `onDataReceived(batch)` 回调，并推送到 `source:{agentId}:{sourceId}`。
3. 服务端推送到达时：解析消息中的 `sinkId` 与 `data` 字段，回调 `onDataReceived(data)` 后经执行器 `publish(sinkId, data)` 分发给对应 sink。
4. `stop()`：先停执行器，再断开 `SyncClient`，最后走基类关闭逻辑。

两个开箱实现：

| 实现 | 构造 | 说明 |
| --- | --- | --- |
| `DefaultHttpDataSyncAgent` | `(String agentId, String serverUrl)` | 以 `HttpSyncClient(agentId, serverUrl)` 连接服务端，`serverUrl` 形如 `http://127.0.0.1:9090` |
| `DefaultWebSocketDataSyncAgent` | `(String agentId, String serverUrl)` | 以 `WebSocketSyncClient(agentId, serverUrl)` 连接服务端，`serverUrl` 形如 `ws://127.0.0.1:9090` |

### 2.8 异常与错误码

模块内有**两个**同名异常类，用途不同：

- `com.chua.datasync.agent.support.DataSyncAgentException`：简单 `RuntimeException`，构造参数 `(String message)` 或 `(String message, Throwable cause)`。基类 `AbstractDataSyncAgent` 启动失败、远程 Agent 启动/注册失败时抛出。
- `com.chua.datasync.agent.support.exception.DataSyncAgentException`：带错误码的异常基类。构造参数支持 `(DataSyncErrorCode)`、`(DataSyncErrorCode, Object... args)`、`(DataSyncErrorCode, Throwable)`、`(DataSyncErrorCode, Throwable, Object... args)` 与 `(String message)`；取值方法 `getErrorCode()`、`getArgs()`、`getErrorCodeString()`（返回 `DSYNC-{编号}`）。

`DataSyncErrorCode` 枚举，`codeString()` / `getErrorCodeString()` 返回 `DSYNC-{编号}`（类别只体现在编号分段：1xxx 通用、2xxx 配置……见下表），方法：`code()`、`template()`、`format(Object...)`、`codeString()`、`formatWithCode(Object...)`。

| 类别 | 枚举项（编号） |
| --- | --- |
| 通用 (1xxx) | `UNKNOWN`(1000)、`INIT_FAILED`(1001)、`CLEANUP_FAILED`(1002) |
| 配置 (2xxx) | `CONFIG_MISSING`(2001)、`CONFIG_INVALID`(2002)、`MAPPING_NOT_FOUND`(2003)、`TRIGGER_INVALID`(2004) |
| Source (3xxx) | `SOURCE_NOT_FOUND`(3001)、`SOURCE_READ_FAILED`(3002)、`SOURCE_CONNECT_FAILED`(3003)、`SOURCE_RESOURCE_NOT_FOUND`(3004) |
| Sink (4xxx) | `SINK_NOT_FOUND`(4001)、`SINK_WRITE_FAILED`(4002)、`SINK_CONNECT_FAILED`(4003) |
| 网络 (5xxx) | `NETWORK_TIMEOUT`(5001)、`NETWORK_CONNECT_FAILED`(5002)、`NETWORK_DISCONNECTED`(5003) |
| 数据库 (6xxx) | `DB_CONNECT_FAILED`(6001)、`DB_QUERY_FAILED`(6002)、`DB_WRITE_FAILED`(6003)、`DB_TRANSACTION_FAILED`(6004)、`DB_POOL_EXHAUSTED`(6005) |
| 文件 (7xxx) | `FILE_NOT_FOUND`(7001)、`FILE_READ_FAILED`(7002)、`FILE_WRITE_FAILED`(7003)、`FILE_PERMISSION_DENIED`(7004) |
| 字段映射 (8xxx) | `MAPPING_FIELD_INVALID`(8001)、`MAPPING_FIELD_MISSING`(8002)、`MAPPING_TYPE_MISMATCH`(8003) |
| 方向约束 (9xxx) | `SOURCE_DIRECTION_ERROR`(9001)、`SINK_DIRECTION_ERROR`(9002)、`DIRECTION_MISMATCH`(9003) |

---

## 3. 快速开始

最短可运行示例：CSV 文件 → CSV 文件，经 Agent 管理生命周期。

```java
import com.chua.datasync.agent.support.AbstractDataSyncAgent;
import com.chua.datasync.agent.support.sink.CsvDataSyncAgentSink;
import com.chua.datasync.agent.support.source.CsvDataSyncAgentSource;

import java.util.Map;

public class QuickStart {
    public static void main(String[] args) {
        // 1. 源：读 CSV（首行为表头），inputId 用于 Mapping 绑定
        CsvDataSyncAgentSource source =
                new CsvDataSyncAgentSource("user-csv", "in", "D:/data/users.csv");

        // 2. 汇：写 CSV（默认覆盖模式）
        CsvDataSyncAgentSink sink =
                new CsvDataSyncAgentSink("user-copy", "D:/data/users-copy.csv");

        // 3. 组装 Agent（addSource/addSink 为 protected，用匿名子类登记）
        AbstractDataSyncAgent agent = new AbstractDataSyncAgent("agent-1") {{
            addSource(source);
            addSink(sink);
        }};

        // 4. 数据管道：源 → 汇
        sink.write(source.read(Map.of()));

        // 5. 生命周期
        agent.start();   // 启动
        agent.stop();    // 停止并依次关闭 source / sink
    }
}
```

**数据库增量同步**（JDBC 源 + 复合水位）：

```java
import com.chua.datasync.agent.support.source.JdbcDataSyncAgentSource;

import java.util.List;
import java.util.Map;

JdbcDataSyncAgentSource source = new JdbcDataSyncAgentSource(
        "mysql-user", "in",
        "jdbc:mysql://127.0.0.1:3306/demo", "root", "******",
        "SELECT id, name, updated_at FROM t_user",
        List.of("updated_at", "id"),   // 增量列（复合水位）；空列表 = 全量
        null,                          // 水位存储；null = 默认文件存储
        "id", "name", "updated_at");   // 输出列名

// params["offset"] 传上次水位（多列为 JSON 数组串，单列为裸值）
source.read(Map.of("offset", "[\"2026-01-01 00:00:00\",\"42\"]"))
      .subscribe(row -> System.out.println(row));
```

**接收服务端推送**（远程 Agent）：

```java
import com.chua.datasync.agent.support.http.DefaultHttpDataSyncAgent;

DefaultHttpDataSyncAgent agent =
        new DefaultHttpDataSyncAgent("agent-1", "http://127.0.0.1:9090");

agent.onDataReceived(data -> System.out.println("收到 " + data.size() + " 条"));
agent.start();
```

> Maven 依赖坐标见 [1.1](#11-maven-坐标)；JDBC 示例需宿主工程自行引入对应数据库驱动。

---

## 4. 配置项

本模块**不是 Spring Boot starter**，没有 `application.yml` / `*.properties` 配置项；一切通过**构造参数与方法注入**。源码中固定的内置约定值如下：

| 项 | 值 | 依据 |
| --- | --- | --- |
| 水位文件目录（默认） | `${java.io.tmpdir}/datasync-offsets` | `FileSyncDataOffsetStorage` 无参构造 |
| 水位文件名 | `<sourceId>.offset`；按映射隔离时 `<sourceId>__<mappingId>.offset` | `FileSyncDataOffsetStorage#fileNameOf` |
| 水位文件内容格式 | `sourceId,offsetValue,timestamp,mappingId`（一行，4 段） | `FileSyncDataOffsetStorage` 读写实现 |
| Chronicle 数据目录（默认） | `${java.io.tmpdir}/chronicle-datasync` | `ReactorDataSyncExecutor#start()` 未注入分派器时的回退值 |
| 分发 topic | 服务端模式 `server-{agentId}-{sinkId}`；客户端模式 `consumer-{agentId}-{sinkId}` | `ReactorDataSyncExecutor#buildTopic` |
| 远程注册 topic 与报文 | `register:{agentId}`；报文 `{"agentId":"...","sources":[...],"sinks":[...]}` | `AbstractRemoteDataSyncAgent#sendRegister` |
| 远程订阅 topic | `source:{agentId}`、`sink:{agentId}`；源数据回推 `source:{agentId}:{sourceId}` | `AbstractRemoteDataSyncAgent#start` / `#handleSourceRequest` |
| 服务端 Sink 推送消息字段 | `sinkId`、`data` | `AbstractRemoteDataSyncAgent#handleSinkPush` |
| 读取参数键 | `params["offset"]`：JDBC 源为水位值；定界符源为跳过行数（`Number`） | 两个 `read` 实现 |
| HikariCP 池参数（源与汇一致） | `maximumPoolSize=5`、`minimumIdle=1`、`connectionTimeout=30000ms`、`idleTimeout=600000ms`、`maxLifetime=1800000ms` | 两个 JDBC 实现的 `createDataSource` |
| JDBC 读取 | `fetchSize=500`、`FETCH_FORWARD`、连接池首次读取时惰性创建 | `JdbcDataSyncAgentSource` |
| JDBC Sink 默认批次 | `batchSize=100` | `JdbcDataSyncAgentSink` 四参构造 |
| 文件编码 | 源/汇均为 UTF-8 | 定界符源/汇实现 |

---

## 5. SPI 扩展点

本模块自身**没有** `META-INF/extensions` 等 SPI 注册文件（`src/main/resources` 仅有 native-image 的 `reflect-config.json`）。扩展方式是**实现接口后以构造参数注入**：

| 扩展点 | 做法 |
| --- | --- |
| 自定义水位存储 | 实现 `SyncDataOffsetStorage`（`read` / `write` / `delete` / `listAll`；需要按映射隔离时覆盖 `read(sourceId, mappingId, defaultValue)`），传入 `JdbcDataSyncAgentSource` / `DelimitedDataSyncAgentSource` 的构造器 |
| 自定义 Source | 实现 `DataSyncAgentSource`，或继承 `DelimitedDataSyncAgentSource` / `JdbcDataSyncAgentSource` |
| 自定义 Sink | 实现 `DataSyncAgentSink`，或继承 `DelimitedDataSyncAgentSink`（`CsvDataSyncAgentSink` / `TsvDataSyncAgentSink` 即这样实现） |
| 自定义 Agent | 继承 `AbstractDataSyncAgent`（本地）或 `AbstractRemoteDataSyncAgent`（远程） |
| 自定义数据到达处理 | 覆盖 `DataSyncAgent#onDataReceived(List<Map<String, Object>>)` |
| 替换分发底层 | 调 `ReactorDataSyncExecutor#setDispatcherProvider(DispatcherProvider)` 注入 `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` 实现；不注入则用内置 Chronicle 回退 |
| 替换远程传输协议 | `AbstractRemoteDataSyncAgent` 构造接受任意 `com.chua.common.support.network.sync.SyncClient`；common-starter 内置 `HttpSyncClient`、`WebSocketSyncClient` |

---

## 6. 构建与依赖

模块归属：`utils-support-parent-starter` → `utils-support-datatask-parent`。在本模块目录执行：

```bash
mvn -o -q compile -DskipTests
```

依赖清单（`pom.xml`）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | 契约与工具（`SyncClient`、`DispatcherProvider`、`Wraps`、`StringUtils` 等） |
| `com.zaxxer:HikariCP` | `${hikaricp.version}`（仓库根定义为 `7.1.0`） | JDBC 源/汇的连接池 |
| `net.openhft:chronicle-queue` | `5.27ea5` | 进程内分发执行器的 Chronicle 回退实现 |
| `com.chua:utils-support-chronicle-starter` | `${project.version}`（4.0.0.51） | `ChronicleDispatcherProvider` |
| `com.fasterxml.jackson.core:jackson-databind` | 由父 POM 管理 | 声明于 POM 的依赖 |

GraalVM native-image：`src/main/resources/META-INF/native-image/com.chua/utils-support-datasync-agent-starter/reflect-config.json` 登记了本模块全部 26 个公开类型（接口、实现、模型、异常、枚举，与 `src/main/java` 下 26 个类型一一对应）的反射元数据。

---

## 7. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| 数据同步服务端与任务编排 | `AbstractRemoteDataSyncAgent` 的 Javadoc 提到「数据同步服务端」，但服务端实现、任务调度与映射编排不在本模块源码中；同父模块下另有 `utils-support-datasync-starter` 等兄弟模块（见 `utils-support-datatask-parent/pom.xml` 的 `<modules>` 列表） |
| Spring Boot 自动装配 | 无 `spring.factories` / 自动配置类；引入本模块不会自动装配任何 Bean，全部手动 `new` 组装 |
| 数据库驱动 | JDBC 源/汇只依赖 HikariCP；MySQL、Oracle 等驱动需宿主工程自行引入 |
| 完整 CSV 解析引擎 | 定界符源按 `String.split(Pattern.quote(分隔符), -1)` 切分、首行作表头，不提供引号转义、多行字段等解析器级能力 |
| `DataSyncSource` / `DataSyncSink` 的实现 | 这两个门面接口在本模块没有内置实现，仅由 `DataSyncAgent#toSource()` / `toSink()` 默认方法暴露（默认返回 `null`） |
| 管理界面 / REST 接口 | 本模块无 Controller、无页面，仅提供编程 API |
