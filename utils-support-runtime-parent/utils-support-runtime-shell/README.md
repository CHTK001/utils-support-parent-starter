# utils-support-runtime-shell

> runtime 体系的 **Telnet 字符界面（Shell）模块**：在目标 JVM 内启动一个默认只绑回环地址的 Telnet 服务器，对外提供内置命令（`help` / `status` / `info` / `threads` / `memory`，可选 `apm` / `runtime`）、Tab 补全与可注册的自定义命令；所有输出经 `Console` 以纯文本字符界面返回。自身不含 APM 采集、不含工件编排、不含 Agent 注入逻辑。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.runtime.shell`（子包 `output`、`command`、`command.builtin`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. 会话协议与线程模型](#5-会话协议与线程模型)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-runtime-shell</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 描述（`<description>`）：运行时 Shell：Telnet 服务器、命令系统、字符界面输出。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 形态 | 普通 Java 库：`TelnetServer` + `ShellSession` + `Command`/`CommandRegistry` + `Console` | 不是 Spring starter，无自动装配、无 HTTP 接口 |
| 能力 | Telnet 监听、逐字符行协议解析、Tab 补全、内置命令、自定义命令注册、CUI 格式化输出 | 不采集 APM 数据（在 `utils-support-runtime-apm`），不管理运行工件（在 `runtime-core`） |
| 安全语义 | Shell **无任何鉴权**，默认只绑 `127.0.0.1`；绑非回环地址仅记录 `WARNING`，由部署方自行保证网络隔离 | 不是对外服务；不应暴露到不可信网络 |
| 集成方式 | 可独立 `new TelnetServer().start(port)`，也可由 `runtime-starter` 的 `RuntimeBoot` 装配 | 不自带启动脚本、不内置默认端口自动监听 |

**判断口径**：本模块只回答「字符界面怎么开、命令怎么注册、一条输入怎么变成输出」；「采什么数据」「工件怎么启停」「Agent 怎么注入」都在兄弟模块。

### 1.3 规模

- Java 源文件 **12** 个：
  - `com.chua.runtime.shell`：`TelnetServer`、`ShellSession`
  - `com.chua.runtime.shell.output`：`Console`
  - `com.chua.runtime.shell.command`：`Command`、`CommandRegistry`
  - `com.chua.runtime.shell.command.builtin`：`HelpCommand`、`StatusCommand`、`InfoCommand`、`ThreadsCommand`、`MemoryCommand`、`RuntimeCommand`、`ApmCommand`
- 资源文件：仅 `META-INF/native-image/com.chua/utils-support-runtime-shell/` 下 `reflect-config.json`、`resource-config.json`、`proxy-config.json`。
- 无 `src/test` 测试目录；核心类 Javadoc 标注 `@since 4.0.0.42`，会话欢迎横幅为 `Chua Runtime Shell v4.0.0.42`。

---

## 2. 核心 API 与速查表

### 2.1 TelnetServer（服务器入口）

```java
package com.chua.runtime.shell;

public class TelnetServer {
    public TelnetServer();                    // 无 APM 集成，注册 help/status/info/threads/memory
    public TelnetServer(ApmBootstrap apm);    // 额外注册 apm 命令（apm != null 时）

    public void start(int port) throws IOException;          // 以 withBind 设置的地址启动
    public void start(String host, int port) throws IOException; // host 为空回退 127.0.0.1
    public void stop();                                       // 关闭 ServerSocket + shutdownNow 线程池
    public boolean isRunning();
    public InetAddress getLocalAddress();                     // 未启动返回 null
    public int getLocalPort();                                // 未启动返回 -1

    public TelnetServer withBind(String host);                // 供 start(int) 使用的监听地址
    public TelnetServer withMaxSessions(int maxSessions);     // 并发上限，至少 1
    public TelnetServer withIdleTimeout(long idleTimeoutMillis); // 毫秒，<=0 不限时
    public void register(Command command);                    // 注册自定义命令
    public CommandRegistry getRegistry();                     // 命令注册表（Javadoc：外部测试用）
}
```

### 2.2 Command（扩展点接口）

```java
package com.chua.runtime.shell.command;

public interface Command {
    String name();                                  // 命令名
    default String[] aliases() { return new String[0]; }
    String description();                           // 说明（help 列表展示）
    default String usage() { return name(); }       // 用法
    default List<String> complete(String[] args) { return Collections.emptyList(); } // Tab 补全候选
    int execute(String[] args, Console console);    // 执行，返回结果码
}
```

### 2.3 CommandRegistry（命令注册表）

```java
package com.chua.runtime.shell.command;

