# utils-support-ssh-starter

> utils 体系的 **SSH/SFTP 协议集成模块**：基于 Apache MINA SSHD 提供 SSH 客户端（命令执行 / 交互式 Shell / PTY 终端 / 正反向与动态隧道）、SFTP 文件客户端、SSH Shell 服务端（`@ShellMethod` 声明式命令）、SFTP 目录轮询与远程 Java 服务管理。
>
> 版本：`4.0.0.51`｜JDK：`25`（仓库根 pom `java.version=25`，启用 `--enable-preview`）｜包名：`com.chua.ssh.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 核心 API](#3-核心-api)
  - [3.1 SshClient：SSH 客户端](#31-sshclientssh-客户端)
  - [3.2 SftpClient：SFTP 文件客户端](#32-sftpclientsftp-文件客户端)
  - [3.3 SshTunnel 与主机密钥策略](#33-sshtunnel-与主机密钥策略)
  - [3.4 SshServer：SSH Shell 服务端](#34-sshserver-ssh-shell-服务端)
  - [3.5 @ShellMethod 与命令处理](#35-shellmethod-与命令处理)
  - [3.6 SSH 进度条](#36-ssh-进度条)
  - [3.7 SftpPolledDirectory：SFTP 目录轮询](#37-sftppolleddirectorysftp-目录轮询)
  - [3.8 SshRemoteServiceManager 与 LinuxExecClient](#38-sshremoteservicemanager-与-linuxexecclient)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)
- [9. 文档索引](#9-文档索引)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ssh-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | SSH/SFTP 协议的客户端、服务端与运维工具集，基于 Apache MINA SSHD（依赖树快照 `dtrees.txt` 显示 `sshd-core`/`sshd-sftp` 为 `2.13.2`；类注释中写作 "3.x"） | 不是通用网络库，HTTP/TCP/FTP 等协议在 `utils-support-common-starter` |
| 依赖 | 仅依赖 `utils-support-common-starter`、`sshd-core`、`sshd-sftp`、`eddsa`（lombok 等为 provided） | 不依赖 Spring、不依赖数据库驱动、不依赖消息中间件 |
| 配置 | 全部通过 Builder、注解、构造参数在代码中完成 | 不含 application.yml/properties 配置文件 |
| 形态 | 普通 Java 库；`SshServer` 可用 `main` 独立启动 | 不是 Spring Boot 自动装配 starter（「starter」是本工具集的模块命名惯例） |

### 1.3 规模

- Java 源文件 **17** 个，顶层包 **6** 个：`annotations`、`client`、`directory`、`parser`、`server`、`service`。
- SPI 注册文件 **5** 个（`src/main/resources/META-INF/` 下 `extensions/` 4 个 + `services/` 1 个）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json` 与 `resource-config.json`。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 执行远程命令 | `SshClient.builder()…exec().command(cmd).execute()` | 返回 `ExecResult(exitCode, stdout, stderr)`；`stream(…)` 逐行回调、`follow(…)` 跟随不退出的命令 |
| 交互式 Shell | `ssh.shell().connect().send(cmd).readAll()` | 同步读取全部输出 |
| PTY 实时终端 | `ssh.terminal().connect()` | 宽高 / TERM 类型 / 按键 / 等待提示符 |
| 端口转发 / SOCKS5 | `ssh.forward().local(…)` / `.remote(…)` / `.dynamic(…)` | `start()` 返回 `AutoCloseable` |
| SFTP 上传下载与目录操作 | `SftpClient.create(host, user, pass)` 的 `upload()` / `download()` / `ls()` / `mkdir()` / `rm()` / `rename()` / `stat()` | 全链式 |
| 自定义 SSH 服务端 | `SshServer.builder()…` + `@ShellMethod` | 内置 `help` / `exit` / `quit` / `clear` |
| 远程 Java 服务启停 | `SshRemoteServiceManager`（SPI 名 `ssh`） | 上传 jar、systemd 安装/卸载、`kill -0` 探活 |
| SFTP 目录变更监听 | `SftpPolledDirectory` | 快照差分轮询远端目录 |

