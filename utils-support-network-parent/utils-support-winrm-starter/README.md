# utils-support-winrm-starter

> WinRM（Windows Remote Management / WS-Management，SOAP over HTTP）协议集成：对远程 **Windows** 主机执行命令、交互式 Shell / 伪终端、文件上传下载，以及 Java 服务的远程启停、安装、卸载。协议栈基于 `io.cloudsoft.windows:winrm4j` / `winrm4j-client`（0.12.3）。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承仓库根 `java.version=25`）｜包名：`com.chua.winrm.support`（子包 `client` / `service`）

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
    <artifactId>utils-support-winrm-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

所属父模块：`utils-support-network-parent`（网络协议族，与 `utils-support-ssh-starter`、`utils-support-socketio-starter` 等同级）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | WinRM 协议**客户端**：命令执行、Shell / 伪终端、文件操作、远程服务管理 | 不是 Windows 主机侧的被管程序 / Agent，也不含 WinRM 服务端 |
| 抽象实现 | `FileClient`（文件操作）与 `RemoteServiceManager`（远程服务管理）两个 SPI 的 WinRM 实现 | 不是 SSH / SFTP 实现（见 `utils-support-ssh-starter`） |
| 依赖 | 只依赖 `utils-support-common-starter` + winrm4j + JAXB，不依赖 Spring | 不绑定任何业务 starter，不持有业务表 |

### 1.3 规模

- Java 源文件 **5** 个：`WinRmExecClient`、`WinRmFileClient`、`WinRmFileClientSpi`、`WinRmRemoteServiceManager`、`WinRMException`。
- SPI 登记文件 2 个（`META-INF/extensions/` 与 `META-INF/services/` 各 1 个）。
- 内置 GraalVM `native-image` 的 `reflect-config.json`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 执行远程命令 | `WinRmExecClient.builder()…build().connect()` → `exec().command(cmd).execute()` | 返回 `ExecResult(exitCode, stdout, stderr)` |
| 只要 stdout | `exec().command(cmd).executeAndGetOutput()` | 等价于 `execute().stdout()` |
| 只要退出码 | `exec().command(cmd).executeAndGetExitCode()` | 等价于 `execute().exitCode()` |
| 交互式 Shell | `shell().connect().send(cmd)` | 同一 Shell 内连续执行多条命令 |
| 伪终端（实时回调） | `terminal().onOutput(cb).connect().send(cmd).readBuffer()` | 基于单次命令模拟，stdout+stderr 合并回调 |
| 文件列表 / 读文件 | `WinRmFileClient.listFiles(path)` / `readFile(path)` | 实现 `FileClient` SPI |
| 文件上传 / 下载 | `WinRmFileClient.uploadFile(in, path)` / `downloadFile(path, out)` | PowerShell + Base64 传输 |
| 远程 Java 服务启停 | `WinRmRemoteServiceManager.startRemote / stopRemote / restartRemote` | 实现 `RemoteServiceManager` SPI（`@Spi("winrm")`） |

### 2.2 WinRmExecClient —— 命令执行客户端

`com.chua.winrm.support.client.WinRmExecClient`，实现 `AutoCloseable`，链式风格与 SSH 客户端一致（`@since 4.0.0.42`）。

**构建与连接：**

```java
WinRmExecClient winrm = WinRmExecClient.builder()
        .host("192.168.1.10")
        .port(5985)                 // 默认 5985
        .username("administrator")
        .password("pass")
        .build();                   // host/username 为空抛 IllegalArgumentException

winrm.connect();                    // 端点固定为 http://<host>:<port>/wsman
```

