# utils-support-runtime-starter

> 运行时管理**主入口**：软件管理（下载/启动/停止）+ 服务注册 + Agent 注入 + 示例。它把同父工程下的 core / apm / agent / shell 四个模块聚合成一条 `RuntimeBoot` 链式 API，支持「下载 → 安装 main → 注入 agent → 启动服务 → 打开 shell」的完整流程。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.runtime.starter`

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
    <artifactId>utils-support-runtime-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 运行时管理的**聚合入口**：`RuntimeBoot` 链式 API 串起下载、启动、服务注册、Agent 注入、Shell、APM | 不自己实现管理逻辑；`RuntimeManager` 的实现 `DefaultRuntimeManager` 在 `utils-support-runtime-core` |
| 软件管理 | 通过 `RuntimeManager` 管理工件进程（启停/重启/状态/日志），可注册为系统服务 | 不提供 REST 接口，不读写数据库 |
| Agent 注入 | `attachAgent()`（注入指定 PID）、`attachSelf()`（自注入当前 JVM） | Agent 本体（`premain`/`agentmain`）在 `utils-support-runtime-agent`，本模块只负责 attach |
| APM 观测 | `startApm()` 启动采集，`withHandler(Plugin)` 追加自定义处理器 | 字节码插桩引擎（Spy）在 `utils-support-runtime-spy` |
| 示例 | 内置 `GuacamoleArtifact`，演示如何用 builder 描述一个可安装的软件工件 | 不是 Guacamole 的完整封装，只是工件描述示例 |

### 1.3 组成

本模块只有 2 个源码类，其余能力全部来自同父工程的兄弟模块：

| 类型 | 职责 |
| --- | --- |
| `RuntimeBoot` | 链式启动器主入口；内部持有 `DefaultRuntimeManager`、`ApmBootstrap`、`TelnetServer` |
| `RuntimeBoot.BootConfig` | 启动器配置（工件、服务、Agent 路径/选项、PID、Shell 端口、插件目录） |
| `GuacamoleArtifact` | Apache Guacamole 工件示例构建器（内含 `GuacamoleBuilder`） |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建启动器 | `RuntimeBoot.create()` / `create(BootConfig)` | 链式 API 起点 |
| 管理软件与服务 | `boot.getManager()` → `RuntimeManager` | 启停、重启、状态、日志、系统服务 |
| 下载工件 | `install()` | 按工件 `id` 下载，行级日志回调 |
| 注册并启动系统服务 | `withService(ManagedService)` + `startAsService()` | WINDOWS_SERVICE / SYSTEMD / INIT_D / LAUNCHD / AUTO |
| Agent 注入指定进程 | `withAgent(Path)` + `attachAgent()` | 需通过 `BootConfig.pid` 指定目标 PID，否则跳过 |
| Agent 自注入当前 JVM | `attachSelf()` | 自动定位 classpath 或 Spring Boot fat jar 里的 agent jar |
| 打开 Telnet 运维 Shell | `startShell()` | 默认 `127.0.0.1:4567`，无鉴权，默认只绑回环 |
| 启动 APM 采集 | `startApm()` / `withHandler(Plugin)` | 内置大量处理器；`withApmCommand()` 向 Shell 注册 APM 查看命令 |
| 构建 Guacamole 工件 | `GuacamoleArtifact.createDefault()` 等 | 另有 `createServer()`（guacd）、`createWeb()`（Tomcat） |
| 优雅退出 | `shutdown()` | 停 Shell、停 APM、关闭管理器 |

### 2.2 RuntimeBoot 链式方法

| 方法 | 说明 |
| --- | --- |
| `create()` / `create(BootConfig)` | 创建启动器 |
| `withArtifact(RuntimeArtifact)` | 设置工件（内部 `registerOrReplace`） |
| `withService(ManagedService)` | 设置系统服务配置 |
| `withAgent(Path)` / `withAgentOptions(String)` | 设置 Agent JAR 路径与选项（逗号分隔 `key=value`） |
| `withShellPort(int)` | 设置 Shell 端口 |
| `withApmCommand()` | 向 Shell 注册 APM 查看命令（`ApmCommand`） |
| `withHandler(Plugin)` | 注册自定义 APM 处理器 |
| `install()` | 下载已注册工件；未注册则告警跳过 |
| `attachAgent()` | 注入 Agent 到 `config.pid` 指定的 JVM；路径或 PID 缺失时告警跳过 |
| `attachSelf()` | 自注入当前 JVM；`agentPath` 未设置时按「classpath 扫描 → fat jar `BOOT-INF/lib/` 解压 → classloader 回退」三级定位 |
| `startShell()` | 启动 Telnet Shell（`shellHost:shellPort`） |
| `startApm()` | 启动 APM |
| `startAsService()` | 工件未运行则 `manager.start(id)`；有 `service` 配置则 `installAsService` + `startService` |
| `run()` | 阻塞运行；状态为 `STOPPED` 时先启动工件 |
| `shutdown()` | 依次停 Shell、停 APM、关闭管理器 |
| `getManager()` / `getApm()` / `getShell()` | 拿到底层 `RuntimeManager` / `ApmBootstrap` / `TelnetServer` |

### 2.3 RuntimeManager（`getManager()` 暴露）

`RuntimeManager` 接口位于 `com.chua.runtime.core.manager`，本模块通过 `DefaultRuntimeManager` 使用：

| 分类 | 方法 |
| --- | --- |
| 工件注册 | `register` / `registerOrReplace` / `unregister` / `getArtifact` / `getArtifactIds` |
| 下载 | `download(id, LineCallback)` 返回 `CompletableFuture<Boolean>` |
| 进程控制 | `start` / `stop` / `restart` / `status` / `getInstance` / `getRunningInstances` / `tailLog` |
| 系统服务 | `installAsService` / `uninstallService` / `startService` / `stopService` / `restartService` / `serviceStatus` / `enableService` / `disableService` |
| Agent 注入 | `attachToJvm(pid, agentPath, options)` 返回 `CmdResult`；`listJavaProcesses()` |
| 其他 | `getServiceManager()` / `getJavaAgentManager()` / `close()` |

### 2.4 GuacamoleArtifact（示例工件）

| 方法 | 产物 |
| --- | --- |
| `builder(artifactId, version)` | 获取 `GuacamoleBuilder` |
| `createDefault()` | `guacamole-1.5.5` 工件 |
| `createServer()` | `guacd-1.5.5`，类型 `NATIVE`，可执行 `/usr/local/bin/guacd`，参数 `-b 0.0.0.0 -p 4822` |
| `createWeb()` | `guacamole-web-1.5.5`，类型 `TOMCAT`，可执行 `apache-tomcat/bin/startup.sh` |

`GuacamoleBuilder` 链式项：`type(RuntimeType)`、`executable(Path)`、`workDir(Path)`、`args(String...)`、`startupTimeout(long)`（默认 60000 毫秒）、`healthCheck(String)`、`build()`。`build()` 产出的工件默认 `autoRestart(true)`、`maxRestartAttempts(3)`，`id` 为 `artifactId-version`，`name` 为 `Guacamole <artifactId> <version>`。

`RuntimeType` 枚举（来自 `com.chua.common.support.lang.cmd`）：`JAR`、`APPIMAGE`、`NATIVE`、`NPM`、`PYTHON`、`SCRIPT`、`DOCKER`、`TOMCAT`、`UNKNOWN`。

---

## 3. 快速开始

### 3.1 下载并启动一个外部软件（Guacamole）

```java
import com.chua.runtime.starter.GuacamoleArtifact;
import com.chua.runtime.starter.RuntimeBoot;

