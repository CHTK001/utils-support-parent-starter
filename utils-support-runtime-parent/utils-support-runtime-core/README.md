# utils-support-runtime-core

> 运行时管理的**核心契约与默认实现**：把一个可运行工件（`RuntimeArtifact`）注册进管理器，即可启动、停止、重启、查状态、订阅实时日志、做健康检查，并可进一步把它安装为 Windows / systemd 系统服务，或向目标 JVM 注入 Java Agent。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.runtime.core`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 已知行为与限制](#6-已知行为与限制)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-runtime-core</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 定位

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 契约 | `RuntimeManager` / `RuntimeInstance` / `RuntimeLauncher` / `ServiceManager` / `JavaAgentManager` 五个接口，`RuntimeArtifact` / `ManagedService` / `RuntimeStatus` / `LogStream` 四个模型 | 不绑定具体部署平台或进程管理器 |
| 默认实现 | `DefaultRuntimeManager`（注册表 + 下载 + 服务/Agent 门面）、`DefaultRuntimeInstance`（`ProcessBuilder` 进程生命周期）、`WindowsServiceManager`（`sc.exe`）、`SystemdServiceManager`（`systemctl`）、`DefaultJavaAgentManager`（Attach API 反射调用） | 不含 Agent 字节码本体，不含 Tomcat / Docker 等具体启动器 |
| 依赖 | 只依赖 `utils-support-common-starter`，外加 lombok（provided）与 slf4j-api（父 POM 注入）；代码日志实际走 `java.util.logging` | 不依赖 Spring、不依赖数据库驱动、不依赖任何上层业务 starter |

规模：14 个 Java 源文件，3 个子包（`manager` / `model` / `service`）。`src/main/resources` 下仅有 GraalVM `native-image` 的 `reflect-config.json` / `proxy-config.json` / `resource-config.json`，**没有** `META-INF/extensions`——本模块不注册任何 SPI 实现。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 管理一个可运行进程 | `new DefaultRuntimeManager()` + `RuntimeArtifact.builder()` | 注册工件后 `start(id)` 拿到 `RuntimeInstance` |
| 启动 / 停止 / 重启 / 查状态 | `RuntimeManager.start` / `stop` / `restart` / `status` | 状态枚举 `RuntimeStatus`：`STOPPED` / `STARTING` / `RUNNING` / `STOPPING` / `CRASHED` / `TIMEOUT` / `UNKNOWN` |
| 实时日志 | `manager.tailLog(id, callback)` 或 `instance.logStream()` | `LogStream` 环形缓冲（默认 1000 行）+ 多订阅者，可 `tail(n)` / `getBuffer()` |
| 健康检查 | `instance.healthCheck()` | 优先 `healthCheckUrl`（HTTP 2xx~3xx 视为通过），其次 `healthCheckCommand`，否则检查进程是否存活 |
| 下载工件 | `manager.download(id, callback)` | 基于 common-starter 的 `Downloader`，支持 MD5 校验与自动解压，返回 `CompletableFuture<Boolean>` |
| 注册为系统服务 | `manager.installAsService(id, ManagedService)` | Windows 走 `sc.exe`，Linux 检测到 `systemctl` 走 systemd |
| 注入 Java Agent | `manager.attachToJvm(pid, agentPath, options)` 或 `manager.getJavaAgentManager()` | 反射调用 `com.sun.tools.attach.VirtualMachine` |
| 自定义启动方式 | 实现 SPI `RuntimeLauncher` | 按 `artifact.getType().name()` 匹配，适合 TOMCAT / DOCKER 等非默认类型 |

### 2.2 RuntimeManager（门面）

```java
public interface RuntimeManager extends AutoCloseable {
    RuntimeArtifact register(RuntimeArtifact artifact);        // 重复 id 抛 IllegalArgumentException
    RuntimeArtifact registerOrReplace(RuntimeArtifact artifact); // 先关闭旧实例再替换
    boolean unregister(String id);
    RuntimeArtifact getArtifact(String id);

