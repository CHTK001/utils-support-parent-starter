# utils-support-metrics-starter

> utils 体系的**系统指标采集 Starter**：通过 Rust native 采样器采集 CPU、内存、交换分区、磁盘、网络、进程、GPU、电池与系统负载，经 Jackson 解析为 `MetricsSnapshot`，以「轮询目录差分」方式对外提供最新快照，并可通过 `MetricsDataSyncAgent` 把指标按固定间隔推送给任意 `DataSyncAgentSink`。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.metrics.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 快速开始](#2-快速开始)
- [3. 核心 API](#3-核心-api)
- [4. 指标数据模型](#4-指标数据模型)
- [5. 配置项](#5-配置项)
- [6. 指标推送 Agent](#6-指标推送-agent)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-metrics-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 系统指标**采集与推送**：CPU / 内存 / 交换分区 / 磁盘 / 磁盘 IO / 网络 / 进程 / GPU / 电池 / 系统负载 | 不做指标的存储、查询、展示与告警 |
| 采集 | Rust native 采样器（经 `MetricsNativeSpi` SPI 接入），Java 侧只依赖契约 | 纯 Java 采集；native 实现缺失时自动降级为空实现 |
| 集成 | Spring Boot 自动配置（`MetricsAutoConfiguration`）+ DataSync Agent 推送（`MetricsDataSyncAgent`） | 不提供 HTTP 端点、管理界面或数据库表 |
| 解析 | `MetricsJsonParser`：native 输出的 snake_case JSON → camelCase 模型 | 不做二次聚合、降采样或时序计算 |

**判断口径**：本模块只负责「采到 → 解析 → 交给调用方 / 推给 sink」；数据落到哪里、怎么展示，由使用方实现。

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 取最新系统快照 | `MetricsService#getCurrentSnapshot()` | 返回 `MetricsSnapshot`，无数据时返回 `null` |
| Spring Boot 自动装配 | `MetricsAutoConfiguration` | 按 `metrics.*` 配置创建 `MetricsService` Bean（`destroyMethod = "close"`） |
| 采集 CPU / 内存 / 磁盘 / 网络 / 负载 | `MetricsSnapshot` 的 `cpuCores` / `memorySlots` / `disks` / `networks` / `load` 字段 | 默认全部开启 |
| 采集进程 / GPU / 电池 / 磁盘 IO | `MetricsSnapshot` 的 `processes` / `gpus` / `batteries` / `diskIo` 字段 | 进程采集开关默认关闭（`metrics.enable-process=false`） |
| 解析 native JSON | `MetricsJsonParser#parse(String)` | snake_case → camelCase；失败返回 `null` |
| 定时推送指标 | `MetricsDataSyncAgent` | 单线程守护调度，把快照转成统一行结构写入所有已注册 sink |
| 接入 / 替换 native 采样 | `MetricsNativeLibrary` + `MetricsNativeSpi` | 实现由 `utils-support-native-metrics` 通过 SPI 注册 |
| 释放 native 资源 | `MetricsService#close()` | 实现 `AutoCloseable`；Spring Bean 销毁时自动调用 |

---

## 2. 快速开始

### 2.1 Spring Boot（推荐）

引入依赖后，`MetricsAutoConfiguration` 自动装配 `MetricsService`：

```yaml
# application.yml
metrics:
  enabled: true          # 是否启用指标模块
  interval-ms: 1000      # native 内部采样间隔（毫秒）
  enable-process: false  # 进程采集默认关闭，按需打开
```

```java
import com.chua.metrics.support.MetricsService;
import com.chua.metrics.support.MetricsSnapshot;

@Autowired
private MetricsService metricsService;

MetricsSnapshot snapshot = metricsService.getCurrentSnapshot();
if (snapshot != null) {
    snapshot.getCpuCores().forEach(c ->
            System.out.println("cpu-" + c.getId() + " 使用率=" + c.getUsage() + "%"));
    snapshot.getMemorySlots().forEach(m ->
            System.out.println("slot-" + m.getSlot() + " 已用=" + m.getUsed() + "B"));
    if (snapshot.getLoad() != null) {
        System.out.println("1 分钟负载=" + snapshot.getLoad().getLoad1());
    }
}
```

启动日志：`Metrics 模块已启动，采样间隔={}ms`；`metrics.enabled=false` 时输出 `Metrics 模块已禁用`，且不创建 Bean。

### 2.2 非 Spring 环境

与自动配置等价的手工装配（轮询间隔取采样间隔的一半，事件为 `WatcherEvent.ALL_KIND`）：

```java
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.metrics.support.MetricsService;
import com.chua.metrics.support.MetricsSnapshot;

import java.util.Set;
import java.util.concurrent.TimeUnit;

try (MetricsService service = new MetricsService(1000)) {   // 参数为 native 采样间隔（毫秒）
    Set<WatcherEvent> events = Wraps.set();
    events.add(WatcherEvent.ALL_KIND);
    service.start(new DirectoryPollerEnvironment(events, 500, TimeUnit.MILLISECONDS));

    MetricsSnapshot snapshot = service.getCurrentSnapshot();
    // ...
}   // close() 会停止采样器并释放 native 资源
```

> native 库不可用时，构造 `MetricsService` 只会打 warn 日志（`metrics 原生库不可用，指标采集降级为空实现；请自行添加依赖 com.chua:utils-support-native-metrics:4.0.0.43`），不会抛异常；此时 `getCurrentSnapshot()` 返回 `null`。

### 2.3 指标推送 Agent

```java
import com.chua.metrics.support.MetricsDataSyncAgent;

MetricsDataSyncAgent agent = new MetricsDataSyncAgent("metrics-1", metricsService, 1000);
agent.addSink(mySink);   // DataSyncAgentSink 由使用方实现
agent.start();           // 单线程守护线程，线程名 metrics-push-metrics-1
// ...
agent.stop();            // 最多等待 2 秒收尾
```

---

## 3. 核心 API

所有类均在 `com.chua.metrics.support` 包下（作者 `CH`；多数类型 `@since 4.0.0.42`，`MetricsNativeLibrary` 为 4.0.0.50、`MetricsDataSyncAgent` 为 4.0.0）。

| 类型 | 说明 |
| --- | --- |
| `MetricsService` | 指标服务，继承 common-starter 的 `DiffPolledDirectory<SnapshotWrapper>`，实现 `AutoCloseable`；内部持有 native 门面与 JSON 解析器 |
| `MetricsService.SnapshotWrapper` | 快照包装：`MetricsSnapshot snapshot` + `long timestamp`（取自快照时间戳） |
| `MetricsJsonParser` | JSON 解析器：`parse(String json)` → `MetricsSnapshot`；忽略未知字段，snake_case ↔ camelCase 自动映射；失败返回 `null` |
| `MetricsNativeLibrary` | native 门面（包私有）：`create()` / `start(long)` / `poll()` / `close()`，全部委托给 `MetricsNativeSpi` |
| `MetricsAutoConfiguration` | Spring Boot 自动配置：启用 `MetricsProperties`，创建并启动 `MetricsService` Bean |
| `MetricsProperties` | 配置属性，前缀 `metrics`（见第 5 节） |
| `MetricsDataSyncAgent` | 指标推送 Agent，继承 `AbstractDataSyncAgent`（见第 6 节） |
| `MetricsSnapshot` 及各子模型 | 指标数据模型（见第 4 节） |

`MetricsService` 的公开方法：

| 方法 | 说明 |
| --- | --- |
| `new MetricsService(long intervalMs)` | 创建服务并启动 native 采样器；native 缺失时降级为空实现 |
| `start(DirectoryPollerEnvironment environment)` | 启动轮询（使用默认执行器，即 `super.start(environment, null)`） |
| `getCurrentSnapshot()` | 取当前最新快照；native 缺失、JSON 为空或解析失败时返回 `null` |
| `close()` | 停止 native 采样器并关闭轮询目录 |
| `isDelegatedOperatingSystem()` | 固定返回 `false`（覆盖自父类） |

---

## 4. 指标数据模型

### 4.1 MetricsSnapshot

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `timestamp` | `long` | Unix 时间戳（毫秒） |
| `cpuCores` | `List<CpuCore>` | CPU 核心指标列表 |
| `memorySlots` | `List<MemorySlot>` | 内存插槽指标列表 |
| `swapTotal` / `swapUsed` | `long` | 交换分区总容量 / 已用容量（字节） |
| `disks` | `List<DiskInfo>` | 磁盘信息列表 |
| `diskIo` | `List<DiskIo>` | 磁盘 IO 列表 |
| `networks` | `List<NetworkInterface>` | 网络接口列表 |
| `processes` | `List<ProcessInfo>` | 进程信息列表（按 CPU 使用率排序的前 N 条） |
| `gpus` | `List<GpuInfo>` | GPU 信息列表 |
| `batteries` | `List<BatteryInfo>` | 电池信息列表 |
| `load` | `SystemLoad` | 系统负载信息（JSON 字段 `system_load`） |

### 4.2 子模型字段

| 模型 | 字段（类型） |
| --- | --- |
| `CpuCore` | `id`(int, 从 0 开始)、`usage`(float, 0-100%)、`frequency`(long, Hz)、`name`(String, 型号) |
| `MemorySlot` | `slot`(int, 从 0 开始)、`total` / `used` / `available`(long, 字节)、`memoryType`(String, 如 DDR4/DDR5) |
| `DiskInfo` | `name`(String, 如 sda/nvme0n1)、`mountPoint`(String, 挂载点)、`total` / `used` / `available`(long, 字节)、`fileSystem`(String, 如 ext4/ntfs/apfs) |
| `DiskIo` | `name`(String)、`readBytes` / `writeBytes` / `readCount` / `writeCount`(long, 自启动以来累计) |
| `NetworkInterface` | `name`(String, 如 eth0/wlan0)、`receivedBytes` / `transmittedBytes` / `receivedPackets` / `transmittedPackets`(long, 自启动以来累计)、`errorsIn` / `errorsOut`(int, 累计) |
| `ProcessInfo` | `pid`(int)、`name`(String)、`cpuUsage`(float, 0-100%)、`memoryBytes`(long)、`threadCount`(int) |
| `GpuInfo` | `id`(int, 从 0 开始)、`name`(String)、`usage`(float, 0-100%)、`memoryUsed` / `memoryTotal`(long, 字节)、`temperature`(Float, 摄氏度，可选) |
| `BatteryInfo` | `index`(int, 从 0 开始)、`chargePercent`(int, 0-100)、`status`(String, charging/discharging/完整/unknown 等)、`timeRemainingMinutes`(Integer, 仅 discharging 状态有效)、`pluggedIn`(boolean) |
| `SystemLoad` | `load1` / `load5` / `load15`(double, 1/5/15 分钟平均负载；JSON 字段 `load_1` / `load_5` / `load_15`) |
| `MetricsTable` | `timestamp`(long)、`table`(String, 指标类型名，如 cpu、内存、disk)；用于表格化展示单个指标项 |

### 4.3 JSON 命名映射

native（Rust serde_json）输出 **snake_case**（如 `cpu_cores`、`mount_point`、`received_bytes`），Java 模型为 camelCase；`MetricsJsonParser` 统一按 `PropertyNamingStrategies.SNAKE_CASE` 映射，并关闭 `FAIL_ON_UNKNOWN_PROPERTIES`（未知字段忽略）。`SystemLoad` 的 `load` → `system_load`、`load1/load5/load15` → `load_1/load_5/load_15` 为显式 `@JsonProperty` 指定。

---

## 5. 配置项

配置前缀 `metrics`（`MetricsProperties`，`@ConfigurationProperties(prefix = "metrics")`），元数据见 `META-INF/spring-configuration-metadata.json`：

| 项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `metrics.enabled` | Boolean | `true` | 是否启用 metrics 模块 |
| `metrics.interval-ms` | Long | `1000` | native 内部采样间隔（毫秒）；`<= 0` 时按 1000 处理 |
| `metrics.enable-cpu` | Boolean | `true` | 是否启用 CPU 指标采集 |
| `metrics.enable-memory` | Boolean | `true` | 是否启用内存指标采集 |
| `metrics.enable-disk` | Boolean | `true` | 是否启用磁盘指标采集 |
| `metrics.enable-network` | Boolean | `true` | 是否启用网络指标采集 |
| `metrics.enable-process` | Boolean | `false` | 是否启用进程指标采集 |
| `metrics.enable-gpu` | Boolean | `true` | 是否启用 GPU 指标采集 |
| `metrics.enable-battery` | Boolean | `true` | 是否启用电池指标采集 |
| `metrics.enable-load` | Boolean | `true` | 是否启用系统负载采集 |

> 依据源码：本模块内实际消费的是 `metrics.enabled`（是否创建 Bean）与 `metrics.interval-ms`（native 采样间隔，轮询间隔取其一半）；其余 `enable-*` 开关为 `MetricsProperties` 中声明的配置项，源码未提供本模块内的其它消费逻辑。

---

## 6. 指标推送 Agent

`MetricsDataSyncAgent extends AbstractDataSyncAgent`（来自 `utils-support-datasync-agent-starter`），按固定间隔读取 `MetricsService` 快照并写入所有已注册 sink。

| 能力 | 说明 |
| --- | --- |
| 构造 | `new MetricsDataSyncAgent(String agentId, MetricsService metricsService, long intervalMs)`；`intervalMs <= 0` 按 1000 处理 |
| 启动 | `start()`：CAS 保证幂等；单线程守护调度器，线程名 `metrics-push-{agentId}`，按 `intervalMs` 固定频率推送 |
| 停止 | `stop()`：关闭调度器，等待在途任务最多 2 秒后 `shutdownNow` |
| 状态 | `isRunning()` |
| 注册 sink | `addSink(DataSyncAgentSink)` |
| 异常 | 推送异常仅 warn 日志，不中断调度 |

内部源 `MetricsAgentSource`（实现 `DataSyncAgentSource` + `Directional`）：`sourceId = agentId + ":metrics"`、`inputId = "metrics"`、`direction = Direction.INPUT`；`read(params)` 返回 Reactor `Flux<Map<String, Object>>`，Agent 已关闭或快照缺失时为空流。

行结构（统一键）：`timestamp`、`metric_type`、`name`（为空时省略）+ 各指标键：

| metric_type | 指标键 |
| --- | --- |
| `cpu`（每核心，name=`cpu-{id}`） | `usage`、`frequency` |
| `memory`（每插槽，name=`memory-{slot}`） | `total`、`used`、`available` |
| `swap`（无 name） | `total`、`used` |
| `disk`（name=磁盘名） | `total`、`used`、`available`、`mount_point`、`file_system` |
| `network`（name=接口名） | `rx_bytes`、`tx_bytes`、`rx_packets`、`tx_packets` |
| `load`（无 name） | `load_1m`、`load_5m`、`load_15m` |

> 推送行不包含 `diskIo`、`processes`、`gpus`、`batteries`；这些数据请直接从 `MetricsSnapshot` 读取。

SPI 登记：`META-INF/extensions/com.chua.datasync.agent.support.DataSyncAgent` → `com.chua.metrics.support.MetricsDataSyncAgent`，即本 Agent 作为 `DataSyncAgent` 的 SPI 实现被 datasync-agent 体系发现；GraalVM native-image 的 `reflect-config.json` 已为其注册反射元数据，`resource-config.json` 保留 `META-INF/extensions/.*` 资源。

---

## 7. SPI 扩展点

### 7.1 native 采样：`MetricsNativeSpi`

native 采样能力由 common-starter 的契约接口 `com.chua.common.support.nativeapi.MetricsNativeSpi`（继承 `NativeService`）定义，对应 native 侧 `metrics_native` 的三个原语：

| 方法 | 说明 |
| --- | --- |
| `start(long intervalMs)` | 启动采样器 |
| `poll()` | 拉取当前快照，返回 JSON 字符串；未启动或失败返回 `null` |
| `close()` | 停止采样器（幂等） |

查找入口为 `com.chua.common.support.nativeapi.NativeServices`：

- `NativeServices.get(MetricsNativeSpi.class)`：按 SPI 查找可用实现；未引入 native 制品、加载失败或实现自报不可用时返回 `null`（不向业务抛 `NoClassDefFoundError`）；
- `NativeServices.require(type, capability)`：缺失时抛 `IllegalStateException`；
- `NativeServices.available(type)`：能力探测。

实现由 `utils-support-native-metrics` 模块注册（对应 native 库 `metrics_native`）；该模块为外部制品，本仓库源码未提供。

### 7.2 `DataSyncAgent` SPI

`MetricsDataSyncAgent` 通过 `META-INF/extensions/com.chua.datasync.agent.support.DataSyncAgent` 登记（内容为一行全限定类名），可作为 datasync-agent 体系的扩展实现被加载。

---

## 8. 构建与依赖

```bash
mvn -pl utils-support-extra-parent/utils-support-metrics-starter -am clean package
```

编译要求与全仓一致：JDK 25（`<release>25</release>`，`--enable-preview`）、源码 UTF-8 无 BOM、Lombok 注解处理。

| 依赖 | 版本 / 范围 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 4.0.0.51 / compile | `DiffPolledDirectory`、`Wraps`、`NativeServices` / `MetricsNativeSpi`、`StringUtils` 等 |
| `com.chua:utils-support-datasync-agent-starter` | 4.0.0.51 / compile | `AbstractDataSyncAgent`、`DataSyncAgentSource` / `DataSyncAgentSink`、`Direction` |
| `com.fasterxml.jackson.core:jackson-databind` | compile | 快照 JSON 解析 |
| `org.springframework.boot:spring-boot-autoconfigure` | compile | `@AutoConfiguration`、`@ConfigurationProperties` |
| `org.projectlombok:lombok` | provided | 编译期注解处理 |
| `jakarta.annotation-api` | provided | 注解 |
| `com.chua:utils-support-native-metrics` | 运行期可选 | `MetricsNativeSpi` 的实现（Rust native 采样器）；未引入时本模块降级为空实现 |

自动配置登记：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` → `com.chua.metrics.support.MetricsAutoConfiguration`。

---

## 9. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| Rust native 采样器实现（`metrics_native`） | 由外部制品 `utils-support-native-metrics` 提供，本仓源码未提供；缺失时自动降级为空实现 |
| 指标的存储、查询、聚合、可视化、告警 | 源码未提供；本模块只负责采集与推送 |
| `DataSyncAgentSink` 的具体落地（写库、写消息队列等） | 由使用方实现并注册 |
| 目录轮询 / 差分、SPI、JSON 等通用底层能力 | 属于 `utils-support-common-starter`（`lang.directory`、`spi` 等） |
| HTTP 端点、管理页面 | 源码未提供 |
