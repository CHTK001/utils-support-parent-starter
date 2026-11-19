# utils-support-ftp-starter

> FTP 协议集成模块：提供链式 DSL 的 FTP/FTPS 客户端与远程目录轮询实现，基于 Apache Commons Net。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.ftp.support`（`client`、`directory` 两个子包）

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
    <artifactId>utils-support-ftp-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 客户端 | `FtpClient`：链式 DSL 的 FTP/FTPS 客户端，覆盖上传、下载、列目录、目录切换/创建/删除、重命名、存在性与大小查询 | 不是 FTP 服务端（服务端能力在 `utils-support-common-starter` 的 `network.ftp` / `network.server`） |
| 目录监听 | `FtpPolledDirectory`：基于快照对比（`DiffPolledDirectory`）的远程 FTP 目录轮询，注册为 `PolledDirectory` SPI 的 `ftp` 实现 | 不是本地文件系统监听（本地目录监听用 common-starter 的 `DirectoryWatcher`） |
| 依赖 | 只依赖 `commons-net` 与 `utils-support-common-starter` | 不依赖 Spring，不内置连接池；连接生命周期由调用方通过 `connect()` / `close()` 管理 |
| 规模 | 2 个 Java 源文件、2 个子包，含 GraalVM `native-image` 的 `reflect-config.json` / `resource-config.json` | — |

**判断口径**：凡是「用 Java 操作远程 FTP 服务器上的文件与目录」，入口就是本模块的 `FtpClient`；凡是「定时发现远程 FTP 目录里新增/修改/删除了哪些文件」，入口就是本模块的 `FtpPolledDirectory`。其它协议（SFTP、WebDAV、SMB 等）不在本模块。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 快速创建明文 FTP 客户端 | `FtpClient.create(host, username, password)` | 返回未连接实例，再调 `.connect()` |
| 创建 FTPS（显式 TLS）客户端 | `FtpClient.createFtps(host, username, password)` | 等价于 `builder().ssl(true)` |
| 完整参数构建 | `FtpClient.builder().host(...)...build()` | 端口、被动模式、二进制、超时、SSL 均可配 |
| 上传文件 | `ftp.upload().local(本地路径).remote(远程路径).exec()` | 也可用 `.stream(InputStream)` 代替 `.local(...)` |
| 下载文件 | `ftp.download().remote(远程路径).local(本地路径).exec()` | 也可用 `.stream(OutputStream)`；本地路径父目录不存在会自动创建 |
| 列出远程目录 | `ftp.ls().path("/data").exec()` | 返回 `List<String>` 文件/目录名列表，`path` 默认当前目录 `.` |
| 创建目录 | `ftp.mkdir().path("/data/new-dir").exec()` | — |
| 切换目录 | `ftp.cd().path("/data/sub").exec()` | 切换失败抛 `FtpClientException` |
| 删除文件 | `ftp.rm().path("/data/old.txt").exec()` | — |
| 删除目录 | `ftp.rmdir().path("/data/empty-dir").exec()` | — |
| 重命名 | `ftp.rename().from("/data/a.txt").to("/data/b.txt").exec()` | 文件/目录均适用 |
| 查当前工作目录 | `ftp.pwd()` | 返回字符串 |
| 查文件/目录是否存在 | `ftp.exists(path)` | 异常时返回 `false` |
| 查文件大小 | `ftp.size(path)` | 字节数，获取失败返回 `-1` |
| 切换传输模式 | `ftp.setBinary(true)` | `true` 二进制 / `false` ASCII，返回当前实例 |
| 远程目录变更轮询 | `new FtpPolledDirectory(listenPath, environment)` | SPI key：`ftp`，见第 5 节 |

### 2.2 FtpClient

`com.chua.ftp.support.client.FtpClient`，实现 `AutoCloseable`，支持明文 FTP 与 FTPS 两种模式（FTPS 由 Apache Commons Net 的 `FTPSClient` 承载）。所有文件操作采用链式 DSL，与 `utils-support-ssh-starter` 的 `SftpClient` 风格统一。

链式操作入口与对应操作类（均为 `FtpClient` 的内部静态类）：

| 入口方法 | 操作类 | 参数方法 | `exec()` 行为 |
| --- | --- | --- | --- |
| `upload()` | `UploadOperation` | `local(path)` / `remote(path)` / `stream(InputStream)` | `storeFile` 上传；未指定来源时抛 `FtpClientException` |
| `download()` | `DownloadOperation` | `remote(path)` / `local(path)` / `stream(OutputStream)` | `retrieveFile` 下载；本地路径自动创建父目录 |
| `ls()` | `ListOperation` | `path(path)`（默认 `.`） | 返回 `List<String>` 名称列表 |
| `cd()` | `CdOperation` | `path(path)` | 切换工作目录，失败抛异常 |
| `mkdir()` | `MkdirOperation` | `path(path)` | 创建目录 |
| `rm()` | `RmOperation` | `path(path)` | 删除文件 |
| `rmdir()` | `RmdirOperation` | `path(path)` | 删除目录 |
| `rename()` | `RenameOperation` | `from(oldPath)` / `to(newPath)` | 重命名 |

连接管理：`connect()` 完成连接 + 登录 + 进入被动模式（并按 `binary` 设置二进制传输），失败抛 `FtpClientException`；`disconnect()` / `close()` 登出并断开。所有操作失败均抛 `FtpClient.FtpClientException`（`RuntimeException` 子类）。

### 2.3 FtpPolledDirectory

`com.chua.ftp.support.directory.FtpPolledDirectory`，继承 common-starter 的 `DiffPolledDirectory<FTPFile>`（快照对比机制），定时对比远程 FTP 目录的文件变更；连接参数由 `DirectoryPollerEnvironment` 提供，轮询由目录轮询执行器驱动。

- 构造：`FtpPolledDirectory(String listenPath, DirectoryPollerEnvironment environment)`，`listenPath` 为要监听的远程目录。
- `start(environment, executor)` 时建立 FTP 连接并登录（按 `passiveMode` 进入被动模式），随后交由父类启动轮询。
- `listAndModified(path)` 读取目录快照；读取失败记录日志并返回空列表（不中断轮询）。
- `close()` 时登出并断开连接。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ftp-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 上传 / 列目录 / 下载（最短可运行示例）

```java
import com.chua.ftp.support.client.FtpClient;