---

## 3. 核心 API

### 3.1 SshClient：SSH 客户端

`com.chua.ssh.support.client.SshClient`，实现 `AutoCloseable`。类注释示例：

```java
SshClient ssh = SshClient.builder()
    .host("192.168.1.100").port(22)
    .username("root").password("pass")
    .build();

// 执行命令
String result = ssh.exec().command("ls -la").executeAndGetOutput();

// 交互式 Shell
ssh.shell().connect().send("ls -la").readAll();

// 正向隧道（本地端口转发）: 本地 8080 → 远程 127.0.0.1:80
ssh.connect().forward().local(8080, "127.0.0.1", 80).start();

// 反向隧道（远程端口转发）: 远程 9090 → 本地 127.0.0.1:3000
ssh.connect().forward().remote(9090, "127.0.0.1", 3000).start();

// 动态隧道（SOCKS5 代理）
ssh.connect().forward().dynamic(1080).start();
```

**Builder 参数与默认值**（均为源码值）：

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `host(String)` | 必填（为空抛 `IllegalArgumentException`） | 主机地址 |
| `port(int)` | `22` | 端口 |
| `username(String)` | 必填（为空抛 `IllegalArgumentException`） | 用户名 |
| `password(String)` | 无 | 密码 |
| `privateKey(String)` | 无 | 私钥文件路径 |
| `connectTimeout(int)` | `30` | 连接超时（源码字段注释写「毫秒」，实际按秒传入 `verify(…, TimeUnit.SECONDS)`） |
| `sessionTimeout(int)` | `30` | 会话认证超时（同上，按秒使用） |
| `execTimeout(long)` | `60` | 命令执行/读取超时（秒） |
| `hostKeyPolicy(HostKeyPolicy)` | `ACCEPT_NEW` | 主机密钥校验策略 |
| `knownHostsPath(Path)` | `${user.home}/.ssh/known_hosts` | 已知主机密钥库路径 |

认证顺序：配置了 `password` 则密码认证；配置了 `privateKey` 则加载私钥（加载不到密钥对时抛 `SshClientException`，提示可能是密钥类型不支持、口令错误或缺少 eddsa 依赖）；两者都未提供时依次尝试 `~/.ssh/id_ed25519`、`id_rsa`、`id_ecdsa`、`id_dsa` 默认身份文件，仍无则尝试 none 认证。连接建立后设置 30 秒心跳（`SSH_MSG_IGNORE`）。

**操作入口**：

| 方法 | 返回 | 能力 |
| --- | --- | --- |
| `exec()` | `ExecOperation` | `command(cmd)` → `execute()` 得 `ExecResult`；`executeAndGetOutput()` / `executeAndGetExitCode()`；`stream(stdoutLine, stderrLine)` 与 `stream(lineConsumer)` 阻塞式逐行回调；`follow(lineConsumer)` 非阻塞跟随（`tail -f` 类命令），返回 `StreamHandle`，关闭即中断远端命令 |
| `shell()` | `ShellOperation` | `connect()` / `send(cmd)` / `readAll()` / `close()` |
| `terminal()` | `TerminalOperation` | PTY 实时终端：`pty(boolean)`、`width(int)`、`height(int)`、`ptyType(String)`（默认 `vt100`）、`onOutput(Consumer<String>)`、`onClose(Runnable)`、`connect()`、`send(cmd)`、`sendRaw(input)`、`sendKey(key)`（支持 `ctrl+c`、`ctrl+d`、`ctrl+z`、`enter`、`tab`）、`readBuffer()`、`readUntil(expected, timeoutMs)`、`isConnected()`、`close()` |
| `forward()` | `ForwardOperation` | `local(localPort, remoteHost, remotePort)`、`remote(remotePort, localHost, localPort)`、`dynamic(localPort)`、`bindAddress(addr)`（默认 `127.0.0.1`）、`start()` 返回 `AutoCloseable`；`getActualPort()` 回填实际绑定端口（反向隧道端口在远端分配，本地不可回填） |
| `sftp()` | `SftpClient` | 在既有连接上新开 SFTP 通道（复用当前已认证会话，不新建连接）；返回实例的 `close` 只关通道，不关会话 |
| `isConnected()` | `boolean` | 会话存在且未关闭 |
| `connect()` / `disconnect()` / `close()` | — | 连接管理；`close()` 等价 `disconnect()` |