public class CommandRegistry {
    public void register(Command command);   // 按小写名注册，别名同时入别名表
    public Command find(String name);        // 名称或别名查找（不区分大小写），无则 null
    public Map<String, Command> all();       // 不可变命令映射
    public List<String> allNames();          // 命令名 + 别名（Tab 补全顶层用）
    public List<String> complete(String prefix); // 按前缀补全
    public int size();
}
```

### 2.4 Console（字符界面输出）

```java
package com.chua.runtime.shell.output;

public class Console {
    public Console(PrintWriter writer);
    public void println(String text);
    public void blank();               // 空行
    public void header(String title);  // 三行式：==== 线 / == 标题 == / ==== 线
    public void line();                // 40 个 '-'
    public void error(String message);   // 前缀 [ERROR]
    public void warn(String message);    // 前缀 [WARN]
    public void info(String message);    // 前缀 [INFO]
    public void success(String message); // 前缀 [OK]
}
```

> 类 Javadoc 宣称「支持表格、分隔线、进度条、带色输出」，当前实现提供的是 `header` / `line` 分隔线与 `[ERROR]`/`[WARN]`/`[INFO]`/`[OK]` 四种前缀；表格与进度条以各命令内 `String.format` 拼接实现，`Console` 未提供独立方法。

### 2.5 内置命令总表

| 命令 | 别名 | 说明 | 是否默认注册 |
| --- | --- | --- | --- |
| `help` | `?`、`commands` | 列出所有命令；`help <命令>` 查看单个命令的说明与用法 | ✅ |
| `status` | `stats`、`uptime` | Uptime、OS、CPU 数、堆使用/总量/上限、classpath | ✅ |
| `info` | `env`、`version` | Java/JVM 版本与提供商、操作系统、用户目录、启动时间 | ✅ |
| `threads` | `th`、`dump` | 线程列表（每线程栈深 3、CPU 时间、锁同步器数量） | ✅ |
| `memory` | `mem`、`gc` | 堆内存、内存池用量、GC 次数与累计耗时 | ✅ |
| `apm` | 动态（处理器简称） | `apm` 总览 / `apm list` / `apm status` / `apm help` / `apm <处理器> [N]` 查看最近 N 条（默认 20） | 仅当构造时传入 `ApmBootstrap` 或显式 `register` |
| `runtime` | `list`、`services` | `runtime [list\|start\|stop\|status\|log] <id>`，操作 `RuntimeManager` 管理的工件 | ❌ 需自行 `register(new RuntimeCommand(manager))` |

`exit` / `quit` 由 `ShellSession` 直接识别（输出 `[OK] 再见`），不是注册命令。`apm` 命令的动态别名与子命令来自 `ApmBootstrap.getHandlers()` 的处理器名（去掉 `-handler` 后缀），可查看 `LogHandler`、`NetHandler`、`FileHandler`、`TraceHandler`、`TransmissionHandler`、`DependencyGraphHandler`、`HandleLeakHandler` 七类数据。

### 2.6 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 启动一个 Shell | `new TelnetServer().start(4567)` | 立即返回，acceptor 在后台守护线程运行 |
| 指定监听地址 | `withBind("127.0.0.1")` 或 `start(host, port)` | 默认 `127.0.0.1`；非回环会打 `WARNING` |
| 限流与超时 | `withMaxSessions(8)`、`withIdleTimeout(300_000L)` | 超限回「会话已达上限，请稍后重试」；超时经 `setSoTimeout` 生效 |
| 注册自定义命令 | `server.register(command)` 或 `server.getRegistry().register(command)` | 实现 `Command` 接口即可 |
| 查看 APM 数据 | `new TelnetServer(apmBootstrap)`，或 `RuntimeBoot.withApmCommand()` | 无 APM 时构造器不注册 `apm` 命令 |
| 管理运行工件 | `register(new RuntimeCommand(manager))` 后会话内执行 `runtime list/start/stop/status/log` | 需传入 `RuntimeManager` |
| 查询服务器状态 | `isRunning()` / `getLocalAddress()` / `getLocalPort()` | 停止后 `stop()` |
| 自定义输出格式 | `new Console(printWriter)` | 任何 `PrintWriter`（Telnet、标准输出、测试缓冲）均可 |

---

## 3. 快速开始

### 3.1 最短可运行示例（独立启动）

```java
import com.chua.runtime.shell.TelnetServer;
import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

