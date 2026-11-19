# utils-support-datasync-starter

> 数据同步服务端：Mapping 管理、调度、执行器、AgentServer 管理。
>
> 版本 `4.0.0.51` ｜ JDK `25` ｜ 包名 `com.chua.starter.datasync` ｜ 主要 API `@since 4.0.0.42`

## 目录

1. [它是什么 / 不是什么](#它是什么--不是什么)
2. [模块结构](#模块结构)
3. [核心 API 速查](#核心-api-速查)
4. [快速开始](#快速开始)
5. [配置项](#配置项)
6. [SPI 扩展点](#spi-扩展点)
7. [构建与依赖](#构建与依赖)
8. [边界（这些能力不在本模块）](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

**它是什么**

- **数据同步服务端门面**：`DataSyncServer` / `DefaultDataSyncServer` 统一管理映射（Mapping）、数据源（Source）、数据目标（Sink）、Agent 的注册与查询，并提供 `start()` / `stop()` 生命周期。
- **映射管理**：`DataSyncMappingManager` 维护映射注册表，支持按输入标识 / 输出标识检索，并在接口上声明 `createFromConfig(mappingId, config)` 由配置定义构造映射；`DefaultDataSyncMappingManager` 为其默认实现。
- **配置文件解析与目录热加载**：`ConfigFileParser` 体系内置 YAML / JSON / Properties 三种解析器；`DirectoryDataSyncMappingScanner` 首次全量扫描目录后基于 `WatchService` 监听变化热加载映射。
- **字段映射与类型转换**：`DataSyncFieldMapping`（sourceField / targetField / converter）+ `FieldMappingConverter`，内置 `DefaultFieldMappingConverter` 支持 `toString` / `toInteger` / `toLong` / `toDouble` / `toBoolean` / `toDate`（标识大小写不敏感，未识别或转换失败时返回原值）。
- **定时调度**：`SyncDataSchedulerManager` / `DefaultSyncDataSchedulerManager` 每秒轮询满足触发条件的映射并执行；触发判定优先使用映射自带的 `Trigger`，否则用 `cron` 构造 `CronTrigger`，`cron` 为空或无效时降级为固定间隔 1 分钟的 `SimpleTrigger`；支持重试、退避、连续失败熔断与内存安全 prefetch 计算。
- **响应式执行器管理**：`ExecutorManager` / `DefaultExecutorManager` 按 topic 懒创建并池化 `ReactorDataSyncExecutor`（来自 `utils-support-datasync-agent-starter`）。
- **Agent 管理**：`AgentServerManager` / `DataSyncAgentServer` 统一管理本地与远程 Agent；本地 Agent（`LocalDataSyncAgent`）直接注册回调，远程 Agent 经 `SyncServer` 消息（`sink:` / `source:` / `register:` 主题前缀）自动注册与推送。

**它不是什么**

- **不是 Spring Boot 自动配置 starter**：本模块 pom 仅依赖 `utils-support-datasync-agent-starter` 与 `snakeyaml`，资源目录仅有 `META-INF/native-image`，没有自动配置文件，也没有 `application.yml` 配置项；一切能力通过 Java API 装配。
- **不提供具体存储型 Source / Sink 的全部实现**：JDBC、CSV、TSV、分隔符等实现在同父模块的 `utils-support-datasync-agent-starter` 中，本模块只内置文件系统与 HTTP 两个 Source。
- **不包含数据库映射加载器**：源码注释中提及的 `DatabaseMappingLoader` 不在本模块源码内。
- **不提供 HTTP 管理接口或 Web 控制台**。

## 模块结构

| 包 | 内容 |
|---|---|
| `com.chua.starter.datasync` | `DataSyncServer`（总门面接口）、`DefaultDataSyncServer`（默认实现）、`ExecutorManager`、`DefaultExecutorManager` |
| `.agent` | `AgentServerManager`、`DataSyncAgentServer`、`DefaultDataSyncAgentServer`（抽象基类）、`LocalDataSyncAgent`（本地直连标记接口） |
| `.model` | `DataSyncMapping`（映射模型接口） |
| `.config` | `DataSyncConfigDefinition`、`DefaultDataSyncConfigDefinition`、`DirectoryConfigDefinition`、`FileConfigDefinition`、`TextConfigDefinition` |
| `.mapping` | `DataSyncMappingManager`、`DefaultDataSyncMappingManager`、`DataSyncFieldMapping`、`FieldMappingConverter`、`DefaultFieldMappingConverter`、`DefaultDataSyncMapping` |
| `.source` | `FileSystemDataSyncSource`（本地 JSON / YAML 文件）、`HttpAgentDataSyncSource`（HTTP 拉取远程 Agent） |
| `.scanner` | `ConfigFileParser`、`DirectoryDataSyncMappingScanner`、`YamlConfigFileParser`、`JsonConfigFileParser`、`PropertiesConfigFileParser` |
| `.scheduler` | `SyncDataSchedulerManager`、`DefaultSyncDataSchedulerManager`（内含 `SchedulerConfig`） |

## 核心 API 速查

| 你想做什么 | 入口 |
|---|---|
| 创建服务端 | `new DefaultDataSyncServer(agentServerManager)` 或 `new DefaultDataSyncServer(agentServerManager, schedulerConfig)` |
| 注册 / 注销数据源 | `server.registerSource(source)` / `server.unregisterSource(sourceId)`；查询 `getSource` / `getSources` |
| 注册 / 注销数据目标 | `server.registerSink(sink)` / `server.unregisterSink(sinkId)`；查询 `getSink` / `getSinks` |
| 注册 / 注销 Agent | `server.registerAgent(agent)` / `server.unregisterAgent(agentId)`；查询 `getAgent` / `getAgents` |
| 添加 / 移除映射 | `server.addMapping(mapping)` / `server.mappingManager().removeMapping(mappingId)` |
| 手动触发一次同步 | `server.schedulerManager().trigger(mappingId)` |
| 目录扫描 + 热加载 | `new DirectoryDataSyncMappingScanner(mappingManager, dirConfig, parsers).start()` |
| 启动 / 停止 | `server.start()` / `server.stop()` |
| 获取执行器 | `server.executorManager().getExecutor(topic)` 返回 `ReactorDataSyncExecutor` |

`DataSyncServer` 完整方法集：`mappingManager()`、`schedulerManager()`、`agentServerManager()`、`executorManager()`、`addMapping`、`registerSource` / `unregisterSource` / `getSource` / `getSources`、`registerSink` / `unregisterSink` / `getSink` / `getSinks`、`registerAgent` / `unregisterAgent` / `getAgent` / `getAgents`、`start` / `stop`。

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasync-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最小可运行示例（本地模式）

```java
import com.chua.starter.datasync.DataSyncServer;
import com.chua.starter.datasync.DefaultDataSyncServer;
import com.chua.starter.datasync.agent.DataSyncAgentServer;
import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.mapping.DefaultDataSyncMapping;
import com.chua.starter.datasync.scanner.YamlConfigFileParser;
import com.chua.starter.datasync.source.FileSystemDataSyncSource;

import java.nio.file.Path;

// 1) 创建服务端。本地模式不接 SyncServer（两个 null），
//    仅管理本地 Agent 资源；需要远程 Agent 时传入 SyncServer 实例。
DataSyncServer server = new DefaultDataSyncServer(
        new DataSyncAgentServer(null, null, "datasync-1"));

// 2) 注册数据源与数据目标。
//    Source 也可用 HttpAgentDataSyncSource，或 agent-starter 的 JdbcDataSyncAgentSource 等。
server.registerSource(new FileSystemDataSyncSource("user-source", "user-input", "./data/users.json"));
server.registerSink(mySink);   // 任意 DataSyncAgentSink 实现，如 agent-starter 的 JdbcDataSyncAgentSink

// 3) 从 YAML 解析映射配置，并注册映射。
//    注意：parse(Path) 声明抛出受检异常 Exception，调用处需 try/catch 或在方法签名上声明 throws。
YamlConfigFileParser parser = new YamlConfigFileParser();
DataSyncConfigDefinition config = parser.parse(Path.of("mappings/user-sync.yaml"));
server.addMapping(new DefaultDataSyncMapping("user-sync", config));

// 4) 启动：顺序为 执行器 → AgentServer → 调度器；stop() 按相反顺序关闭。
server.start();
```

### 3. 映射配置文件（YAML 示例）

YAML / JSON / Properties 三种解析器共用同一组键。以下键名均来自 `YamlConfigFileParser` 的解析逻辑：

```yaml
mappingId: user-sync        # 映射标识
inputId: user-input         # 输入标识
sourceId: user-source       # 数据源实例标识
outputId: user-output       # 输出标识
sinkId: user-sink           # 目标 Sink 实例标识
batch: 100                  # 批量大小，缺省 100
mappings:                   # 字段映射列表，converter 缺省 toString
  - sourceField: id
    targetField: user_id
    converter: toString
  - sourceField: age
    targetField: user_age
    converter: toInteger
params:                     # 附加参数，原样透传到映射
  table: t_user
```

说明：

- 不写 `cron` 时，映射按默认 `SimpleTrigger` 每分钟触发一次；写了 `cron` 则按 `CronTrigger` 判定；映射对象自带 `trigger()` 时优先于 `cron`。
- 配置中出现 `directoryPath`、`filePath` 或 `text` 时，解析结果分别为目录型、文件型、文本型配置（`DirectoryConfigDefinition` / `FileConfigDefinition` / `TextConfigDefinition`），否则为普通 `SimpleConfigDefinition`。
- Properties 解析器使用 flat key（如 `sourceId=user-source`）。

### 4. 目录扫描 + 热加载（可选）

```java
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.scanner.DirectoryDataSyncMappingScanner;
import com.chua.starter.datasync.scanner.JsonConfigFileParser;
import com.chua.starter.datasync.scanner.PropertiesConfigFileParser;
import com.chua.starter.datasync.scanner.YamlConfigFileParser;

import java.util.List;

// 目录型配置可同样由 YAML 解析得到（YAML 中含 directoryPath 键）：
// parse(Path) 声明抛出受检异常 Exception，调用处需 try/catch 或在方法签名上声明 throws。
DirectoryConfigDefinition dirConfig =
        (DirectoryConfigDefinition) parser.parse(Path.of("mappings/dir.yaml"));

DirectoryDataSyncMappingScanner scanner = new DirectoryDataSyncMappingScanner(
        server.mappingManager(),
        dirConfig,
        List.of(new YamlConfigFileParser(), new JsonConfigFileParser(), new PropertiesConfigFileParser()));

scanner.start();   // 首次全量扫描目录下所有映射配置，之后基于 WatchService 监听变化热加载
// ...
scanner.stop();
```

## 配置项

### Spring 配置项

**无。** 本模块不是 Spring Boot 自动配置 starter：pom 依赖中无 Spring Boot，`src/main/resources` 下仅有 `META-INF/native-image`（GraalVM 反射配置），没有 `spring.factories` / 自动配置导入文件，也没有 `@ConfigurationProperties`。所有组件通过 Java API 直接构造与装配。

### 调度器配置 `DefaultSyncDataSchedulerManager.SchedulerConfig`

构建方式：`SchedulerConfig.builder()....build()`；以下字段均带 `@Builder.Default` 默认值：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `flatMapParallelism` | `10` | flatMap 并行度 |
| `maxBufferRows` | `Long.MAX_VALUE` | 最大缓冲行数上限（设置 >0 的数值后启用上限） |
| `maxRatePerSecond` | `0` | 每秒最大请求数，>0 时启用限速 |
| `retryMaxAttempts` | `2` | 最大重试次数 |
| `retryBackoffMs` | `500` | 重试初始退避毫秒 |
| `circuitBreakerThreshold` | `10` | 熔断阈值：连续失败超过此次数后停止重试 |
| `memorySafeEnabled` | `true` | 内存安全模式：按 JVM 最大堆自动计算管线最大在飞行数，防止 OOM |
| `memoryPercent` | `30` | 内存安全计算使用的堆内存百分比 |
| `estimatedRowBytes` | `256` | 估算单行数据字节数 |
| `directDispatch` | `false` | 是否直连派发：`true` 同 JVM 内直接调用订阅者绕过 Chronicle；`false` 使用 Chronicle 磁盘派发 |

### 映射配置文件键

| 键 | 类型 | 说明 | 缺省 |
|---|---|---|---|
| `mappingId` | string | 映射标识 | 无 |
| `inputId` | string | 输入标识 | 无 |
| `sourceId` | string | 数据源实例标识 | 无 |
| `outputId` | string | 输出标识 | 无 |
| `sinkId` | string | 目标 Sink 实例标识 | 无 |
| `batch` | int | 批量大小 | `100` |
| `cronType` | string | 定时类型 | 无 |
| `cron` | string | 定时表达式（构造 `CronTrigger`） | 缺省按 `SimpleTrigger` 每分钟触发 |
| `directoryPath` | string | 目录路径（目录型配置） | 无 |
| `filePath` | string | 文件路径（文件型配置） | 无 |
| `text` | string | 文本内容（文本型配置） | 无 |
| `mappings` | list | 字段映射：`sourceField` / `targetField` / `converter` | `converter` 缺省 `toString` |
| `params` | map | 附加参数 | 空 |

### 运行期行为要点

- **一源多目标**：`DataSyncMapping.sinkIds()` 默认只含 `sinkId()`；覆盖它返回多个 sinkId 后，调度器会为每个 sink 单独订阅，源只读一次，数据同时投递给全部目标；任一 sink 缺失则整条映射跳过，避免目标间数据不一致。
- **触发判定**：优先 `mapping.trigger()` → 其次 `cron` 构造 `CronTrigger`（结果按 mappingId 缓存）→ `cron` 为空或无效时用固定 1 分钟的 `SimpleTrigger`；按上次执行时间去重，避免同一 cron 区间重复触发。
- **服务端启动顺序**：`DefaultDataSyncServer.start()` 依次启动 `executorManager` → `agentServerManager` → `schedulerManager`；`stop()` 顺序相反。`setExecutorManager` / `setMappingManager` 必须在 `start()` 之前调用。
- **AgentServer**：本地 Agent 注册后推送走 `agent.onDataReceived(data)`；Agent 不在本地注册表时经 `SyncServer` 发布到 `sink:<agentId>` 主题。`DataSyncAgentServer` 内部持有 `ReactorDataSyncExecutor(serverId, true)`。

## SPI 扩展点

| 扩展点 | 用途 | 内置 / 默认 |
|---|---|---|
| `DataSyncAgentSource` | 自定义数据读取，实现 `sourceId()` / `inputId()` / `read(params)` 后 `registerSource` 注册 | `FileSystemDataSyncSource`、`HttpAgentDataSyncSource`（本模块）；JDBC / CSV / TSV 等在 `utils-support-datasync-agent-starter` |
| `DataSyncAgentSink` | 自定义数据写入，实现 `sinkId()` / `write(flux)` / `close()` 后 `registerSink` 注册 | JDBC / TSV 等在 `utils-support-datasync-agent-starter` |
| `DataSyncAgent` / `LocalDataSyncAgent` | 自定义 Agent；`LocalDataSyncAgent` 是本地直连（不走网络）的标记接口 | 无 |
| `ConfigFileParser` | 自定义配置文件格式解析（`supports(Path)` + `parse(Path)`），作为列表传入 `DirectoryDataSyncMappingScanner` | `YamlConfigFileParser`（.yaml/.yml）、`JsonConfigFileParser`（.json）、`PropertiesConfigFileParser`（.properties） |
| `DataSyncMapping` / `DataSyncConfigDefinition` | 自定义映射 / 配置载体（接口，可自行实现） | `DefaultDataSyncMapping`、`DefaultDataSyncConfigDefinition` |
| `com.chua.common.support.task.scheduler.Trigger` | 自定义调度触发时机，通过 `DataSyncMapping.trigger()` 携带，优先于 `cron` | `CronTrigger` / `SimpleTrigger`（1 分钟） |
| `SyncDataSchedulerManager.trigger(mappingId)` | default 方法，可覆盖以支持手动触发 | `DefaultSyncDataSchedulerManager` 已实现（立即执行一次） |
| `ExecutorManager` / `DataSyncMappingManager` | 替换执行器 / 映射管理器实现（`setExecutorManager` / `setMappingManager`，须在 `start()` 前） | `DefaultExecutorManager` / `DefaultDataSyncMappingManager` |
| `FieldMappingConverter` | 字段类型转换接口，可自行实现用于自有组件 | `DefaultFieldMappingConverter`；注意 `DefaultSyncDataSchedulerManager` 当前固定使用该默认实现 |

## 构建与依赖

Maven 坐标：`com.chua:utils-support-datasync-starter:4.0.0.51`，父模块 `com.chua:utils-support-datatask-parent`。

| 依赖 | 版本 | 说明 |
|---|---|---|
| `com.chua:utils-support-datasync-agent-starter` | `4.0.0.51` | 提供 `DataSyncAgent` / `DataSyncAgentSource` / `DataSyncAgentSink` / `ReactorDataSyncExecutor` 等契约与实现 |
| `org.yaml:snakeyaml` | `2.7` | YAML 解析 |

- 编译目标：JDK 25（父 POM `java.version=25`、`maven.compiler.release=25`）。
- 本地编译（在 `utils-support-parent-starter` 仓库根目录）：`mvn -o -q compile -DskipTests`。
- 随包附带 GraalVM native-image 反射配置：`META-INF/native-image/com.chua/utils-support-datasync-starter/reflect-config.json`。
- 同目录《单元测试报告.md》记录了目录扫描、YAML / JSON / Properties 解析、字段映射与映射注册的真实启动测试结果。

## 边界（这些能力不在本模块）

| 能力 | 归属 |
|---|---|
| JDBC / CSV / TSV / 分隔符等具体 Source、Sink 实现 | `utils-support-datasync-agent-starter` |
| `ReactorDataSyncExecutor`（Chronicle 响应式分派执行器）、`SyncDataOffset` | `utils-support-datasync-agent-starter` |
| 数据库映射加载（源码注释提及的 `DatabaseMappingLoader`） | 不在本模块源码中 |
| HTTP 管理接口 / Web 控制台 | 本模块不提供 |
| Spring Boot 自动配置、`application.yml` 配置项 | 本模块不是自动配置 starter |