**结果与异常**：

- `SshClient.ExecResult`：`record ExecResult(int exitCode, String stdout, String stderr)`，附 `getOutput()` 返回 stdout。
- `SshClient.SshClientException`：`RuntimeException` 子类。
- `SshClient.TunnelDefinition`：`local(...)` / `remote(...)` / `dynamic(...)` 三种静态工厂，类型枚举 `Type { LOCAL, REMOTE, DYNAMIC }`，供 `SshTunnel` 使用。

### 3.2 SftpClient：SFTP 文件客户端

`com.chua.ssh.support.client.SftpClient`，实现 `AutoCloseable`。

**工厂方法**：

- `SftpClient.builder()…build()`：独立连接。
- `SftpClient.create(host, username, password)`：等价 builder 三件套。
- `SftpClient.of(ClientSession session, String username, String host, int port)`：基于既有已认证会话构建（复用 SSH 连接，不新建连接）；`close()` 只关 SFTP 通道，会话生命周期归提供方（连接池）管理；会话未建立或已关闭时抛 `IllegalStateException`。

**Builder 参数与默认值**：`port` 默认 `22`；`connectTimeout` 默认 `30` 秒；`authTimeout` 默认 `30` 秒；`hostKeyPolicy` 默认 `ACCEPT_NEW`；`knownHostsPath` 默认 `${user.home}/.ssh/known_hosts`；另有 `password`、`privateKey`。独立链式客户端的连接/认证超时均以秒配置；SPI 适配器将 `ClientSetting` 的 connect/read 毫秒值向上换算为秒，其中 read 值用于认证等待。SFTP 文件操作级 read/write timeout 尚无单独消费实现。

**操作入口**（均为「入口方法 → 链式设参 → `exec()` 执行」结构）：

| 入口 | 链式方法 | 执行结果 |
| --- | --- | --- |
| `upload()` | `local(p)`、`remote(p)` | 上传本地文件到远程路径 |
| `download()` | `remote(p)`、`local(p)` | 下载远程文件到本地路径 |
| `ls()` | `path(p)`（默认 `.`） | `List<Map<String, Object>>`，每项含 `name` 与 `attributes` |
| `mkdir()` | `path(p)`、`recursive(boolean)` | 递归时按累积前缀逐级创建并做最终 `stat` 校验，避免假成功 |
| `rm()` | `path(p)`、`recursive(boolean)` | `recursive=true` 走 `rmdir`，否则删除文件 |
| `rename()` | `from(p)`、`to(p)` | 远端重命名 |
| `stat()` | `path(p)` | `Map`：`size`、`permissions`、`isDirectory`、`isRegularFile`、`lastModifiedTime` |

其它：`isOpen()` 判断会话是否仍打开；`disconnect()` / `close()` 释放资源（复用会话时仅关通道）；异常类型 `SftpClientException`（`RuntimeException` 子类）。

### 3.3 SshTunnel 与主机密钥策略

**SshTunnel**（`com.chua.ssh.support.client.SshTunnel`）实现 common 层的 `com.chua.common.support.network.tunnel.Tunnel`，支持正反向隧道与动态 SOCKS5 隧道：

- 构造：`new SshTunnel(SshClient, SshClient.TunnelDefinition, String bindAddress)`（bindAddress 为 null 时取 `127.0.0.1`）。
- 方法：`open()` 返回实际端口、`close()`、`getInfo()` 返回 `TunnelInfo`、`onInfo(Consumer<TunnelInfo>)` 状态回调、`isOpen()`。开启/关闭失败抛 `TunnelException`。