public class ShellMain {
    public static void main(String[] args) throws Exception {
        TelnetServer shell = new TelnetServer();      // 已内置 help/status/info/threads/memory
        shell.withBind("127.0.0.1")                   // 无鉴权，默认只绑回环
             .withMaxSessions(8)
             .withIdleTimeout(300_000L);
        shell.register(new Command() {                // 可选：自定义命令
            @Override public String name() { return "hello"; }
            @Override public String description() { return "示例命令 hello [名字]"; }
            @Override public int execute(String[] args, Console console) {
                console.success("hello, " + (args.length > 0 ? args[0] : "world"));
                return 0;
            }
        });
        shell.start(4567);                            // 启动后立即返回
        System.out.println("shell on " + shell.getLocalAddress() + ":" + shell.getLocalPort());
    }
}
```

连上后的交互形态（欢迎横幅、提示语均为源码原文）：

```text
$ telnet 127.0.0.1 4567
Chua Runtime Shell v4.0.0.42
输入 help 查看可用命令，输入 exit 退出。Tab 键可补全。

> help
> memory
> hello runtime
[OK] hello, runtime
> exit
[OK] 再见
```

### 3.2 集成 APM 查看

```java
ApmBootstrap apm = new ApmBootstrap(pluginDir);
TelnetServer shell = new TelnetServer(apm);   // 额外注册 apm 命令
shell.start(4567);
```

### 3.3 在 runtime 体系内启用（runtime-starter 的 RuntimeBoot）

`RuntimeBoot` 持有本模块的 `TelnetServer`（可通过 `getShell()` 取出）：

- 构造时 `new TelnetServer()`（不绑 APM）；
- `withShellPort(int)` 写入配置，`RuntimeConfig` 默认 `shellHost=127.0.0.1`、`shellPort=4567`；
- `withApmCommand()` 执行 `shell.register(new ApmCommand(apm))`；
- `startShell()` 以配置的 host/port 调用 `shell.start(...)`，失败仅记 `WARNING`；
- 关闭时 `shell.stop()`。

注意：`RuntimeCommand` 虽被 `RuntimeBoot` import，但当前源码并未注册它；需要 `runtime list/start/stop/status/log` 时，需自行 `shell.register(new RuntimeCommand(manager))`。

### 3.4 失败表现

| 现象 | 表现 |
| --- | --- |
| 端口被占用 | `start` 抛 `IOException`（`ServerSocket` 绑定失败） |
| 重复启动 | `running` CAS 失败，记 `WARNING`「Shell 服务器已运行」后直接返回 |
| 并发会话超限 | 新连接收到一句「会话已达上限，请稍后重试」后关闭 |
| 会话空闲超时 | `soTimeout` 触发 `IOException`，会话以 `FINE` 日志结束 |
| 未知命令 | `[ERROR] 未知命令: <名>，输入 help 查看` |
| 命令抛异常 | `[ERROR] 命令执行异常: <消息>`，会话不中断 |

---

## 4. 配置项

本模块**无配置文件**：`src/main/resources` 仅含 GraalVM native-image 三份 JSON，没有 `application.properties`、`META-INF/extensions` 等任何键值配置。所有参数均以编程方式设置：

| 参数 | 设置入口 | 默认值 |
| --- | --- | --- |
| 监听地址 | `withBind(host)` 或 `start(host, port)` | `127.0.0.1`（`start(host,…)` 传空也回退此值） |
| 监听端口 | `start(port)` / `start(host, port)` | 无默认，必须显式传入；runtime 体系中 `RuntimeConfig` 默认 `4567` |
| 最大并发会话 | `withMaxSessions(int)` | `8`（入参小于 1 时取 1） |
| 会话空闲超时 | `withIdleTimeout(long)` 毫秒 | `300_000`（5 分钟）；`<=0` 不限时 |
| accept 队列 | 无入口（`new ServerSocket(port, 16, bind)` 固定） | backlog `16` |
| 工作线程池 | 无入口（固定 `0` 核心 / `200` 最大 / `60s` 回收 / `CallerRunsPolicy`） | 源码固定 |

安全口径（源码注释原文）：「Shell 无任何鉴权，可执行 `runtime start/stop`、线程与内存查看、日志 tail，因此默认只绑回环；显式传入非回环地址时会记录告警，由部署方自行保证网络隔离。」

日志口径：`TelnetServer` / `ShellSession` 使用 JUL（`java.util.logging`）；`ApmCommand` 使用 Lombok `@Slf4j`（slf4j-api 由父 POM 提供）。

---

## 5. 会话协议与线程模型

`ShellSession.run()` 对单连接做**逐字符**解析：

```text
'\n' 或 '\r'  → 提交当前行：按空白切分，首 token 为命令名（忽略大小写），其余为参数
'\t'          → Tab 补全：顶层按 CommandRegistry.complete(prefix)；
                已输入命令名时委派 Command.complete(args)