RuntimeBoot boot = RuntimeBoot.create()
        .withArtifact(GuacamoleArtifact.createDefault())  // id=guacamole-1.5.5
        .withApmCommand()        // Shell 里可执行 APM 查看命令
        .install()               // 下载工件
        .startAsService()        // 启动；有 service 配置时注册为系统服务
        .startShell();           // Telnet 运维 Shell，默认 127.0.0.1:4567

boot.run();                      // 阻塞保持运行
// 需要退出时：
boot.shutdown();
```

自定义 Guacamole 版本：

```java
RuntimeBoot.create()
        .withArtifact(GuacamoleArtifact.builder("guacamole", "1.5.5").build())
        .install()
        .run();
```

### 3.2 把 Agent 注入当前 JVM（纯 Maven 依赖、零 JVM 启动参数）

```java
import com.chua.runtime.starter.RuntimeBoot;

RuntimeBoot.create()
        .attachSelf()    // 自动定位 agent jar 并 attach 当前进程，
        .startApm();     // 触发 RuntimeAgent.agentmain → SpyBootstrap + ApmBootstrap
```

`attachSelf()` 定位不到 agent jar 时会告警跳过；此时可用 `withAgent(Path)` 显式指定路径。

### 3.3 把 Agent 注入指定进程

```java
import com.chua.runtime.starter.RuntimeBoot;
import java.nio.file.Path;