**HostKeyPolicy**（枚举，`com.chua.ssh.support.client.HostKeyPolicy`）：

| 枚举值 | 行为 |
| --- | --- |
| `ACCEPT_NEW` | **默认**。known_hosts 中有该主机匹配条目时比对密钥，匹配则放行；有条目但密钥不匹配时（如负载均衡多节点呈现不同密钥）记录 WARN 后放行；无条目时接受新密钥并记录 WARN（不回写 known_hosts）。整体等价 OpenSSH `StrictHostKeyChecking=accept-new` 的「接受新主机」语义 |
| `STRICT` | 只接受已记录在 known_hosts 中的主机密钥，未记录一律拒绝 |
| `INSECURE` | 接受任意主机密钥，不做校验；连接时以 ERROR 级别告警，仅限临时排障 |

**HostKeyVerifiers**（包私有工具类）：

- `defaultKnownHostsPath()`：`${user.home}/.ssh/known_hosts`（与 OpenSSH 约定一致，可复用运维已有信任库）。
- `of(policy, knownHostsPath, host, port)`：按策略装配 MINA `ServerKeyVerifier`。MINA 未显式设置 verifier 时会退化为 `AcceptAllServerKeyVerifier`（接受任意密钥，仅打 WARN），因此本模块显式装配；`ACCEPT_NEW` 为手写实现——MINA 2.20 的 `DefaultKnownHostsServerKeyVerifier` 在 `strict=false` 时存在「delegate 返回 true 仍返回 false」的 bug。`ACCEPT_NEW` 与 `STRICT` 共用同一份 known_hosts，策略可随时收紧而无需重新播种。

### 3.4 SshServer：SSH Shell 服务端

`com.chua.ssh.support.server.SshServer`，继承 `AbstractServer`，协议类型 `ProtocolType.SSH`。使用 `ShellUrlServerFilter` 做命令路由，支持 `@ShellMethod` 注解声明式注册命令。

```java
SshServer server = SshServer.builder()
    .port(7222)
    .password("secret")
    .build();
server.registerBean(new FileHandler());   // FileHandler 内方法标注 @ShellMethod
server.start();
```

**Builder 参数与默认值**：

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `port(int)` | `7222`（常量 `DEFAULT_PORT`） | 监听端口 |
| `host(String)` | `0.0.0.0` | 绑定地址 |
| `password(String)` | `""` | 连接密码；空字符串表示不验证 |
| `idleTimeout(int)` | `0` | 空闲超时（秒）。注意：当前源码仅保存该值，未应用到 MINA 会话配置 |

**启动行为**（`doStart`）：

- 主机密钥：`SimpleGeneratorHostKeyProvider`，RSA 2048；未指定持久化路径时每次启动在内存中生成。
- 显式拒绝 `ServerSetting#encrypt` 的流加密：SSH 自带传输层加密，二者冲突。
- 密码认证器：密码为空串时放行任意密码，否则严格比对；认证结果记录 INFO/WARN 日志。

**命令管理**：

| 方法 | 说明 |
| --- | --- |
| `registerBean(Object)` | 注册 Bean 并触发 `@ShellMethod` 注解解析（继承自服务端基类的能力） |
| `registerCommand(String commandName, ServerHandler handler)` | 直接注册命令处理器 |
| `registerCommand(String commandName, String description, ServerHandler handler)` | 同上（含描述） |
| `removeCommand(String commandName)` | 移除命令 |
| `getCommandNames()` | 已注册命令名列表 |

**交互式 Shell 会话**：每个客户端连接后先收到欢迎语（`Welcome to SshServer`、`Type 'help' for available commands, 'exit' to quit.`）；内置命令：`help`（打印帮助）、`exit` / `quit`（退出，回 `Bye~`）、`clear`（ANSI 清屏）；其余输入行构造 `SshCommandRequest` / `SshCommandResponse` 后交由处理器链（含 `ShellUrlServerFilter` 路由）执行。