| 工厂 / 构造 | 说明 |
| --- | --- |
| `WinRmExecClient.builder()` | 链式构建器，参数见 [4.2](#42-builder-参数与默认值) |
| `WinRmExecClient.create(host, username, password)` | 快捷工厂，端口等取默认值 |
| `new WinRmExecClient(ClientSetting)` | 从通用客户端配置构建；读取 connect/read 超时（毫秒），domain=null、认证固定 NTLM、payloadEncryptionOff=false |

**连接行为（`connect()` 内部，与源码一致）：**

- 端点：`http://<host>:<port>/wsman`；
- 认证方案默认 `NTLM`；NTLM 时底层目标方案同时声明 `Negotiate` 与 `NTLM`（目标机 401 可能只回 Negotiate/SPNEGO，只声明 NTLM 会导致认证循环）；
- `payloadEncryptionOff(true)` 时设置 `PayloadEncryptionMode.OFF`（Basic 认证通常需配合目标机 `allowunencrypted=true`）；
- 始终 `disableCertificateChecks(true)`；
- 失败抛 `WinRMException`，消息内含异常链根因摘要（`SimpleClassName: message`）。

**三种操作入口：**

| 入口 | 返回 | 说明 |
| --- | --- | --- |
| `exec()` | `ExecOperation` | 单条命令：`command(cmd)` → `execute()` / `executeAndGetOutput()` / `executeAndGetExitCode()` |
| `shell()` | `ShellOperation` | 交互式 Shell：`connect()` → `send(cmd)` → `readAll()` → `close()`；输出在 `send()` 时同步返回，`readAll()` 固定返回空串（仅为 API 兼容保留） |
| `terminal()` | `TerminalOperation` | 伪终端：`pty(v)` / `width(w)` / `height(h)` 为兼容性占位（直接返回 this），`onOutput(Consumer<String>)` / `onClose(Runnable)` 回调，`connect()` → `send(cmd)`（每次 send 执行一条命令，stdout+stderr 合并进缓冲并回调）→ `readBuffer()`（取走并清空缓冲）→ `close()` |

`executeCommand(command, timeoutMs)` 会将毫秒值传给 winrm4j 的 operation timeout；无参重载采用客户端的 `sessionTimeout`。命令级超时走真实 WinRM 时序的验证尚未执行。

**执行结果：**

```java
public record ExecResult(int exitCode, String stdout, String stderr) {
    public String getOutput() { return stdout; }
}
```

**关闭：** `close()`（= `disconnect()`，AutoCloseable，可配合 try-with-resources）、`closeQuietly()`（吞掉关闭异常）。

**异常：** `com.chua.winrm.support.client.WinRMException`，继承 `RuntimeException`，构造器 `(String message)` 与 `(String message, Throwable cause)`。

### 2.3 WinRmFileClient —— 文件操作

`com.chua.winrm.support.client.WinRmFileClient`，实现 `com.chua.common.support.network.protocol.client.FileClient`（`@since 4.0.0.42`）。所有操作翻译为 PowerShell 脚本执行：脚本按 **UTF-16LE** 编码后 Base64，通过 `powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand <Base64>` 下发；路径统一做单引号转义（`'` → `''`）。

| 方法 | 远程行为 |
| --- | --- |
| `listFiles(path)` | `Get-ChildItem -LiteralPath … \| Select-Object -ExpandProperty Name`，按行拆分 |
| `uploadFile(InputStream, path)` | 内容 Base64 后 `[System.IO.File]::WriteAllBytes(…)` |
| `downloadFile(path, OutputStream)` | `[System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes(…))`，解码写出 |
| `readFile(path)` | `Get-Content -LiteralPath … -Raw` |
| `createDirectory(path, recursive)` | `New-Item -ItemType Directory`，recursive 时追加 `-Force` |
| `delete(path)` | `Remove-Item -Force -Recurse` |
| `rename(oldPath, newPath)` | `Move-Item` |
| `exists(path)` / `isDirectory(path)` | `Test-Path`（后者加 `-PathType Container`） |
| `connect()` / `closeQuietly()` | 委托内部 `WinRmExecClient` |

构造方式二选一：

```java
WinRmFileClient a = new WinRmFileClient(clientSetting);   // 独立连接；连接/读取超时按毫秒传递
WinRmFileClient b = new WinRmFileClient(execClient);      // 复用已连接的 WinRmExecClient
```

退出码非 0 时抛 `IOException`，消息含 `exitCode` 与 stderr（为空时回退 stdout）；路径为空抛 `IOException("远程路径不能为空")`。

`com.chua.winrm.support.client.WinRmFileClientSpi` 是同一个 `FileClient` 的 SPI 适配器：构造时校验 `ClientSetting` 非空（否则 `IllegalArgumentException("WinRM ClientSetting 不能为空")`），全部方法委托给内部 `WinRmFileClient`。

### 2.4 WinRmRemoteServiceManager —— 远程服务管理

`com.chua.winrm.support.service.WinRmRemoteServiceManager`，实现 `com.chua.common.support.service.RemoteServiceManager`，注解 `@Spi("winrm")`（`@since 4.0.0.43`）。用于在远程 Windows 主机上对 Java 服务做启停 / 重启 / 安装 / 卸载。

```java
// connect 固定使用 Basic 认证并关闭负载加密（payloadEncryptionOff(true)）
mgr.connect(new RemoteServiceManager.SshConfig(host, 5985, username, password, privateKeyPath));
```

| 方法 | 行为 |
| --- | --- |
| `startRemote(serviceName, jarPath, startCmd)` | 本地 jar 存在则先上传（相对路径归一到 `C:\opt\sip-server\<文件名>`，盘符绝对路径原样保留）；`startCmd` 中的 `{jar}` 占位符替换为远程路径；以 `Start-Process -FilePath 'java' -ArgumentList @('-jar', '<jar>') -WindowStyle Hidden -PassThru` 后台启动，返回 PID（解析失败返回 -1） |
| `stopRemote(pid, serviceName)` | `pid > 0` 时 `Stop-Process -Id <pid> -Force`；否则按服务名匹配 `Win32_Process` 中 `CommandLine like '%<serviceName>%'` 的 java 进程并停止 |
| `restartRemote(pid, serviceName, jarPath, startCmd)` | 先 stop，等待 2 秒，再 start |
| `isRemoteRunning(pid)` | `(Get-Process -Id <pid> -ErrorAction SilentlyContinue) -ne $null` 是否返回 True |
| `isRemoteServiceRunning(serviceName)` | `sc.exe query "<serviceName>"` 输出包含 `RUNNING` 或 `正在运行` |
| `uploadJar(localPath, remotePath)` | 仅上传 jar（本地文件不存在时告警并跳过） |
| `installRemote(serviceName, remoteJarPath, startCmd)` | 创建远程目录 + `sc.exe create … binPath= "<startCmd>" start= auto` + `sc.exe description` + `sc.exe failure … reset= 86400 actions= restart/60000`（注册为 Windows 系统服务并配置失败自动重启） |
| `uninstallRemote(serviceName)` | `sc.exe stop` → 等 3 秒 → `sc.exe delete` |
| `disconnect()` / `isConnected()` | 断开与状态查询 |

未连接时调用任何远程方法抛 `IllegalStateException("[service-remote] WinRM 未连接，请先调用 connect()")`；命令执行失败包装为 `RuntimeException`。

---

## 3. 快速开始

### 3.1 执行命令（最短路径）

```java
import com.chua.winrm.support.client.WinRmExecClient;
import com.chua.winrm.support.client.WinRmExecClient.ExecResult;

try (WinRmExecClient winrm = WinRmExecClient.builder()
        .host("192.168.1.10")
        .port(5985)
        .username("administrator")
        .password("pass")
        .build()) {

    winrm.connect();

    // 完整结果：退出码 + stdout + stderr
    ExecResult r = winrm.exec().command("echo test").execute();
    System.out.println(r.exitCode() + " / " + r.stdout() + " / " + r.stderr());

    // 只要输出
    String hostname = winrm.exec().command("hostname").executeAndGetOutput();

    // 伪终端：输出实时回调
    winrm.terminal().pty(true).width(80).height(24)
            .onOutput(System.out::print)
            .connect()
            .send("ipconfig");
}
```

### 3.2 文件操作

```java
import com.chua.winrm.support.client.WinRmFileClient;

WinRmFileClient files = new WinRmFileClient(execClient);   // 复用 3.1 已连接的客户端
files.connect();
try {
    files.createDirectory("C:/opt/app", true);
    files.uploadFile(inputStream, "C:/opt/app/app.jar");
    System.out.println(files.listFiles("C:/opt/app"));
    System.out.println(files.readFile("C:/opt/app/readme.txt"));
    System.out.println(files.exists("C:/opt/app/app.jar"));
    files.downloadFile("C:/opt/app/app.jar", outputStream);
    files.rename("C:/opt/app/a.txt", "C:/opt/app/b.txt");
    files.delete("C:/opt/app/b.txt");
} finally {
    files.closeQuietly();
}
```

### 3.3 远程服务管理

```java
import com.chua.common.support.service.RemoteServiceManager;
import com.chua.common.support.spi.ServiceProvider;

RemoteServiceManager mgr = ServiceProvider.of(RemoteServiceManager.class)
        .getNewExtension("winrm");
mgr.connect(new RemoteServiceManager.SshConfig("192.168.1.10", 5985, "administrator", "pass", null));

long pid = mgr.startRemote("app", "C:/opt/app.jar", "java -jar {jar}");
System.out.println(mgr.isRemoteRunning(pid));

mgr.installRemote("app", "C:/opt/app.jar", "java -jar C:/opt/app.jar");  // 注册为 Windows 服务
System.out.println(mgr.isRemoteServiceRunning("app"));
mgr.stopRemote(pid, "app");
mgr.uninstallRemote("app");
mgr.disconnect();
```

---

## 4. 配置项

**无外部配置项。** 依据：本模块 `src/main/resources` 仅含 META-INF 的 SPI 登记文件与 native-image 反射配置，没有任何 `application*.properties` / `*.yml`；源码中也不读取系统属性或环境变量。全部参数在**构造期显式传入**。

### 4.1 参数入口

| 入口 | 说明 |
| --- | --- |
| `WinRmExecClient.Builder` | 逐项设置主机、端口、凭据、认证方案、超时等 |
| `com.chua.common.support.network.protocol.ClientSetting` | 通用客户端配置；执行与文件客户端读取 host/port/username/password 及 connect/read 毫秒超时；`writeTimeout` 当前没有对应消费路径 |
| `RemoteServiceManager.SshConfig(host, port, username, password, privateKeyPath)` | record；仅校验 host 与 username 非空白，WinRM 实现复用该载体、port 取 HTTP 端口 5985 |

### 4.2 Builder 参数与默认值

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `host` | String | 必填 | 主机地址；空白抛 `IllegalArgumentException("host 不能为空")` |
| `port` | int | `5985` | WinRM HTTP 端口，端点 `http://<host>:<port>/wsman` |
| `username` | String | 必填 | 用户名；空白抛 `IllegalArgumentException("username 不能为空")` |
| `password` | String | `null` | 密码 |
| `domain` | String | `null` | 域；当前 `connect()` 流程未使用该值 |
| `connectTimeout` | int/long | `30,000` 毫秒 | 传给 winrm4j `connectionTimeout` |
| `sessionTimeout` | int/long | `30,000` 毫秒 | 传给 winrm4j `receiveTimeout` 与命令的 `operationTimeout` |
| `authenticationScheme` | String | `NTLM` | `NTLM` 或 `Basic` |
| `payloadEncryptionOff` | boolean | `false` | `true` 时设置 `PayloadEncryptionMode.OFF`（Basic 认证需配合目标机 `allowunencrypted=true`） |

---

## 5. SPI 扩展点

本模块向 `utils-support-common-starter` 的两个 SPI 注册了 WinRM 实现：

| SPI 接口 | 登记方式 | key / 别名 | 实现 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.protocol.client.FileClient` | `META-INF/extensions/com.chua.common.support.network.protocol.client.FileClient` | 裸类名行（无 `别名=` 前缀），别名由 SPI 框架按「实现类简单名去掉接口简单名」推导，即 `WinRmSpi` | `WinRmFileClientSpi` |
| `com.chua.common.support.service.RemoteServiceManager` | `@Spi("winrm")` 注解 + `META-INF/services/…RemoteServiceManager`（标准 ServiceLoader） | `winrm` | `WinRmRemoteServiceManager` |

获取方式：

```java
import com.chua.common.support.spi.ServiceProvider;

// 文件客户端（WinRM）
FileClient fileClient = ServiceProvider.of(FileClient.class).getExtension("WinRmSpi");

// 远程服务管理器（WinRM）
RemoteServiceManager mgr = ServiceProvider.of(RemoteServiceManager.class).getNewExtension("winrm");
```

GraalVM 侧：`META-INF/native-image/com.chua/utils-support-winrm-starter/reflect-config.json` 对 `WinRmExecClient`、`WinRmFileClient`、`WinRmFileClientSpi` 三个类注册了全部构造器、方法与字段的反射元数据。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（继承仓库根 `java.version=25`，本模块 pom 未覆盖）。
- 源码 UTF-8、无 BOM；Lombok 以 `provided` 方式参与编译。

```bash
mvn -pl utils-support-network-parent/utils-support-winrm-starter -am clean package
```

### 6.2 依赖清单（pom 实测）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 pom | SPI 契约（`FileClient`、`RemoteServiceManager`、`ServiceProvider` 等）与工具 |
| `io.cloudsoft.windows:winrm4j` | 0.12.3 | WinRM 协议核心 |
| `io.cloudsoft.windows:winrm4j-client` | 0.12.3 | `WinRmClient` / `WinRmClientBuilder` / `ShellCommand` |
| `javax.xml.bind:jaxb-api` | 2.4.0-b180830.0359 | JAXB API（SOAP/WS-Management 编解码所需） |
| `com.sun.xml.bind:jaxb-impl` / `jaxb-core` | 2.3.1 / 2.3.0.1 | JAXB 实现 |
| `javax.activation:activation` | 1.1.1 | JAXB 关联依赖 |
| `com.google.code.findbugs:jsr305` | `${jsr305.version}` | 注解 |
| `org.projectlombok:lombok` | `${lombok.version}` | `provided`，编译期 |

> pom 中将 `**/client/Fp*.java` 排除出测试编译：这类 `Fp*` 文件是一次性远程 WinRM 探针（含 main 方法），历史上从未参与编译，仅保留源码。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 / 去处 |
| --- | --- |
| SSH / SFTP / 本地服务管理 | 在 `utils-support-ssh-starter`（`RemoteServiceManager` 的 `ssh` 实现等）；本模块只做 WinRM |
| Windows 端被管程序 / Agent / WinRM 服务端 | 不存在；本模块只是发起连接的客户端，目标机需自行开启 WinRM 监听（默认 HTTP 5985 `/wsman`） |
| 连接池 / 多连接复用 | 无；一次 `builder()…build()` 对应一个连接，`ShellOperation`、`TerminalOperation` 均复用当前客户端 |
| 大文件高速传输 | 不适合；`uploadFile` / `downloadFile` 将整个文件 Base64 后经 PowerShell 传输，全量驻留内存 |
| 真实 PTY | 无；`terminal()` 的 `pty/width/height` 为兼容性占位（直接返回 this），伪终端按「每次 `send` 执行一条命令」模拟 |
| 命令级超时控制 | 命令执行会设置 winrm4j operation timeout；真实远程超时计时尚未验收。`shell().readAll()` 固定返回空串（输出已在 `send()` 时同步返回） |
| 非 Windows 主机 | 不支持；PowerShell / `sc.exe` / `Get-Process` 等命令仅适用于 Windows 目标机 |
| 单元测试 | `src/test` 无测试源码；仓库内《单元测试覆盖矩阵.md》为早期规划文档，其中的 `WinRMClient`、`WinRmServer*Spi` 及 okhttp / jackson-dataformat-xml 依赖与当前源码**不一致**，以本 README 与源码为准 |