RuntimeBoot.create(
                RuntimeBoot.BootConfig.builder()
                        .pid(1234)                     // 目标 JVM 进程
                        .build())
        .withAgent(Path.of("utils-support-runtime-agent-4.0.0.51.jar"))
        .withAgentOptions("storage.type=sqlite")
        .attachAgent();
```

目标 PID 可用 `boot.getManager().listJavaProcesses()` 查询。

---

## 4. 配置项

### 4.1 BootConfig（`RuntimeBoot.BootConfig`，builder 构造）

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `artifact` | `RuntimeArtifact` | `id=default` 的空工件 | 要管理的工件 |
| `service` | `ManagedService` | 未设置（null） | 系统服务注册配置 |
| `agentPath` | `Path` | 未设置（null） | Agent JAR 路径；`attachSelf()` 未设置时会自动定位 |
| `agentOptions` | `String` | `""` | Agent 选项，逗号分隔 `key=value` |
| `pid` | `int` | `0` | 目标进程 PID；`attachAgent()` 为 0 时跳过注入 |
| `shellPort` | `int` | `4567` | Shell 端口 |
| `shellHost` | `String` | `127.0.0.1` | Shell 监听地址；该端口无鉴权，默认只绑回环，跨机访问需显式设置并自行做网络隔离 |
| `pluginDir` | `Path` | `plugins` | APM 插件目录，传给 `ApmBootstrap` |

### 4.2 Agent 参数（`-javaagent` options 或 `attachToJvm` 的 options）

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `plugins` | 插件目录路径 | 源码未提供 |
| `enabled` | 是否启用 | `true` |
| `port` | Shell 端口 | `4567` |
| `storage.type` | APM 存储实现名（`inmemory` / `sqlite` / `noop`） | `inmemory` |
| `storage.retention.ms` | 数据保留时长（毫秒） | 7 天 |
| `storage.capacity` | 存储容量上限 | `100000` |
| `storage.path` | 存储落盘目录（sqlite 等非内存实现使用） | 默认临时目录 |

存储参数同时受系统属性 `apm.storage.*` 影响，优先级：Agent 参数 > 系统属性 > 默认值。

### 4.3 APM 存储配置（`apm.storage.*`，可经系统属性或宿主注入）

| 键 | 默认值 |
| --- | --- |
| `apm.storage.type` | `inmemory` |
| `apm.storage.retention.ms` | `604800000`（7 天） |
| `apm.storage.capacity` | `100000` |
| `apm.storage.path` | 未设置（空） |
| `apm.storage.jdbc.libs` | 未设置（空；agent 场景下 sqlite-jdbc 及依赖经独立 ClassLoader 加载） |
| `apm.storage.jdbc.driver` | 未设置（空） |
| `apm.storage.async` | `true`（异步批量写入；`false` 逐条同步） |
| `apm.storage.flush.timeout.ms` | 未设置（空） |
| `apm.storage.maintenance.interval.ms` | 未设置（空） |

完整取值优先级：宿主注入（`ApmBootstrap.storageConfig(Map)` / `storageConfigProvider(Supplier)`）> Agent options > 系统属性 > 默认值。运行中可用 `ApmBootstrap.reloadStorageConfig()` 热更新，`storageStatus()` 做运维自检。

### 4.4 Telnet Shell 固定默认值（`TelnetServer` 常量）

| 项 | 值 |
| --- | --- |
| 默认端口 | `4567` |
| 默认监听地址 | `127.0.0.1`（非回环地址启动时会打告警日志） |
| 默认最大并发会话 | `8` |
| 默认会话空闲超时 | `300000` 毫秒 |

会话数与空闲超时可通过 `TelnetServer` 的 `withMaxSessions(int)` / `withIdleTimeout(long)` 调整（经 `boot.getShell()` 获取实例）。

---

## 5. SPI 扩展点

### 5.1 Plugin — APM 处理器扩展

接口：`com.chua.runtime.plugin.Plugin`，方法：`name()`、`version()`、`init(PluginContext)`、`start()`、`stop()`、`status()`、`isRunning()`。

- 注册方式：实现类放入 `META-INF/services/com.chua.runtime.plugin.Plugin`（Java SPI）。
- 接入方式：`RuntimeBoot.withHandler(Plugin)` 或 `ApmBootstrap.addHandler(Plugin)`。
- APM 模块内置 80+ 处理器（`com.chua.runtime.apm.handler.*Handler`，覆盖日志/网络/文件/链路、JDBC、Redis、Kafka、Tomcat、Spring 等组件），单个处理器初始化或启动失败不阻断其他处理器，也不阻断宿主进程。

### 5.2 Command — Shell 命令扩展

接口：`com.chua.runtime.shell.command.Command`。通过 `boot.getShell().register(command)`（即 `TelnetServer.register`）注册自定义命令。

内置命令：

| 命令类 | 说明 |
| --- | --- |
| `HelpCommand` | 帮助 |
| `StatusCommand` | 状态 |
| `InfoCommand` | 信息 |
| `ThreadsCommand` | 线程 |
| `MemoryCommand` | 内存 |
| `ApmCommand` | APM 查看（由 `withApmCommand()` 注册） |
| `RuntimeCommand` | `runtime` 命令，子命令 `list` / `start` / `stop` / `status` / `log` |

### 5.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-runtime-starter/` 内置 `resource-config.json`（保留 `META-INF/services/.*` 与 `META-INF/extensions/.*` 资源）、`reflect-config.json` 与 `proxy-config.json`。