**命令行入口**：`SshServer.main(String[])` 支持 `--port`/`-p`、`--password`/`-P`、`--host`/`-h`，缺省同 Builder 默认值。

### 3.5 @ShellMethod 与命令处理

**@ShellMethod**（`com.chua.ssh.support.annotations.ShellMethod`）：标记可被 SSH 客户端通过 Shell 协议调用的方法，可标在类（命令路径前缀）与方法（具体命令）上。

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `value()` | 必填 | 方法级=完整命令名（如 `"ls"`）；类级=路径前缀（如 `"/api"`） |
| `description()` | `""` | 命令描述，用于 help 和文档 |
| `method()` | `""` | 命令的 HTTP 方法等效值（可选） |
| `produce()` | `""` | 输出格式，指定 `ViewParser` SPI 名称；可选 `table`、`plain`、`list`、`md`、`kv`、`card`、`tree`、`barchart`、`json`，空表示自动检测 |

```java
@ShellMethod("/api")
public class FileHandler {
    @ShellMethod(value = "ls", description = "列出目录内容")
    public String ls(String[] args) { ... }
}
```

**ShellMethodServerHandlerParser**（`com.chua.ssh.support.parser`，`@Spi("shell-method")`）：实现 `ServerHandlerAnnotationParser`，优先级 `0`。从 `ObjectContext` 中查找标注 `@ShellMethod` 的 Bean 生成命令路由；类级前缀与方法级名用 `.` 连接（`@ShellMethod("/api")` + `@ShellMethod("user")` → `api.user`）。仅当过滤器为 `ShellUrlServerFilter` 时生效。

**ShellMethodServerHandler**：反射转发请求到目标 Bean 方法。支持三种方法签名：

1. 无参；
2. `String[] args`（接收命令参数）；
3. `String[] args, SshCommandResponse resp`（流式：方法自行向响应写输出，返回值不再渲染）。

返回值经 `ViewParser` SPI 渲染：`produce` 指定名称优先；否则按 `support(data)` 与 `getOrder()` 自动选择；`String` 结果原样返回。

**SshCommandRequest**（实现 `ServerRequest`）：用 common 层 `CommandLine`（关闭 help 选项）解析命令行，失败回退空白符切分。`getPath()` 返回命令名（首个位置参数）；`getParams()` 以 `"0"`、`"1"`… 为键返回参数；`getArgs()` 返回参数数组；`getUri()` / `getBodyString()` 返回原始命令行。

**SshCommandResponse**（实现 `ServerResponse`）：包装 SSH 客户端输出流。默认状态码 `200`、内容类型 `text/plain`；`setBody` / `flush` 把缓冲写入输出流；`end()` 刷新并补换行；`writeRaw(byte[])` 直写；另有 `setStatus` / `setHeader` / `setContentType` / `sendError` / `setResult` / `reset`。

### 3.6 SSH 进度条

**SshProgress**（单任务）：封装 common 层 `ProgressBar`，自动适配 SSH 输出流。

```java
@ShellMethod("download")
public void download(String[] args, SshCommandResponse res) throws Exception {
    SshProgress bar = new SshProgress(res, "下载", 100);
    for (int i = 0; i <= 100; i++) {
        bar.step();
        Thread.sleep(60);
    }
    bar.done("完成");
}
```

- 构造：`new SshProgress(response, taskName, total)` 或 `new SshProgress(response, taskName, total, maxBarLength)`（默认 80）。
- 方法：`step()`、`stepBy(n)`、`stepTo(n)`、`extraMessage(msg)`、`done()`、`done(message)`（当前与 `done()` 等价，message 未输出）、`close()`。
- 底层：`ProgressBarBuilder` + `ProgressBarStyle.ASCII`，50ms 刷新。

**SshMultiProgress**（多任务并排）：封装 `MultiProgressBar`。

```java
SshMultiProgress mp = new SshMultiProgress(res);
mp.add("任务A", 100);
mp.add("任务B", 200);
mp.stepBy(0, 1);      // 按索引步进
mp.stepBy("任务B", 1); // 按名称步进
mp.close();
```