127 / 8       → 退格：删一个字符并回显 "\b \b"
32 ~ 126      → 可打印字符：入缓冲并回显
连接关闭(-1)  → 会话结束
```

- 启动后先输出横幅 `Chua Runtime Shell v4.0.0.42` 与操作提示，再循环输出提示符 `> `。
- 命令执行异常被捕获为 `[ERROR] 命令执行异常: …`，不影响会话；命令返回码当前未被会话消费。
- 线程模型：守护线程 `runtime-shell-acceptor` 负责 `accept`；每个连接提交到守护线程 `runtime-shell` 线程池（`SynchronousQueue` + `CallerRunsPolicy`）；`Semaphore(maxSessions)` 做并发闸门，会话结束归还许可。
- `stop()` 先翻转 `running`，再关 `ServerSocket`、`shutdownNow()` 线程池。

---

## 6. SPI 扩展点

- 本模块**不定义、不注册任何 Java SPI**：`src/main/resources` 下没有 `META-INF/services`、`META-INF/extensions` 目录，扩展点就是 `Command` 接口本身——实现它并调用 `TelnetServer#register` / `CommandRegistry#register` 即可进入会话。
- `apm` 命令的子命令与别名由 `ApmBootstrap.getHandlers()`（`runtime-apm` 的 `Plugin` 列表）动态生成，本模块不做 SPI 装配。
- native-image 保真：`resource-config.json` 保留 `META-INF/services/.*` 与 `META-INF/extensions/.*` 两类资源模式，保障依赖模块的 SPI 资源在 native-image 下仍可发现；`proxy-config.json` 为空数组。
- `reflect-config.json` 登记的是 `ShellServer`、`ShellHandler`、`ShellCommand`、`AutoConfiguration` 四个类名——这些类**在当前源码中不存在**，属历史遗留配置（当前对外类为 `TelnetServer` 等，见第 1.3 节）。

---

## 7. 构建与依赖

### 7.1 依赖清单（本模块 pom）

| 依赖 | Scope | 用途（按源码 import） |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | provided | `StringUtils`、`Wraps`、`LineCallback` |
| `com.chua:utils-support-runtime-core` | provided | `RuntimeManager`、`RuntimeInstance`、`RuntimeStatus`（`runtime` 命令） |
| `com.chua:utils-support-runtime-apm` | provided | `ApmBootstrap` 与各 `*Handler`（`apm` 命令） |
| `org.projectlombok:lombok` | provided（父 POM） | `ApmCommand` 的 `@Slf4j` |
| `org.slf4j:slf4j-api` | compile（父 POM） | Lombok 生成的日志门面 |

`ApmCommand` 还引用了 `runtime-plugin` 的 `Plugin` 与 `runtime-protocol` 的 `DependencyEdge`、`TransmissionRecord`，pom 未直接声明，经 `runtime-apm` 传递引入。

### 7.2 打包

- 普通 jar，无 shade、无特殊 Manifest 条目、无 `src/test`。
- 随 jar 发布 native-image 三份配置：`reflect-config.json`、`resource-config.json`、`proxy-config.json`。

### 7.3 父模块

隶属 `com.chua:utils-support-runtime-parent:4.0.0.51`，兄弟模块：`utils-support-runtime-core`、`utils-support-runtime-plugin`、`utils-support-runtime-spy`、`utils-support-runtime-apm`、`utils-support-runtime-agent`、`utils-support-runtime-starter`、`utils-support-runtime-protocol`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 所在 |
| --- | --- |
| APM 指标采集、处理器实现、存储落库 | `utils-support-runtime-apm` |
| 运行工件编排（`RuntimeManager` / `DefaultRuntimeManager`）、`attachToJvm` 运维入口 | `utils-support-runtime-core` / `utils-support-runtime-starter` |
| Agent 注入（`premain` / `agentmain`）、fat-jar 打包 | `utils-support-runtime-agent` |
| 字节码插桩规则（`SpyTransformer` 等） | `utils-support-runtime-spy` |
| 可加密、带行协议扩展的通用 Telnet 服务端 | `utils-support-common-starter` 的 `JdkTelnetServer`（与本模块的 `TelnetServer` 是两回事） |
| 鉴权、TLS/加密通道 | 无——本模块 Shell 无鉴权，默认仅回环 |
| Web/HTTP 界面、指标查询 API、历史数据展示 | 无——只有会话内字符界面 |

本模块**不做**：不采集、不存储、不编排、不鉴权；它是 runtime 体系里「让人能敲命令看运行时状态」的那扇门。