public class FtpDemo {
    public static void main(String[] args) {
        try (FtpClient ftp = FtpClient.builder()
                .host("192.168.1.100").port(21)
                .username("user").password("pass")
                .build().connect()) {

            // 上传
            ftp.upload().local("/tmp/file.txt").remote("/data/file.txt").exec();

            // 列目录
            System.out.println(ftp.ls().path("/data").exec());

            // 下载
            ftp.download().remote("/data/file.txt").local("/tmp/copy.txt").exec();
        }   // try-with-resources 自动 disconnect
    }
}
```

### 3.3 FTPS（显式 TLS）

```java
FtpClient ftps = FtpClient.builder()
        .host("192.168.1.100").port(21)
        .username("user").password("pass")
        .ssl(true).implicit(false)      // 显式 TLS；隐式模式写 implicit(true)
        .build().connect();
```

### 3.4 远程目录轮询

构造 `FtpPolledDirectory(listenPath, environment)`，连接参数通过 `DirectoryPollerEnvironment` 的属性传入（见 4.2），注册与调度由 common-starter 的目录轮询体系（`PolledDirectory` SPI + 目录轮询执行器）完成。

---

## 4. 配置项

### 4.1 Spring 配置项

**无**。本模块不是 Spring Boot 自动配置模块，源码未提供任何 `application.properties` / `application.yml` 配置绑定前缀；所有参数都通过 `FtpClient.Builder` 或 `DirectoryPollerEnvironment` 在代码中显式传入。

### 4.2 FtpClient.Builder 参数

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `host(String)` | String | 无（`build()` 校验非空） | FTP 服务器地址 |
| `port(int)` | int | `21` | 服务器端口 |
| `username(String)` | String | 无（`build()` 校验非空） | 登录用户名 |
| `password(String)` | String | 无 | 登录密码 |
| `passive(boolean)` | boolean | `true` | 是否使用被动模式；当前 `connect()` 实现固定进入本地被动模式 |
| `binary(boolean)` | boolean | `true` | 是否使用二进制传输模式 |
| `connectTimeout(int)` | int | `30000` | 连接超时时间（毫秒） |
| `ssl(boolean)` | boolean | `false` | 是否启用 SSL/TLS（FTPS 模式） |
| `implicit(boolean)` | boolean | `false` | 是否隐式 SSL 模式（`true`=隐式 TLS，`false`=显式 TLS） |

`build()` 时 `host` 或 `username` 为 `null` 会抛 `IllegalArgumentException`。

### 4.3 FtpPolledDirectory 环境属性

通过 `DirectoryPollerEnvironment.getProperty(key, defaultValue)` 读取：

| 属性键 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | 无（必填） | FTP 服务器地址 |
| `port` | `21` | FTP 端口 |
| `username` | `anonymous` | 登录用户名 |
| `password` | 空字符串 | 登录密码 |
| `passiveMode` | `true` | 是否使用被动模式 |

---

## 5. SPI 扩展点

本模块不定义新的 SPI 接口，只向 common-starter 的目录轮询 SPI 注册实现：

| 项 | 值 |
| --- | --- |
| SPI 接口 | `com.chua.common.support.lang.directory.PolledDirectory`（定义于 `utils-support-common-starter`） |
| 注册文件 | `src/main/resources/META-INF/extensions/com.chua.common.support.lang.directory.PolledDirectory` |
| 注册内容 | `ftp=com.chua.ftp.support.directory.FtpPolledDirectory` |
| SPI key | `ftp` |

即：通过目录轮询体系按名称 `ftp` 即可取到 `FtpPolledDirectory`，用于监听远程 FTP 目录的新增 / 修改 / 删除。新增其它协议的目录轮询实现时，在各自模块向同一 SPI 注册即可，不要在本模块内扩展。

---

## 6. 构建与依赖

依赖关系：

```text
utils-support-ftp-starter
├── utils-support-common-starter
└── commons-net:commons-net（版本由 utils-support-network-parent 统一管理，当前 3.13.0）
```

构建命令（仓库根目录为 `utils-support-parent-starter`）：

```bash
# 从仓库根构建本模块（含其依赖模块）
mvn -pl utils-support-network-parent/utils-support-ftp-starter -am compile -DskipTests

# 或在本模块目录内
mvn compile -DskipTests
```

GraalVM 原生镜像：`src/main/resources/META-INF/native-image/com.chua/utils-support-ftp-starter/` 下提供 `reflect-config.json` 与 `resource-config.json`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| FTP/FTPS **服务端**（`FtpServer` / `FtpsServer`、`FtpConfig`） | `utils-support-common-starter` 的 `network.ftp` / `network.server` |
| SFTP / SSH 客户端（`SftpClient` 等） | `utils-support-ssh-starter` |
| 本地目录监听（`DirectoryWatcher`）、目录轮询调度与执行器 | `utils-support-common-starter` 的 `lang.directory` |
| 统一文件系统抽象（`FileSystem`）、对象存储（`FileStorage`） | `utils-support-common-starter` / `utils-support-filesystem-starter` |
| WebDAV、SMB 等其它文件协议 | `utils-support-webdav-starter`、`utils-support-smb-starter` |
| 连接池、断点续传、递归遍历下载 | 本模块未提供；`FtpClient` 为单连接客户端，每个实例对应一条 FTP 连接，连接复用由调用方自行管理 |