- 构造：`new SshMultiProgress(response)` 或 `new SshMultiProgress(response, barWidth)`（默认 80）。
- 方法：`add(name, total)`（须在首次步进前调用）、`stepBy(index|name, n)`、`stepTo(index|name, value)`、`close()`；首次步进时懒初始化，未 `add` 直接步进抛 `IllegalStateException`。

**SshProgressBarConsumer**：`ProgressBarConsumer` 实现，把进度渲染结果通过 `SshCommandResponse#writeRaw` 写入 SSH 输出流，默认宽度 80。

### 3.7 SftpPolledDirectory：SFTP 目录轮询

`com.chua.ssh.support.directory.SftpPolledDirectory`，继承 common 层 `DiffPolledDirectory<SftpClient.DirEntry>`，定时对比远程 SFTP 目录的文件变更。

- 构造：`new SftpPolledDirectory(String listenPath, DirectoryPollerEnvironment environment)`，从环境读取属性：`host`、`port`（默认 `22`）、`username`、`password`。
- `start(...)`：建立 MINA SSH 连接（连接/认证各 10 秒超时）后启动轮询；轮询由 common 层执行器（如 `VirtualThreadPollerExecutor`）驱动。
- 内部：`listAndModified(path)` 用 `sftp.readDir` 列目录；`getFileName` / `getModified` 提取文件名与修改时间。
- `close()`：释放 SFTP 通道、会话与客户端。

### 3.8 SshRemoteServiceManager 与 LinuxExecClient

**SshRemoteServiceManager**（`com.chua.ssh.support.service`，`@Spi("ssh")`，4.0.0.43 起）：实现 common 层 `RemoteServiceManager`，基于 `SshClient` 与 `SftpClient` 在远程主机上对 Java 服务进行启停/重启/安装/卸载。

```java
SshRemoteServiceManager mgr = ServiceProvider.of(RemoteServiceManager.class)
        .getNewExtension("ssh");
mgr.connect(new SshConfig("192.168.1.10", 22, "root", "pass", null));
mgr.installRemote("app", "/opt/app/app.jar", "java -jar /opt/app/app.jar");
mgr.startRemote("app", "/opt/app/app.jar", "java -jar /opt/app/app.jar");
```

| 方法 | 行为 |
| --- | --- |
| `connect(SshConfig)` | 建立 SSH 连接（支持密码与私钥） |
| `disconnect()` / `isConnected()` | 断开与状态 |
| `execCommand(String)` | 同步执行远程命令，返回输出（stdout 为空回退 stderr） |
| `startRemote(serviceName, jarPath, startCmd)` | 按需上传 jar 后后台启动：`nohup <cmd> > /dev/null 2>&1 & echo $!`，返回 PID（解析失败 -1）；`startCmd` 中的 `{jar}` 替换为远程路径 |
| `stopRemote(pid, serviceName)` | `kill <pid>`，pid 无效时 `pkill -f <serviceName>` |
| `restartRemote(pid, serviceName, jarPath, startCmd)` | 先停，等 2 秒，再启 |
| `isRemoteRunning(pid)` | `kill -0 <pid>` 探活 |
| `uploadJar(localPath, remotePath)` | SFTP 上传（懒建连；先递归建远程目录） |
| `installRemote(serviceName, remoteJarPath, startCmd)` | 写 `/etc/systemd/system/<name>.service`（`Type=simple`、`Restart=on-failure`、`RestartSec=5`、`WantedBy=multi-user.target`）+ `daemon-reload` + `enable` |
| `uninstallRemote(serviceName)` | `disable` + `stop` + 删 unit + `daemon-reload` |

路径规则：默认部署目录 `/opt/sip-server`；jar 相对路径拼接到该目录，绝对路径原样使用。未连接时调用上述操作抛 `IllegalStateException`。