### 5.4 RuntimeManager 替换说明

`RuntimeBoot` 内部固定使用 `new DefaultRuntimeManager()`，未做成可替换 SPI；如需替换实现，属于 `utils-support-runtime-core` 的扩展范畴。

---

## 6. 构建与依赖

模块坐标 `com.chua:utils-support-runtime-starter:4.0.0.51`，随父工程 `utils-support-runtime-parent`（其上为 `utils-support-parent-starter`）一起构建，编译级别 JDK 25，源码编码 UTF-8。

直接依赖（均为同版本 `4.0.0.51`）：

| 依赖 | 提供能力 |
| --- | --- |
| `utils-support-runtime-core` | `RuntimeManager`、`DefaultRuntimeManager`、`RuntimeArtifact`、`ManagedService`、`RuntimeStatus`、`RuntimeInstance` 等模型与接口 |
| `utils-support-runtime-apm` | `ApmBootstrap`、内置 APM 处理器与存储（`SqliteStorage` / `InMemoryStorage` / `NoopStorage`） |
| `utils-support-runtime-agent` | `RuntimeAgent`（`premain` / `agentmain` 入口） |
| `utils-support-runtime-shell` | `TelnetServer`、`ShellSession`、命令注册表与内置命令 |

父 pom 统一提供：`lombok`（provided）、`slf4j-api`。

父工程 `utils-support-runtime-parent` 全部子模块：`core`、`plugin`、`protocol`、`spy`、`apm`、`agent`、`shell`、`starter`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 实际位置 |
| --- | --- |
| `RuntimeManager` 接口与默认实现 `DefaultRuntimeManager`、工件/服务/状态模型 | `utils-support-runtime-core` |
| 字节码插桩引擎（`SpyBootstrap`、`SpyTransformer`、`RuntimeSpy`） | `utils-support-runtime-spy` |
| Agent 入口（`RuntimeAgent` 的 `premain` / `agentmain`） | `utils-support-runtime-agent` |
| APM 处理器与存储实现 | `utils-support-runtime-apm` |
| `Plugin` / `PluginContext` 插件契约 | `utils-support-runtime-plugin` |
| 具体协议实现 | `utils-support-runtime-protocol` |
| HTTP 客户端、JSON、SPI 等通用工具 | `utils-support-common-starter` |
