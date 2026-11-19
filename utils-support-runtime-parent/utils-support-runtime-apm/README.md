# utils-support-runtime-apm

> 运行时 APM 模块：对目标进程做**字节码插桩**，采集日志、网络传输、文件操作与分布式链路追踪事件，写入可插拔的本地存储，并通过 `OtnlEngine` SPI（SPI 名 `hotspot`）提供零外部依赖的本机可观测查询。按 pom 描述，本模块「通过 agentmain 注入」。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.runtime.apm`（子包 `handler` / `storage` / `engine`）

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
    <artifactId>utils-support-runtime-apm</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 插桩 | 内置 **87 个**默认处理器（实现 `Plugin` 契约，多数同时实现 `RuntimeSpy.Interceptor`），覆盖日志、网络、文件、链路追踪与主流中间件客户端 | 不含 agent 入口：premain/agentmain 与 agent jar 组装在 `utils-support-runtime-agent` / `utils-support-runtime-starter` |
| 存储 | `ApmStorage` SPI + 内置 `NoopStorage` / `InMemoryStorage` / `SqliteStorage`，append-only、可异步批量写、支持热更新 | 不含外部数据库（JDBC `DataSource`）与 OTLP 导出实现（接口注释中规划为「独立模块」） |
| 查询 | `HotspotOtnlEngine`（SPI 名 `hotspot`）基于进程内 `TraceHandler` 输出 services / instances / endpoints / traces | 不提供指标时序、拓扑、告警、日志检索（该实现 javadoc 明确「本实现不支持」） |
| 依赖 | 只依赖 runtime 体系（plugin / spy / protocol）、`utils-support-common-starter`、ASM、sqlite-jdbc | 不依赖 Spring；不依赖任何被观测的中间件客户端（插桩按需生效） |

### 1.3 规模

- Java 源文件 **107** 个：`handler` 92、`storage` 13、`engine` 1、根包 `ApmBootstrap` 1。
- SPI 声明文件 2 个：
  - `META-INF/services/com.chua.runtime.plugin.Plugin`：登记 `LogHandler` / `NetHandler` / `FileHandler` / `TraceHandler`；
  - `META-INF/extensions/com.chua.common.support.apm.OtnlEngine`：`hotspot=com.chua.runtime.apm.engine.HotspotOtnlEngine`。
- 内置 GraalVM `native-image` 配置 3 个：`reflect-config.json`（protocol 模型与核心 handler）、`proxy-config.json`（`com.chua.runtime.plugin.Plugin`）、`resource-config.json`。
- 核心 API 自 `4.0.0.42` 起提供；`HotspotOtnlEngine` 自 `4.0.0.50` 起提供。

---

## 2. 核心 API 与速查表

### 2.1 ApmBootstrap —— 唯一启动入口

`com.chua.runtime.apm.ApmBootstrap` 负责注册并启动全部处理器，并管理存储层生命周期。

| API | 说明 |
| --- | --- |
| `new ApmBootstrap(Path pluginDir)` | 创建启动器，注册 87 个默认处理器；单个处理器 `init` 失败仅告警，不阻断其他 |
| `storageConfig(Map<String, String>)` | 启动前注入静态存储配置（键为 `apm.storage.*`），优先级高于 Agent options 与系统属性 |
| `storageConfigProvider(Supplier<Map<String, String>>)` | 注入「活配置源」，`reloadStorageConfig()` 每次重新取值；provider 抛异常按「未配置」降级 |
| `start()` | 先 `StorageManager.init(resolveStorageConfig())` 启动存储层，再逐个启动处理器；单个失败不阻断，全部失败会告警「APM 实际不工作」 |
| `stop()` | 逆序停止处理器（注销拦截规则）→ 等待 100ms 排空在途事件 → `StorageManager.shutdown()` |
| `reloadStorageConfig()` | 热更新存储配置：重新解析并与生效配置比对，有差异才切换；切换失败保留原存储 |
| `storageStatus()` | 存储运行时状态（含 `type`、`available` 及实现自定义键），用于运维自检 |
| `resolveStorageConfig()` | 按优先级解析当前应生效的 `StorageConfig` |
| `addHandler(Plugin handler)` | 注册自定义处理器，并以临时目录的 `PluginContext` 自动 `init` |
| `getHandler(Class<T>)` / `getHandlers()` | 按类型取处理器 / 全部处理器（不可修改列表） |
| `status()` / `isStarted()` | 汇总各处理器状态字符串 / 是否已启动 |
| `getGlobal()` / `getGlobalHandler(Class<T>)`（静态） | 取全局实例 / 全局指定类型处理器；未启动返回 `null` |

存储层通过静态门面 `com.chua.runtime.apm.storage.StorageManager` 管理：`init(config)`、`reconfigure(config)`（热更新）、`get()`、`register(storage)`（绕过 SPI 直接注入）、`describe()`、`shutdown()`，以及便捷写入 `appendTransmission(record)`。启动失败一律降级为 `NoopStorage`，观测组件绝不阻断宿主进程。

### 2.2 核心处理器

| 处理器 | 职责 |
| --- | --- |
| `LogHandler` | 劫持 SLF4J / JUL / APCL 日志框架与 `System.out` / `System.err` |
| `NetHandler` / `FileHandler` | 网络与文件操作事件 |
| `TraceHandler` | 对目标类 public 方法做入口/出口/异常插桩生成 Span 树；拦截 Tomcat `StandardEngineValve` / `CoyoteAdapter` 做 HTTP 请求追踪（XRebel 风格） |
| `TransmissionHandler` | 拦截 `Socket` / `ServerSocket` / `DatagramSocket` / `HttpURLConnection`；按端口推断协议（Redis=6379、MySQL=3306、ZooKeeper=2181 等），按调用栈识别客户端库 |
| `DependencyGraphHandler` / `HandleLeakHandler` / `HandleInventoryHandler` / `SystemOutCaptureHandler` | 依赖图、句柄泄漏、句柄清单、控制台输出捕获 |
| 其余 70+ 处理器 | Jedis / Lettuce / Redisson / Kafka / RocketMQ / MySQL / PostgreSQL / Oracle / SQL Server / H2 / DB2 / ClickHouse / 达梦 / 人大金仓 / Cassandra / MongoDB / Elasticsearch / gRPC / Dubbo / Tomcat / Jetty / Undertow / Netty / Spring MVC / Spring Data JPA / MyBatis / Hibernate / WebClient / OkHttp / Feign / RestTemplate 等 |
| `SoftwareDetector` | 工具类：从调用栈按包名前缀识别第三方客户端库（零版本绑定） |

应用层传输处理器统一继承 `AbstractAppHandler`：子类只声明名称、协议、软件栈、拦截点与端点构建；基类负责有界记录（`BoundedRecordList`，容量 10000）、依赖图同步与生命周期。

### 2.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 给应用挂 APM | `new ApmBootstrap(dir).start()` | 程序化启动全部内置处理器 |
| 随进程自动注入 | `-javaagent:<agent.jar>=storage.type=…` | Agent options；由 runtime-agent / runtime-starter 承接 |
| 指定落盘存储 | `storageConfig(...)` 或 `-Dapm.storage.type=sqlite` | 默认 `inmemory`（内存存储，不落盘） |
| 运行中改存储配置 | `reloadStorageConfig()` | 热更新，无需重启进程 |
| 自定义插桩处理器 | `addHandler(new MyHandler())` | 实现 `Plugin` + `RuntimeSpy.Interceptor` |
| 自定义存储 | `StorageManager.register(...)` 或 SPI | `name()` 与 `apm.storage.type` 匹配 |
| 本机链路查询（无外部后端） | `ServiceProvider.of(OtnlEngine.class).getExtension("hotspot")` | 数据来自进程内 `TraceHandler`；未挂 agent 时 `status()` 返回不可用 |
| 运维自检 | `status()` / `storageStatus()` | 处理器状态与存储状态 |

---

## 3. 快速开始

### 3.1 程序化启动（最短示例）

```java
import com.chua.runtime.apm.ApmBootstrap;
import java.nio.file.Path;
import java.util.Map;