    CompletableFuture<Boolean> download(String id, LineCallback callback);

    RuntimeInstance start(String id);
    RuntimeInstance stop(String id);
    RuntimeInstance restart(String id);      // 注意：见 6. 已知行为与限制
    RuntimeStatus status(String id);
    void tailLog(String id, LineCallback callback);

    List<String> getArtifactIds();
    List<RuntimeInstance> getRunningInstances();  // 仅 status == RUNNING
    RuntimeInstance getInstance(String id);

    ServiceManager getServiceManager();      // Windows / systemd 自动探测
    CmdResult installAsService(String id, ManagedService service);
    CmdResult uninstallService(String serviceName);
    CmdResult startService(String serviceName);
    CmdResult stopService(String serviceName);
    CmdResult restartService(String serviceName);
    CmdResult serviceStatus(String serviceName);
    CmdResult enableService(String serviceName);
    CmdResult disableService(String serviceName);

    JavaAgentManager getJavaAgentManager();  // 懒加载 DefaultJavaAgentManager
    CmdResult attachToJvm(int pid, Path agentPath, String options);
    Map<Integer, String> listJavaProcesses(); // 依赖 ps 命令

    @Override void close() throws Exception;  // 关闭全部实例并清空注册表
}
```

### 2.3 RuntimeInstance（单实例）

```java
public interface RuntimeInstance extends AutoCloseable {
    RuntimeArtifact artifact();
    RuntimeStatus status();
    long pid();                                  // 进程不存活时返回 -1
    CmdResult start();
    CmdResult stop();
    CmdResult restart();                         // 先 stop 再 start
    CmdResult healthCheck();
    LogStream logStream();
    CompletableFuture<CmdResult> onExit();       // 进程退出时完成
    @Override void close() throws Exception;     // stop + 关闭日志流
}
```

### 2.4 RuntimeArtifact（工件描述，builder 构建）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `name` | `String` | 唯一标识 / 显示名 |
| `type` | `RuntimeType` | `JAR` / `APPIMAGE` / `NATIVE` / `NPM` / `PYTHON` / `SCRIPT` / `DOCKER` / `TOMCAT` / `UNKNOWN` |
| `executable` | `Path` | 可执行文件 |
| `workDir` | `Path` | 工作目录 |
| `downloadUrl` / `downloadFilename` | `String` | 下载源与保存文件名 |
| `args` / `env` | `List<String>` / `Map<String,String>` | 启动参数与环境变量（默认空） |
| `startupTimeoutMs` | `long` | 启动超时，默认 `30000` |
| `healthCheckUrl` / `healthCheckCommand` | `String` | 二选一的健康探针 |
| `expectedMd5` / `autoExtract` / `extractTo` | — | 下载校验与解压 |
| `autoRestart` / `maxRestartAttempts` | `boolean` / `int` | 默认 `false` / `3`（本模块未消费，见第 6 节） |

### 2.5 LogStream（实时日志流）

环形缓冲 + 多消费者：`onLine` / `onComplete(exitCode)` / `onError(command, throwable)` 写入并转发；`subscribe(callback)` / `unsubscribe(callback)` 订阅；`getBuffer()` 取全量快照、`tail(n)` 取末尾 n 行、`clear()`、`close()`、`isClosed()`。默认容量 1000 行。

### 2.6 ServiceManager 与两个平台实现

```java
public interface ServiceManager extends AutoCloseable {
    String name();
    boolean isSupported();
    CmdResult install(ManagedService service);
    CmdResult uninstall(String serviceName);
    CmdResult start(String serviceName);
    CmdResult stop(String serviceName);
    CmdResult restart(String serviceName);
    CmdResult status(String serviceName);
    CmdResult enable(String serviceName);
    CmdResult disable(String serviceName);
    boolean isEnabled(String serviceName);
    boolean isInstalled(String serviceName);
}
```

- `WindowsServiceManager`（`name() = "windows"`）：封装 `sc create / delete / start / stop / query / config / description`，参数全部走数组避免 shell 二次解析；`startupType` 映射 `manual→demand`、`disabled→disabled`、其余→`auto`；`restart` = stop → 等 2s → start。
- `SystemdServiceManager`（`name() = "systemd"`）：生成单元文件到 `/etc/systemd/system/<name>.service`（`Type=simple`、依赖项或 `After=network.target`、`WorkingDirectory`、`User`、`Restart=on-failure` + `RestartSec`、`Environment=`、`WantedBy=multi-user.target`），写 `/tmp` 后 `cp`（失败再 `sudo cp`），随后 `daemon-reload`；`startupType=auto` 时自动 `enable`；服务名含 `/` 或 `\` 直接抛 `IllegalArgumentException`（防路径穿越）。

`ManagedService.ServiceType` 枚举：`WINDOWS_SERVICE` / `SYSTEMD` / `INIT_D` / `LAUNCHD` / `AUTO`。

### 2.7 JavaAgentManager

```java
public interface JavaAgentManager extends AutoCloseable {
    String name();
    Map<Integer, String> listPids();                 // 依赖 ps 命令
    CmdResult inspectJvm(int pid);                   // classpath + 系统属性
    CmdResult attach(int pid, Path agentPath, String options);
    CmdResult attachByPort(int port, Path agentPath, String options);
    CmdResult detach(int pid);
}
```

默认实现 `DefaultJavaAgentManager`（`name() = "default"`）通过反射调用 `com.sun.tools.attach.VirtualMachine` 的 `attach` / `loadAgent` / `getClassPath` / `getSystemProperties` / `detach`。

---

## 3. 快速开始

### 3.1 启动一个 JAR 进程

```java
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.lang.cmd.RuntimeType;
import com.chua.runtime.core.manager.DefaultRuntimeManager;
import com.chua.runtime.core.manager.RuntimeInstance;
import com.chua.runtime.core.manager.RuntimeManager;
import com.chua.runtime.core.model.RuntimeArtifact;