**LinuxExecClient**（`com.chua.ssh.support.client`）：Linux 命令执行客户端，兼容服务端指标服务 impl 的调用方式。构造入参为 common 层 `ClientSetting`（取 `host`/`port`/`username`/`password`）。连接超时与认证/命令读取超时从毫秒配置向上换算为秒；`executeCommand(command, timeoutMs)` 将该命令级毫秒超时交给 SSH 等待，单参重载使用 `ClientSetting.readTimeout`。独立的 write timeout 未映射。

---

## 4. 快速开始

### 4.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ssh-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 4.2 执行命令与 SFTP 上传

```java
// SSH 执行命令
try (SshClient ssh = SshClient.builder()
        .host("192.168.1.100").port(22)
        .username("root").password("pass")
        .build()) {
    ssh.connect();

    SshClient.ExecResult r = ssh.exec().command("ls -la").execute();
    System.out.println(r.exitCode());
    System.out.println(r.stdout());

    // 流式逐行输出
    ssh.exec().command("mvn clean install")
       .stream(line -> System.out.println(line));
}
```

```java
// SFTP 上传 / 下载 / 目录
try (SftpClient sftp = SftpClient.create("192.168.1.100", "root", "pass")) {
    sftp.connect();
    sftp.upload().local("local/app.jar").remote("/opt/app/app.jar").exec();
    sftp.mkdir().path("/opt/app/logs").recursive(true).exec();
    sftp.download().remote("/opt/app/app.jar").local("backup/app.jar").exec();
}
```

### 4.3 起一个 SSH Shell 服务端

```java
SshServer server = SshServer.builder()
        .host("0.0.0.0")
        .port(7222)
        .password("secret")
        .build();

server.registerBean(new FileHandler());   // @ShellMethod 声明的命令 Bean
server.start();
```

客户端 `ssh root@<host> -p 7222` 连入后，输入 `help` 查看内置帮助，输入注解声明的命令名（如 `api.ls`）即路由到对应 Java 方法；`exit` / `quit` 退出。

---

## 5. 配置项

**无 application 配置文件与配置键。** 依据：本模块 `src/main/resources` 下仅有 META-INF 的 SPI 注册文件与 native-image 配置，无任何 properties/yml；所有配置均通过 Builder、注解与构造参数在代码中完成。

代码级配置口径：

