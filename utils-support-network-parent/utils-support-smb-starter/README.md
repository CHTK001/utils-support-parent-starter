# utils-support-smb-starter

> SMB 协议集成模块：**Rust 原生服务端（SMB2/3）+ smb-jna 链式客户端 + `FileStorage` SPI 实现**。客户端负责对远端共享的连接、上传、下载、列目录、建目录、删除；服务端基于 Rust 原生库把一个目录以 SMB 共享形式暴露出去。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 POM `maven.compiler.source/target=25`）｜包名：`com.chua.smb`（`client` / `server` / `storage` 三个子包）

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 SmbClient：链式客户端](#22-smbclient链式客户端)
  - [2.3 SmbServer：嵌入式服务端](#23-smbserver嵌入式服务端)
  - [2.4 SmbFileStorage：FileStorage SPI 实现](#24-smbfilestoragefilestorage-spi-实现)
  - [2.5 RustSmbServerBridge：原生库门面](#25-rustsmbserverbridge原生库门面)
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
    <artifactId>utils-support-smb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 的描述为：*SMB 协议集成：Rust 服务端（SMB2/3） + smb-jna 链式客户端 + FileStorage SPI*。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 客户端 | 基于 smb-jna 的链式 SMB 客户端（连接、登录、打开共享、上传、下载、列目录、建目录、删除） | 不是完整的 SMB 协议栈实现，协议交互全部委托给 smb-jna |
| 服务端 | 嵌入式 SMB 服务端门面（继承 `AbstractServer`），基于 Rust 原生库（smb-server crate，SMB2/3） | 不包含原生库本身；Rust 实现由 `utils-support-native-smb`（独立工程）通过 SPI 提供 |
| 存储抽象 | `FileStorage` SPI 的 `smb` 实现，把 SMB 共享适配成统一对象存储模型 | 不是新的存储协议；只是既有 `FileStorage` 契约的一个后端 |
| 装配方式 | 纯 Java 库 + Java SPI，无 Spring 依赖 | 不提供 Spring Boot 自动装配、不含 `application.yml` 配置键 |

**判断口径**：需要「读写别人机器上的 SMB 共享」用 `SmbClient`；需要「把自己的目录变成 SMB 共享」用 `SmbServer`；需要在统一的 `FileStorage` 抽象下把 SMB 当对象存储用，用 `FileStorage.createStorage("smb", setting)`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 连接并登录 SMB 共享 | `SmbClient.create(uri).connect().login().openShare()` | URI 形如 `smb://user:pass@host:port/share`，缺省端口 `445`，无用户名时走匿名认证 |
| 上传文件 | `SmbClient.upload(InputStream, remoteName)` | 覆盖写入（`FILE_OVERWRITE_IF`），父目录不存在时自动逐级创建 |
| 下载文件 | `SmbClient.download(remoteName, OutputStream)` | 文件必须已存在（`FILE_OPEN`） |
| 列目录 | `SmbClient.listFiles(path)` | 返回 `List<SmbFileEntry>`，自动跳过 `.` 与 `..` |
| 建目录 / 删除 | `SmbClient.mkdir(path)` / `SmbClient.delete(path)` | 支持相对当前工作目录的路径 |
| 起嵌入式 SMB 服务端 | `SmbServer.builder()...build().start()` | Rust 原生库承载协议，Java 侧只管生命周期与配置 |
| 对象存储统一入口 | `FileStorage.createStorage("smb", bucketSetting)` | SPI 名 `smb`，对应 `SmbFileStorage` |

### 2.2 SmbClient：链式客户端

`com.chua.smb.client.SmbClient`（`@since 4.0.0.42`，实现 `AutoCloseable`），基于 smb-jna。所有操作方法都返回 `SmbClient` 以支持链式调用；失败时抛 `RuntimeException`（消息如「SMB 连接失败： host:port」「SMB 认证失败」「SMB 上传失败」等）。

| 方法 | 说明 |
| --- | --- |
| `static SmbClient create(String uri)` | 按 `smb://[user:pass@]host[:port][/share]` 解析并创建客户端 |
| `connect()` | 连接 host:port（端口缺省 `445`） |
| `login()` | 有用户名时按 `user/password` 认证，否则匿名（`AuthenticationContext.anonymous()`） |
| `openShare()` | 打开 URI 中的 share 为 `DiskShare`；未 `login()` 先调用会抛 `IllegalStateException` |
| `cd(String path)` | 切换工作目录；相对路径基于当前工作目录拼接 |
| `upload(InputStream in, String remoteName)` | 上传并覆盖同名文件；自动创建缺失的父目录 |
| `download(String remoteName, OutputStream out)` | 下载文件内容到输出流 |
| `listFiles(String path)` | 列目录，返回 `List<SmbFileEntry>`（不含 `.` / `..`） |
| `mkdir(String path)` | 创建目录 |
| `delete(String path)` | 删除文件或目录 |
| `close()` | 依次关闭 share、session、connection、底层 `SMBClient`，失败仅记 debug 日志 |

文件操作（upload/download/listFiles/mkdir/delete）必须先 `openShare()`，否则抛 `IllegalStateException`。

`SmbFileEntry` 为 `record`，字段：`name`（名称）、`size`（大小）、`isDirectory`（是否目录）、`lastModified`（最后修改时间毫秒值）、`path`（所在目录）。

### 2.3 SmbServer：嵌入式服务端

`com.chua.smb.server.SmbServer`（`@since 4.0.0.42`），继承 `com.chua.common.support.network.server.AbstractServer`。协议交互由 Rust 原生库（smb-server crate，SMB2/3）完成，Java 侧负责生命周期管理和配置；`getProtocolType()` 返回 `ProtocolType.SMB`。

- 常量：`DEFAULT_PORT = 445`。
- 生命周期沿用 `AbstractServer` 的 `start()` / `stop()` / `isRunning()`（模板方法 `doStart` / `doStop`）。
- `doStart()` 第一步即调用 `rejectStreamEncryption(...)`：**开启 `ServerSetting` 的 encrypt 开关会直接拒绝启动**，提示原文为「SMB 服务端由 Rust 桥接实现，未暴露 SMB3 加密协商；需要加密请走 VPN 或下层隧道」。
- 启动时通过 `RustSmbServerBridge` 加载原生库并传入 host、port、shareName、rootPath、user、password；`doStop()` 按句柄停止。

Builder（`SmbServer.builder()`）参数及默认值：

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `host(String)` | `0.0.0.0` | 绑定地址 |
| `port(int)` | `445` | 监听端口 |
| `user(String)` | 空串 | 访问用户名 |
| `password(String)` | 空串 | 访问口令 |
| `shareName(String)` | `smbshare` | 对外共享名 |
| `rootPath(String)` | `/tmp/smbroot` | 共享根目录 |

### 2.4 SmbFileStorage：FileStorage SPI 实现

`com.chua.smb.storage.SmbFileStorage`（`@since 4.0.0.42`），继承 `AbstractFileStorage`，注解 `@Spi("smb")`，实现 `com.chua.common.support.storage.FileStorage`。内部把操作转交给 `SmbClient`。

`BucketSetting` 字段映射规则（源码注释原文）：

| BucketSetting 字段 | SMB 含义 |
| --- | --- |
| `endpoint` | SMB 服务器地址（如 `192.168.1.10` 或 `smb://192.168.1.10:445`） |
| `bucket` | share 名称 |
| `accessKeyId` | SMB 用户名 |
| `accessKeySecret` | SMB 密码 |

未设置时的默认值：host `127.0.0.1`、share `smbshare`、用户 `guest`、密码空、端口 `445`。

| 方法 | 行为 |
| --- | --- |
| `putObject(PutObjectRequest)` | 上传字节内容；内容为空返回 FAILURE |
| `getObject(GetObjectRequest)` / `getObject(String key)` | 下载为 `GetObjectResult`（内存字节流 + `Metadata`） |
| `deleteObject(String key)` | 删除对象 |
| `existObject(ExistObjectRequest)` | 通过尝试下载判断是否存在；异常时返回 `exists=false` |
| `listObject(ListObjectRequest)` | 列目录，映射为 `Metadata` 列表 |
| `close()` | 关闭内部 `SmbClient` |

所有方法**不抛异常**，失败以 `FAILURE` 结果码 + message 返回。

### 2.5 RustSmbServerBridge：原生库门面

`com.chua.smb.server.RustSmbServerBridge`（包私有，`@since 4.0.0.50`）。服务端原生能力的门面：只依赖 `com.chua.common.support.nativeapi.SmbServerSpi` 契约，通过 `NativeServices.require(SmbServerSpi.class, ...)` 解析实现（能力名「SMB 服务端能力」），不再反射 native 类。

- `loadLibrary()`：加载原生库；未加载成功抛 `IllegalStateException`（「SMB 服务端能力不可用：原生库未加载」）。
- `start(bindAddr, port, shareName, rootPath, user, password)`：启动并返回服务端句柄。
- `stop(handle)`：按句柄停止。

`SmbServerSpi` 接口（含 `loadLibrary` / `isLoaded` / `start` / `stop` / `listShares`）定义在 `utils-support-common-starter`，**实现由独立工程 `utils-support-native-smb` 提供**；classpath 上没有该实现时，`SmbServer` 无法启动。

---

## 3. 快速开始

以下示例均取自源码 Javadoc 与实现逻辑，是最短可运行形态。

### 3.1 客户端：上传、下载、列目录

```java
import com.chua.smb.client.SmbClient;

try (SmbClient client = SmbClient.create("smb://admin:pass@192.168.1.10:445/share")
        .connect()          // 建立连接
        .login()            // 认证（无用户名时为匿名）
        .openShare()        // 打开 share
        .cd("/folder")      // 切换工作目录（可省略）
        .upload(inputStream, "test.txt")) {   // 上传（父目录不存在会自动创建）

    client.download("test.txt", outputStream);  // 下载
    client.listFiles("/").forEach(System.out::println);  // 列目录
}   // close() 释放 share/session/connection
```

### 3.2 服务端：把本地目录共享出去

```java
import com.chua.smb.server.SmbServer;

SmbServer server = SmbServer.builder()
        .host("0.0.0.0")
        .port(445)
        .shareName("smbshare")
        .rootPath("/tmp/smbroot")
        .user("admin")        // 可选，默认空
        .password("pass")     // 可选，默认空
        .build();

server.start();     // 加载 Rust 原生库并启动；需 classpath 上有 utils-support-native-smb
server.isRunning();
server.stop();
```

> 注意：`ServerSetting` 打开 encrypt 会在启动期被拒绝（本实现未暴露 SMB3 加密协商）。

### 3.3 对象存储统一入口

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.setting.BucketSetting;

BucketSetting setting = BucketSetting.builder()
        .endpoint("192.168.1.10")     // 或 "smb://192.168.1.10:445"
        .bucket("shared")             // share 名称
        .accessKeyId("admin")         // SMB 用户名
        .accessKeySecret("password")  // SMB 密码
        .build();

FileStorage storage = FileStorage.createStorage("smb", setting);
// 之后按 FileStorage 统一模型：putObject / getObject / deleteObject / existObject / listObject
storage.close();
```

---

## 4. 配置项

本模块**没有 Spring 配置键**（无 `application.yml`、无自动装配元数据；`src/main/resources` 下仅有 `META-INF/services` SPI 注册文件与 GraalVM `native-image` 配置）。所有配置通过构造参数传入，汇总如下：

| 配置载体 | 键 / 方法 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `SmbClient` URI | `smb://[user:pass@]host[:port][/share]` | 端口 `445`；无用户名时匿名 | 客户端全部配置来源 |
| `SmbServer.Builder` | `host` / `port` / `user` / `password` / `shareName` / `rootPath` | `0.0.0.0` / `445` / 空 / 空 / `smbshare` / `/tmp/smbroot` | 服务端全部配置来源 |
| `BucketSetting` | `endpoint` / `bucket` / `accessKeyId` / `accessKeySecret` | `127.0.0.1` / `smbshare` / `guest` / 空 | `SmbFileStorage` 的配置来源，端口缺省 `445` |

行为开关方面仅有一条硬约束：`SmbServer` 启动期拒绝 `ServerSetting` 的 encrypt（流加密）开关，理由见 [2.3](#23-smbserver嵌入式服务端)。

---

## 5. SPI 扩展点

本模块在 SPI 体系中有两个方向的参与：

**作为提供方（对外注册的实现）**

| 接口 | SPI 名 | 实现类 | 注册方式 |
| --- | --- | --- | --- |
| `com.chua.common.support.storage.FileStorage` | `smb` | `com.chua.smb.storage.SmbFileStorage` | `META-INF/services/com.chua.common.support.storage.FileStorage`（标准 `ServiceLoader`），类上另有 `@Spi("smb")` |

调用方式：`FileStorage.createStorage("smb", bucketSetting)`。

**作为消费方（依赖外部提供的 SPI）**

| 接口 | 用途 | 实现来源 |
| --- | --- | --- |
| `com.chua.common.support.nativeapi.SmbServerSpi` | SMB 服务端原生能力（加载库、启动、停止） | 独立工程 `utils-support-native-smb`，经 `NativeServices` 解析；缺失时 `SmbServer.start()` 抛「SMB 服务端能力不可用」 |

**GraalVM native-image**：`META-INF/native-image/com.chua/utils-support-smb-starter/` 下提供三份配置——`reflect-config.json`（注册 `SmbFileStorage` 全量反射）、`resource-config.json`（打包 `META-INF/services/.*`）、`proxy-config.json`（`FileStorage` 代理）。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（根 POM `utils-support-parent-starter` 设定 `maven.compiler.source/target=25`）。
- 源码 UTF-8 无 BOM；Lombok 以 `provided` 方式参与编译。

```bash
# 在 utils-support-parent-starter 仓库根目录
mvn -pl utils-support-network-parent/utils-support-smb-starter -am clean package
```

### 6.2 依赖清单（来自模块 POM）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}` | `FileStorage` / `AbstractServer` / `SmbServerSpi` 等契约 |
| `com.hierynomus:smbj` | `${smb.jna.version}`（父 POM 设为 `0.14.0`） | SMB 客户端协议栈 |
| `org.slf4j:slf4j-api` | `${slf4j.version}` | 日志 |
| `org.projectlombok:lombok` | `${lombok.version}`（`provided`） | 编译期注解处理 |

本模块当前未包含测试源码（`src/test` 目录不存在）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| Rust 原生库与 SMB2/3 协议实现 | 在独立工程 `utils-support-native-smb`；本模块只依赖 `SmbServerSpi` 契约，未引入该 jar 时服务端不可启动 |
| SMB3 传输加密 | 未暴露加密协商；`ServerSetting` 开 encrypt 会在启动期被拒绝，需要加密请走 VPN 或下层隧道 |
| Spring Boot 自动装配 / `application.yml` 配置键 | 无；本模块是纯 Java 库 + Java SPI |
| 多共享服务端 | `SmbServer` 一次只暴露一个 `shareName` + `rootPath` |
| 其它对象存储后端（S3/OSS/COS/MinIO 等） | 由各自 storage starter 注册；本模块只提供 `smb` 这一个 `FileStorage` 实现 |

---

## 许可证

Apache License 2.0