RuntimeArtifact artifact = RuntimeArtifact.builder()
        .id("demo")
        .name("演示服务")
        .type(RuntimeType.JAR)                                // java -jar 方式启动
        .executable(Path.of("/opt/demo/app.jar"))
        .args(List.of("--server.port=8080"))
        .healthCheckUrl("http://127.0.0.1:8080/actuator/health")
        .build();

// RuntimeManager extends AutoCloseable
try (RuntimeManager manager = new DefaultRuntimeManager()) {
    manager.register(artifact);                               // 重复 id 抛异常；可改用 registerOrReplace

    RuntimeInstance inst = manager.start("demo");             // 启动进程
    inst.status();                                            // RuntimeStatus.RUNNING
    inst.pid();                                               // 进程 PID，未存活返回 -1

    // LineCallback 三个方法均为 default，按需覆写（不是函数式接口，不能写 lambda）
    manager.tailLog("demo", new LineCallback() {
        @Override
        public void onLine(String line) {
            System.out.println(line);
        }
    });

    inst.healthCheck();                                       // HTTP 2xx~3xx 视为健康
    manager.status("demo");                                   // RUNNING
    manager.stop("demo");                                     // 优雅停止 10s 后强制停止 5s
}
```

### 3.2 注册为系统服务

```java
import com.chua.runtime.core.model.ManagedService;

ManagedService svc = ManagedService.builder()
        .serviceType(ManagedService.ServiceType.AUTO)   // Windows→服务 / Linux→systemd
        .serviceName("demo-app")
        .displayName("演示服务")
        .startupType("auto")                            // 默认 auto
        .autoRestart(true)                              // systemd 生成 Restart=on-failure + RestartSec=10
        .build();

manager.installAsService("demo", svc);   // executable 为空时自动取工件可执行文件的绝对路径
manager.startService("demo-app");
manager.serviceStatus("demo-app");
manager.uninstallService("demo-app");
```

### 3.3 注入 Java Agent

```java
import com.chua.runtime.core.service.JavaAgentManager;

