# utils-support-webdav-starter

> WebDAV 协议集成模块：**基于 Sardine 的 WebDAV 客户端适配**。把任意 WebDAV 服务器（类注释列举：Nginx webdav、Apache mod_dav、Nextcloud、ownCloud 等）适配成统一的 `FileStorage` 对象存储模型，并提供基于 WebDAV 的配置保存/加载器（`ConfigSaveOrLoader`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 POM `maven.compiler.source/target=25`）｜包名：`com.chua.webdav.support`（`storage` / `config` 两个子包）｜两个实现类均 `@since 4.0.0.42`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 WebdavFileStorage：FileStorage SPI 实现](#22-webdavfilestoragefilestorage-spi-实现)
  - [2.3 WebdavConfigSaveOrLoader：配置保存/加载器](#23-webdavconfigsaveorloader配置保存加载器)
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
    <artifactId>utils-support-webdav-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 的描述为：*简单 WebDAV 文件存储实现：基于 sardine 库实现 FileStorage SPI*。

规模：模块只有 **2 个 Java 源文件**——`storage.WebdavFileStorage` 与 `config.WebdavConfigSaveOrLoader`，是典型的「薄适配层」：协议交互全部委托给 Sardine，本模块只做契约适配、结果转换与父目录补齐。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 存储抽象 | `FileStorage` SPI 的 WebDAV 实现：上传、下载、删除、存在性检查、列目录 | 不是新的存储协议；只是既有 `FileStorage` 契约的一个后端 |
| 配置持久化 | `ConfigSaveOrLoader` SPI 的 WebDAV 实现：`saveBytes` / `loadBytes` / `delete` | 不是配置中心客户端；无监听、推送、版本管理能力 |
| 协议角色 | WebDAV **客户端**（PUT / GET / DELETE / PROPFIND / MKCOL 由 Sardine 承载） | 不含 WebDAV 服务端；不能把本地目录暴露成 WebDAV 服务 |
| 装配方式 | 纯 Java 库 + SPI（`META-INF/extensions`），无 Spring 依赖 | 不提供 Spring Boot 自动装配、无 `application.yml` 配置键 |

**判断口径**：需要在统一 `FileStorage` 抽象下把 WebDAV 服务器当对象存储用 → `FileStorage.createStorage("webdav", setting)`；需要把配置字节持久化到 WebDAV → `WebdavConfigSaveOrLoader`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| WebDAV 对象存储 | `FileStorage.createStorage("webdav", bucketSetting)` | SPI 名 `webdav`（别名 `swebdav`），对应 `WebdavFileStorage` |
| 上传文件 | `storage.putObject(PutObjectRequest)` | 父目录不存在时自动逐级创建 |
| 下载文件 | `storage.getObject("dir/a.txt")` 或 `getObject(GetObjectRequest)` | 返回 `GetObjectResult`（输入流 + `Metadata`） |
| 删除文件 | `storage.deleteObject(key)` | 基于 Sardine `delete` |
| 判断存在 | `storage.existObject(ExistObjectRequest)` | 基于 Sardine `exists` |
| 列目录 | `storage.listObject(ListObjectRequest)` | 一次返回全部条目，忽略分页参数 |
| 配置保存/加载/删除 | `WebdavConfigSaveOrLoader`（SPI 名 `webdav`） | `saveBytes` / `loadBytes` / `delete` |
| 释放连接 | `storage.close()` / `loader.close()` | 底层 Sardine `shutdown`，失败仅记 debug 日志 |

### 2.2 WebdavFileStorage：FileStorage SPI 实现

`com.chua.webdav.support.storage.WebdavFileStorage`（`@since 4.0.0.42`），继承 `AbstractFileStorage`，类上注解 `@Spi({"webdav", "swebdav"})`。构造时用 `SardineFactory.begin(accessKeyId, accessKeySecret)` 建立客户端，`baseUrl = endpoint（补齐末尾 /）+ bucket + /`（bucket 非空时）。

**所有操作都不抛异常**：成功返回 `SUCCESS` 结果码，失败返回 `FAILURE` + `message`。

| 方法 | 行为 |
| --- | --- |
| `putObject(PutObjectRequest)` | 先对 key 的每级父目录做「不存在则 `createDirectory`」，再 `sardine.put`；Content-Type 取 `metadata.contentType`，缺省 `application/octet-stream`；成功结果带 `key` 与完整 `url` |
| `getObject(GetObjectRequest)` | `sardine.get(fullUrl(key))`，返回输入流 + `Metadata`（name 取请求的 fileName） |
| `getObject(String key)` | 把 `dir/file.txt` 拆成 `filePath=dir`、`fileName=file.txt` 后走上一行 |
| `deleteObject(String key)` | `sardine.delete` |
| `existObject(ExistObjectRequest)` | `sardine.exists` |
| `listObject(ListObjectRequest)` | `sardine.list`；跳过名为 `.` 或 null 的资源；映射为 `Metadata`（name / size=contentLength / contentType / lastModified / directory / path） |
| `close()` | `sardine.shutdown()`，异常吞掉记 debug |

行为细节（源码事实）：

- `putObject` 直接读取 `request.getContent()` 字段，**不经过** `getContentBytes()` 的 file/inputStream 回退。请用 `PutObjectRequest.of(file, path)` 工厂或显式 `.content(bytes)` 构造；只设 `file` / `inputStream` 会拿到 null 内容。
- 本实现未重写 `supportsRange()`（默认 `false`）：`GetObjectRequest` 的 `offset` / `length` 不会被下推。
- `listObject` 不分页：源码注释「webdav 协议原生不支持分页」，`marker` / `limit` / `offset` 均被忽略。
- `createMultipartStorage()` 继承自 `AbstractFileStorage`：分片暂存本地临时目录，`complete` 时合并成整包再 `putObject`。

### 2.3 WebdavConfigSaveOrLoader：配置保存/加载器

`com.chua.webdav.support.config.WebdavConfigSaveOrLoader`（`@since 4.0.0.42`），继承 `AbstractConfigSaveOrLoader`，类上注解 `@Spi("webdav")`。用 Sardine 把配置字节持久化到远程 WebDAV 服务器。

构造约束：`ConfigSaveLoadSetting` 为 null 时按空配置处理；**`endpoint` 必填**，否则构造时抛 `IllegalArgumentException("WebDAV 配置必须指定 endpoint")`；`username` / `password` 缺省按空串处理；`baseUrl = endpoint + / + basePath + /`。

| 方法 | 行为 |
| --- | --- |
| `saveBytes(String key, byte[] content)` | 先补齐父目录，再 `sardine.put(fullUrl(key), content, setting.getContentType())`；成功返回 `success(key, fullUrl, size)`，失败返回 `failure(key, message)` |
| `loadBytes(String key)` | `sardine.get` → `readAllBytes` → `Optional.of`；任何异常返回 `Optional.empty()`（debug 日志） |
| `delete(String key)` | `sardine.delete`，成功 `true`，失败 `false` |
| `close()` | `sardine.shutdown()`，异常吞掉记 debug |

key 规范化（继承 `normalizeKey`）：`\` 替换为 `/`、去开头 `/`；空白 key 抛 `IllegalArgumentException`。

---

## 3. 快速开始

### 3.1 对象存储：上传、下载、列目录、删除

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.*;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;

BucketSetting setting = BucketSetting.builder()
        .endpoint("https://webdav.example.com")   // WebDAV 服务器地址
        .accessKeyId("username")                  // 用户名
        .accessKeySecret("password")              // 密码
        .bucket("remote-path")                    // 远端子目录（可空）
        .build();

try (FileStorage storage = FileStorage.createStorage("webdav", setting)) {
    // 也可直接构造：FileStorage storage = new WebdavFileStorage(setting);

    // 上传（父目录不存在会自动逐级创建；内容必须放在 content 字段）
    storage.putObject(PutObjectRequest.builder()
            .filePath("docs/2024")
            .fileName("hello.txt")
            .content("Hello WebDAV".getBytes())
            .build());

    // 下载（两种等价写法）
    GetObjectResult got = storage.getObject("docs/2024/hello.txt");
    if (got.getResultCode() == ObjectResult.ResultCode.SUCCESS) {
        try (var is = got.getInputStream()) {
            System.out.println(new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // 列目录
    ListObjectResult list = storage.listObject(
            ListObjectRequest.builder().filePath("docs/2024").build());
    list.getMetadata().forEach(m -> System.out.println(m.getName()));

    // 判断存在
    ExistObjectResult exist = storage.existObject(ExistObjectRequest.builder()
            .filePath("docs/2024").fileName("hello.txt").build());
    System.out.println(exist.isExists());

    // 删除
    storage.deleteObject("docs/2024/hello.txt");
}
```

### 3.2 配置保存/加载

```java
import com.chua.common.support.config.loader.ConfigSaveLoadSetting;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.spi.ServiceProvider;

ConfigSaveLoadSetting setting = ConfigSaveLoadSetting.builder()
        .endpoint("https://webdav.example.com")   // 必填，缺失时构造抛 IllegalArgumentException
        .basePath("my-app/config")                // 基础路径前缀
        .username("username")
        .password("password")
        .build();

// SPI 方式获取（构造参数透传）；也可直接 new WebdavConfigSaveOrLoader(setting)
try (ConfigSaveOrLoader loader =
             ServiceProvider.of(ConfigSaveOrLoader.class).getNewExtension("webdav", setting)) {

    loader.saveBytes("db/datasource.properties",
            "url=jdbc:mysql://127.0.0.1:3306/demo".getBytes());

    loader.loadBytes("db/datasource.properties")
          .ifPresent(bytes -> System.out.println(new String(bytes)));

    loader.delete("db/datasource.properties");
}
```

---

## 4. 配置项

本模块**没有 Spring 配置键**（无 `application.yml`、无自动装配元数据；`src/main/resources` 下仅有 `META-INF/extensions` SPI 注册文件与 GraalVM `native-image` 配置）。所有配置通过构造参数传入：

**`WebdavFileStorage` ← `BucketSetting`**

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `endpoint` | 是 | WebDAV 服务器地址，如 `https://webdav.example.com` |
| `accessKeyId` | 建议 | 登录用户名，传入 `SardineFactory.begin` |
| `accessKeySecret` | 建议 | 登录密码，传入 `SardineFactory.begin`；两者缺省时按 Sardine 默认行为处理（源码未做判空） |
| `bucket` | 否 | 远端根路径，非空时 `baseUrl = endpoint/bucket/` |
| `region` / `connectionTimeoutMills` / `sessionTimeoutMills` / `extraProperties` | — | `BucketSetting` 中存在，但本实现未读取 |

**`WebdavConfigSaveOrLoader` ← `ConfigSaveLoadSetting`**

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `endpoint` | 是 | 缺失时构造直接抛 `IllegalArgumentException` |
| `basePath` | 否 | 基础路径前缀，拼在 endpoint 后 |
| `username` / `password` | 否 | 认证信息，null 按空串处理 |
| `contentType` | 否 | 上传 Content-Type，默认 `application/octet-stream` |
| `charset` | 否 | 默认 UTF-8（经 `charset()` 暴露） |
| `rootPath` / `connectTimeoutMillis` / `readTimeoutMillis` | — | 字段存在，但本实现未读取 |

---

## 5. SPI 扩展点

本模块只作为 SPI **提供方**，不消费外部 SPI：

| 接口 | SPI 名 | 实现类 | 注册文件（`META-INF/extensions/`）内容 |
| --- | --- | --- | --- |
| `com.chua.common.support.storage.FileStorage` | `webdav`、`swebdav`（类上 `@Spi`） | `com.chua.webdav.support.storage.WebdavFileStorage` | `com.chua.common.support.storage.FileStorage` → `com.chua.webdav.support.storage.WebdavFileStorage` |
| `com.chua.common.support.config.loader.ConfigSaveOrLoader` | `webdav`（类上 `@Spi("webdav")`） | `com.chua.webdav.support.config.WebdavConfigSaveOrLoader` | `com.chua.common.support.config.loader.ConfigSaveOrLoader` → `webdav=com.chua.webdav.support.config.WebdavConfigSaveOrLoader` |

调用方式：

```java
FileStorage storage = FileStorage.createStorage("webdav", bucketSetting);
ConfigSaveOrLoader loader =
        ServiceProvider.of(ConfigSaveOrLoader.class).getNewExtension("webdav", setting);
```

**GraalVM native-image**：`META-INF/native-image/com.chua/utils-support-webdav-starter/` 下提供两份配置——`reflect-config.json`（注册两个实现类的全量反射）与 `resource-config.json`（打包 `META-INF/extensions/.*`）。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（根 POM `utils-support-parent-starter` 设定 `maven.compiler.source/target=25`）。
- 源码 UTF-8 无 BOM。

```bash
# 在 utils-support-parent-starter 仓库根目录
mvn -pl utils-support-network-parent/utils-support-webdav-starter -am clean package
```

### 6.2 依赖清单（来自模块 POM）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | `FileStorage` / `AbstractFileStorage` / `ConfigSaveOrLoader` / `BucketSetting` 等契约 |
| `com.github.lookfirst:sardine` | `5.13`（根 POM `dependencyManagement` 统一管理） | WebDAV 客户端协议栈 |

源码中的 `@Slf4j`（Lombok）与 SLF4J 日志门面经由 `utils-support-common-starter` 传递引入，模块 POM 未单独声明。

本模块当前未包含测试源码（`src/test` 目录不存在）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| WebDAV 服务端 | 不提供；本模块只是客户端，不能把本地目录暴露成 WebDAV 服务 |
| Range/断点读取 | `supportsRange()` 为默认 `false`，`GetObjectRequest` 的 `offset`/`length` 不下推 |
| 列目录分页 | `listObject` 忽略 `marker`/`limit`/`offset`，一次返回全部（WebDAV 协议原生不支持分页） |
| 原生分片上传 | 无；`createMultipartStorage()` 为 `AbstractFileStorage` 的本地临时目录默认实现 |
| 认证方式 | 仅用户名/密码（`SardineFactory.begin`）；OAuth/Token 等其它认证源码未提供 |
| Spring Boot 自动装配 / `application.yml` 配置键 | 无；纯 Java 库 + SPI |
| 其它对象存储后端（S3/OSS/COS/MinIO 等） | 由各自 storage starter 注册；本模块只提供 `webdav` 这一个 `FileStorage` 实现 |