| 配置点 | 方式 | 默认值 |
| --- | --- | --- |
| SSH 客户端 | `SshClient.Builder` | 见 [3.1](#31-sshclientssh-客户端) 参数表 |
| SFTP 客户端 | `SftpClient.Builder` | 见 [3.2](#32-sftpclientsftp-文件客户端) |
| 主机密钥策略 / known_hosts | `hostKeyPolicy(...)` / `knownHostsPath(...)` | `ACCEPT_NEW` / `${user.home}/.ssh/known_hosts` |
| SSH 服务端 | `SshServer.Builder` | 端口 `7222`、地址 `0.0.0.0`、密码空串（不验证） |
| PTY 终端 | `terminal().pty(...).width(...).height(...).ptyType(...)` | `pty=true`、`80x24`、`vt100` |
| SFTP 目录轮询 | `DirectoryPollerEnvironment` 属性 | `host`、`port`（默认 22）、`username`、`password` |
| 服务端命令行启动 | `SshServer.main` 参数 | `--port/-p`、`--password/-P`、`--host/-h`，缺省同 Builder |

另：`SshClient.TerminalOperation.ptyType(String)` 的注释提及平台侧可通过配置项 `ssh.terminal-type` 下发终端类型——该键不是本模块读取的配置，本模块只接收 `ptyType(...)` 传入值（空值回退 `vt100`）。

---

## 6. SPI 扩展点

本模块向 common 层 SPI 体系注册以下实现（文件均位于 `src/main/resources/META-INF/`）：

| SPI 接口 | 注册名 | 实现 | 注册文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.service.RemoteServiceManager` | `ssh`（`@Spi("ssh")`） | `SshRemoteServiceManager` | `services/com.chua.common.support.service.RemoteServiceManager` |
| `com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser` | `shell-method`（`@Spi("shell-method")`） | `ShellMethodServerHandlerParser` | `extensions/com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser` |
| `com.chua.common.support.network.protocol.client.FileClient` | `SftpSpi`（实现类名去掉接口名推导） | `SftpFileClientSpi`（委托给链式 `SftpClient`） | `extensions/com.chua.common.support.network.protocol.client.FileClient` |
| `com.chua.common.support.lang.directory.PolledDirectory` | 按类名注册 | `SftpPolledDirectory` | `extensions/com.chua.common.support.lang.directory.PolledDirectory` |

说明：

- `extensions/com.chua.common.support.network.protocol.server.AbstractProtocolServer` 文件存在但内容为空，即本模块未在该扩展点注册任何实现。
- 消费方式示例：`ServiceProvider.of(RemoteServiceManager.class).getNewExtension("ssh")`、`ServiceProvider.of(ViewParser.class)`（`produce` 渲染）。
- 文件客户端按 `ServiceProvider.of(FileClient.class).getNewExtension("SftpSpi", clientSetting)` 创建；`SftpClient` 本身继续提供链式 API，不直接实现 `FileClient`。
- GraalVM native-image：`reflect-config.json` 注册 `LinuxExecClient`、`SftpClient`、`SftpPolledDirectory`、`ShellMethodServerHandlerParser` 的全量反射；`resource-config.json` 保留 `META-INF/extensions/.*` 资源。

---

## 7. 构建与依赖

模块位于 `utils-support-parent-starter/utils-support-network-parent/` 下，随仓库聚合构建：

```bash
mvn -o -q compile -DskipTests
```

依赖（pom 声明 + `dtrees.txt` 依赖树快照）：

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 同仓 `4.0.0.x` | 契约与工具层（SPI、`Tunnel`、`ProgressBar`、`CommandLine`、`ViewParser` 等） |
| `org.apache.sshd:sshd-core` | `2.13.2` | SSH 协议栈 |
| `org.apache.sshd:sshd-sftp` | `2.13.2` | SFTP 支持 |
| `net.i2p.crypto:eddsa` | `0.3.0` | **必需**：sshd-core 将其声明为 optional，不显式引入则 ssh-ed25519 私钥加载不到密钥对，认证以 "No more authentication methods available" 静默失败 |
| `org.projectlombok:lombok` | provided | 编译期 |
| `org.jetbrains:annotations`、`com.google.code.findbugs:jsr305` | provided | 注解 |

不依赖 Spring、不依赖任何数据库/消息中间件。

测试：模块附《单元测试覆盖矩阵.md》，记录 `SshClientTest` 的本地验证项（Builder 校验、链式 API 结构、三种隧道定义等，无需真实 SSH 服务器）；当前模块目录下未发现 `src/test` 测试源码（以文件清点为准）。

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| HTTP / TCP / UDP / WebSocket / FTP / SIP 等其它协议的客户端与服务端 | `utils-support-common-starter` 的 `network` 包 |
| 本地文件系统抽象与读写（`FileSystem` 等） | `utils-support-common-starter` 的 `file` 包 |
| 本地目录监听 / 轮询框架 | `utils-support-common-starter` 的 `lang.directory`（本模块只提供 SFTP 轮询实现） |
| 通用进度条实现 | `utils-support-common-starter` 的 `lang.process`（本模块是面向 SSH 输出流的适配层） |
| SSH 连接池 / 多主机连接管理 | 源码未提供；`SshClient` 为单连接实例，`sftp()` / `SftpClient.of(...)` 只复用既有会话 |
| SCP 协议 | 源码未提供；文件传输走 SFTP |
| known_hosts 的导入 / 编辑 / 清理工具 | 源码未提供；本模块仅提供校验策略与默认路径 |

---

## 9. 文档索引

- [单元测试覆盖矩阵.md](./单元测试覆盖矩阵.md) —— `SshClientTest` 覆盖项与各客户端能力覆盖范围。