JavaAgentManager agents = manager.getJavaAgentManager();
Map<Integer, String> pids = agents.listPids();          // 类 Unix 下有效
agents.attach(1234, Path.of("/opt/agent.jar"), "");     // Attach API loadAgent
agents.inspectJvm(1234);                                // 目标 JVM classpath 与系统属性
```

---

## 4. 配置项

**无。** 依据：本模块没有任何配置文件、配置绑定类或自定义系统属性约定；源码中唯一的系统属性读取是 `DefaultRuntimeManager.download` 里工作目录回退用的 `System.getProperty("user.dir")`。所有可调参数都通过 `RuntimeArtifact` / `ManagedService` 的 builder 字段在代码里显式传入。

代码内默认值（builder 默认，非外部配置）：

| 字段 | 默认值 |
| --- | --- |
| `RuntimeArtifact.startupTimeoutMs` | `30000` |
| `RuntimeArtifact.args` / `env` | 空列表 / 空 linked map |
| `RuntimeArtifact.autoExtract` / `autoRestart` | `false` / `false` |
| `RuntimeArtifact.maxRestartAttempts` | `3` |
| `ManagedService.startupType` | `"auto"` |
| `ManagedService.autoRestart` / `restartSec` | `true` / `10` |
| `LogStream` 缓冲容量 | `1000` 行 |
| 进程停止 | 优雅等待 10s，强制再等 5s |
| 健康检查超时 | 5s |

---

## 5. SPI 扩展点

### 5.1 RuntimeLauncher（唯一对外 SPI）

不同运行时类型的启动/停止差异通过 `RuntimeLauncher` SPI 扩展，避免在 `DefaultRuntimeInstance` 中硬编码：

```java
public interface RuntimeLauncher {
    String type();                                    // 对应 RuntimeType 名称，如 "TOMCAT"
    CmdResult start(RuntimeArtifact artifact);
    CmdResult stop(RuntimeArtifact artifact);
    RuntimeStatus status();