public class ApmDemo {
    public static void main(String[] args) {
        ApmBootstrap apm = new ApmBootstrap(Path.of("plugins"))
                .storageConfig(Map.of(
                        "apm.storage.type", "sqlite",
                        "apm.storage.path", "./apm.db",
                        "apm.storage.async", "true"));
        apm.start();                      // 存储层 + 全部处理器
        try {
            // 业务代码；被插桩的调用已开始产生事件
            System.out.println(apm.isStarted());
            System.out.println(apm.storageStatus());
        } finally {
            apm.stop();                   // 逆序停处理器 → 排空 → 关存储
        }
    }
}
```

### 3.2 以 Java Agent 方式挂载

```text
java -javaagent:<agent.jar>=storage.type=sqlite,storage.path=./apm.db -jar app.jar
```

- Agent options 支持短名（`storage.type`）与全名（`apm.storage.type`）两种写法；
- 等价系统属性：`-Dapm.storage.type=sqlite -Dapm.storage.path=./apm.db`；
- agent 未挂载时，`HotspotOtnlEngine.status()` 返回不可用（提示「未检测到 hotspot agent」），业务进程不受影响。

---

## 4. 配置项

### 4.1 存储配置（`apm.storage.*`）

取值优先级（高 → 低）：**宿主活配置源（`storageConfigProvider`）> 启动注入（`storageConfig`）> Agent options > 系统属性 > 默认值**。未显式给出的 `apm.storage.*` 系统属性会原样透传进 `StorageConfig`，便于运维临时加参数。

| 键（Agent options 短名） | 默认值 | 说明 |
| --- | --- | --- |
| `apm.storage.type`（`storage.type`） | `inmemory` | 存储实现名：`noop` / `inmemory` / `sqlite`；找不到同名实现时安全退回 `noop` |
| `apm.storage.retention.ms`（`storage.retention.ms`） | `604800000`（7 天） | 数据保留期，超期由 `cleanup` 淘汰 |
| `apm.storage.capacity`（`storage.capacity`） | `100000` | 单表最大行数 |
| `apm.storage.path`（`storage.path`） | `./apm.db` | SQLite 文件路径（仅 sqlite 实现） |
| `apm.storage.async`（`storage.async`） | `true` | `true` 异步批量写；`false` 逐条同步 |
| `apm.storage.flush.timeout.ms`（`storage.flush.timeout.ms`） | 源码未提供默认值 | 强制排空写队列的最长等待 |
| `apm.storage.maintenance.interval.ms`（`storage.maintenance.interval.ms`） | 源码未提供默认值 | 按保留期/容量自动淘汰的检查间隔 |
| `apm.storage.jdbc.libs`（`storage.jdbc.libs`） | 空 | JDBC 驱动及依赖 jar 所在目录（可用 `File.pathSeparator` 分隔多个）；agent 场景下 sqlite 驱动需经隔离 ClassLoader 加载 |
| `apm.storage.jdbc.driver`（`storage.jdbc.driver`） | 空 | 指定驱动类 |
| `apm.storage.datasource` | — | 注释中预留（Spring DataSource bean name），**本模块无对应实现** |
| `apm.storage.otlp.endpoint` | — | 注释中预留（OTLP HTTP endpoint），**本模块无对应实现** |

### 4.2 处理器开关

每个处理器在 `init` 阶段从 `PluginContext` 读取自己的启用键，默认全部启用（`"true"`），命名模式为 `<组件>.enabled`，例如：`lettuce.enabled`、`mysql.enabled`、`async-http-client.enabled`。个别处理器有额外参数，如句柄泄漏阈值 `leak.threshold.ms`（系统属性，默认 `1000`）。

---

## 5. SPI 扩展点

### 5.1 处理器：`com.chua.runtime.plugin.Plugin`

- 接口来自 `utils-support-runtime-plugin`；插桩回调通过 `utils-support-runtime-spy` 的 `RuntimeSpy.Interceptor` 接入。
- JVM SPI 声明文件：`META-INF/services/com.chua.runtime.plugin.Plugin`（本模块登记 `LogHandler` / `NetHandler` / `FileHandler` / `TraceHandler`）。
- `ApmBootstrap` 另以代码注册 87 个默认处理器；自定义处理器用 `addHandler(Plugin)` 注册，应用层建议继承 `AbstractAppHandler`。

### 5.2 存储：`com.chua.runtime.apm.storage.ApmStorage`

- 契约方法：`start` / `stop` / `appendTransmission` / `appendDependency` / `appendLeak` / `appendLog` / `queryTransmissions` / `queryDependencies` / `queryLeaks` / `queryLogs` / `stats` / `cleanup` / `name` / `describe` / `flush` / `available`。
- 发现顺序：`ApmStorageClassLoader` 隔离加载器（仅 `sqlite`，解决 agent bootstrap ClassLoader 下无法链接 `java.sql` 的问题）→ `StorageManager.register()` 显式注册 → `ServiceLoader` 按 `apm.storage.type` 精确匹配 → 兜底 `NoopStorage`（不盲选第一个 SPI 实现）。
- 注册方式：在 jar 中提供 `META-INF/services/com.chua.runtime.apm.storage.ApmStorage`，或运行期 `StorageManager.register(storage)`。
- 内置实现：`NoopStorage`（`name()=noop`，`available()=false`）、`InMemoryStorage`（`inmemory`，内存 LRU + TTL）、`SqliteStorage`（`sqlite`，落盘 4 张表：`transmissions` / `dependencies` / `leaks` / `logs`；SQL 全部委托隔离加载器内的 `SqliteSession`，写入经 `SqliteWriteQueue`）。

### 5.3 可观测引擎：`com.chua.common.support.apm.OtnlEngine`

- SPI 接口与 `Otnl*` 模型定义在 `utils-support-common-starter` 的 `apm` 包。
- 本模块提供 `HotspotOtnlEngine`（`@Spi("hotspot")`），注册文件 `META-INF/extensions/com.chua.common.support.apm.OtnlEngine`：
  `hotspot=com.chua.runtime.apm.engine.HotspotOtnlEngine`。
- 能力声明：`services` / `instances` / `endpoints` / `traces`；`endpoint` 固定 `local`，模式 `agent`；数据只覆盖已跑过的方法调用 Span（环形缓冲）；指标时序 / 拓扑 / 告警 / 日志明确不支持。

---

## 6. 构建与依赖

### 6.1 依赖清单（`pom.xml`）

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-runtime-plugin` | `${project.version}`（4.0.0.51） | `Plugin` / `PluginContext` / `InterceptPoint` 契约 |
| `com.chua:utils-support-runtime-spy` | `${project.version}` | `RuntimeSpy` / `SpyBootstrap` / `InterceptContext` 插桩回调 |
| `com.chua:utils-support-runtime-protocol` | `${project.version}` | `TransmissionRecord` / `Span` / `Trace` / `DependencyEdge` 等数据模型 |
| `com.chua:utils-support-common-starter` | `${project.version}` | `Wraps`、`Json`、`StringUtils`、`ReflectUtils`、`OtnlEngine` SPI 等 |
| `org.ow2.asm:asm` | 由父 pom 管理 | 字节码插桩 |
| `org.xerial:sqlite-jdbc` | 由父 pom 管理 | SQLite 存储 |
| `org.projectlombok:lombok` | provided | 注解处理器 |