    static RuntimeLauncher find(String type) { ... }  // ServiceProvider 按名查找；空名或未注册返回 null
}
```

匹配规则：`DefaultRuntimeInstance` 在启动/停止前先执行 `RuntimeLauncher.find(artifact.getType().name())`，命中则**完全委托**给该启动器；未命中才走内置 `ProcessBuilder` 流程。

现状：本模块未注册任何实现（resources 下无 `META-INF/extensions`），在 `utils-support-parent-starter` 仓库范围内也未发现 `implements RuntimeLauncher` 的实现。即：`JAR` / `APPIMAGE` / `NATIVE` / `NPM` / `PYTHON` / `SCRIPT` 由内置流程覆盖，`DOCKER` / `TOMCAT` 等类型当前没有内置启动器，会落入 `default` 分支（直接把 `executable` 当命令执行）；如需支持，请在自己的模块实现该接口并按 common-starter 的 SPI 约定登记（`META-INF/extensions/com.chua.runtime.core.manager.RuntimeLauncher`）。javadoc 另提示容器类（Tomcat）可委托 common-starter 的 `WebContainer` 管理，本模块未内置该实现。

### 5.2 非 SPI 的内部接口

- `ServiceManager`：接口 + 两个平台实现，由 `DefaultRuntimeManager` 内部探测选择（Windows → `WindowsServiceManager`；否则 `which systemctl` 成功 → `SystemdServiceManager`；都没有 → `null`），**没有外部注入点**。
- `JavaAgentManager`：接口 + 默认实现 `DefaultJavaAgentManager`，由 `getJavaAgentManager()` 懒加载创建，同样没有注入点。可以实现该接口自行扩展，但需自己持有实例。

---

## 6. 已知行为与限制

| 项 | 说明 |
| --- | --- |
| `RuntimeManager.restart(id)` | **实例已存在时直接返回该实例，并不会先停后启**；仅当实例不存在时等价于 `start(id)`。需要真正重启请用 `RuntimeInstance.restart()`（先 `stop` 再 `start`） |
| `autoRestart` / `maxRestartAttempts` 字段 | 仅在 `RuntimeArtifact` 上定义并在 `download` 重建时透传，**本模块没有实现自动重启逻辑** |
| `listJavaProcesses()` / `listPids()` | 依赖 `ps -eo pid,comm,args` 命令并按行过滤 `java` / `sun.tools.launcher`；**Windows 上没有 `ps`，会失败并返回空 Map** |
| `attachByPort(port, ...)` | 实际把 `port` 当作 pid 原样传给 `attach`，并没有按端口查找进程 |
| `detach(pid)` | 恒返回失败（`exitCode=1`，提示 JDK 不支持 detach，需重启目标 JVM） |
| 启动判定 | `startupTimeoutMs > 0` 时等待该时长；进程在其内退出 → `CRASHED`；否则置 `RUNNING` 并异步等待退出，运行中退出 → `CRASHED` 并完成 `onExit()` |
| 日志编码 | 日志线程按平台默认字符集读取子进程输出（`Charset.defaultCharset()`），且 `redirectErrorStream(true)` 合并 stderr |
| `download(id, cb)` | 工件未配置 `downloadUrl` 时返回异常完成的 Future；文件名缺省取 URL 最后一段；目标目录缺省取 `workDir`，再缺省取 `user.dir`；成功后用下载产物回填 `executable` |
| GraalVM | `reflect-config.json` 中登记的类名为 `com.chua.runtime.core.Default*`、`RuntimeCoreManager`、`RuntimeAgentBootstrap`，与本模块实际包名 `com.chua.runtime.core.manager.*` 不一致；native 场景下需自行核对反射注册 |

---

## 7. 构建与依赖

父 POM：`com.chua:utils-support-runtime-parent:4.0.0.51`（`utils-support-parent-starter` 之下，与 `runtime-plugin` / `runtime-spy` / `runtime-apm` / `runtime-agent` / `runtime-shell` / `runtime-starter` / `runtime-protocol` 同级）。

```xml
<parent>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-runtime-parent</artifactId>
    <version>4.0.0.51</version>
</parent>
```

| 依赖 | 来源 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter`（同版本） | 本模块 pom | `CmdExecutors` / `CmdResult` / `LineCallback` / `OsFamily` / `RuntimeType`、`Downloader`、`ServiceProvider`、`Wraps`、`ReflectUtils`、`StringUtils` / `CollectionUtils` |
| `org.projectlombok:lombok`（provided） | 父 POM | `@Data` / `@Builder` |
| `org.slf4j:slf4j-api` | 父 POM | 日志门面依赖；模块代码实际使用 `java.util.logging` |

编译：`mvn -o -q compile -DskipTests`（JDK 25，`maven.compiler.release=25`，`project.build.sourceEncoding=UTF-8`）。

---

## 8. 边界：这些能力不在本模块

父 POM 对各子模块的分工描述：`core(接口)/spy(字节码)/agent(agent入口)/starter(主入口+依赖)`。

| 能力 | 所在位置 |
| --- | --- |
| Agent 入口（premain / agentmain 启动壳） | `utils-support-runtime-agent` |
| 字节码增强 / spy | `utils-support-runtime-spy` |
| 运行时协议 | `utils-support-runtime-protocol` |
| Shell 交互 | `utils-support-runtime-shell` |
| APM | `utils-support-runtime-apm` |
| 插件机制 | `utils-support-runtime-plugin` |
| 主入口与依赖聚合 | `utils-support-runtime-starter` |
| Tomcat / Docker 等具体 `RuntimeLauncher` 实现 | 本模块未提供，按第 5 节 SPI 自行扩展 |
| 通用命令执行、下载器、SPI 框架 | `utils-support-common-starter`（本模块只是使用方） |
| Spring Boot 自动装配 | 本模块不是 Spring Boot starter，无自动配置类 |