### 6.2 编译要求

- `maven.compiler.release=25`；编译器 `fork=true`，需向 javac 传入一组 `--add-exports` / `--add-opens`（`jdk.compiler` 内部包），并开启 `-parameters` 与 lombok 注解处理器。
- 仓库口径命令：`mvn -o -q compile -DskipTests`，以 `EXIT=0` 为通过标准。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| agent 入口（premain / agentmain、agent jar 组装、`plugin.agent.options` 载体） | `utils-support-runtime-agent` / `utils-support-runtime-starter` |
| 字节码插桩基础设施（`RuntimeSpy`、`SpyBootstrap`、`InterceptContext`） | `utils-support-runtime-spy` |
| `Plugin` / `PluginContext` / `InterceptPoint` 契约 | `utils-support-runtime-plugin` |
| 传输与链路数据模型（`TransmissionRecord`、`Span`、`Trace`、`DependencyEdge`、`Endpoint`、`EndpointKind`、`Protocol`、`Software`、`StatusCode`） | `utils-support-runtime-protocol` |
| `OtnlEngine` SPI 接口与 `Otnl*` 查询模型 | `utils-support-common-starter`（`com.chua.common.support.apm`） |
| 指标时序、拓扑、告警、日志检索 | 无（`HotspotOtnlEngine` 明确不支持） |
| 外部数据库（JDBC `DataSource`）与 OTLP 导出存储实现 | 无（`ApmStorage` 注释规划为「独立模块」，本模块仅内置 noop / inmemory / sqlite） |
| Spring 集成、配置中心、HTTP 查询接口 | 无（本模块不依赖 Spring，仅提供 Java API 与 SPI） |
