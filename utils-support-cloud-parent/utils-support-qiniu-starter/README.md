# utils-support-qiniu-starter

> utils 体系「云服务集成」家族中的**七牛云模块**：提供七牛云 Kodo 对象存储的 `FileStorage` SPI 实现，以及 `ChatClient` SPI 的 `qiniu` 桩实现（七牛云暂无 AI 对话能力，调用返回固定提示）。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.qiniu.support`（存储实现位于 `com.chua.qiniu.support.storage`）

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
    <artifactId>utils-support-qiniu-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块位于 `utils-support-parent-starter/utils-support-cloud-parent/utils-support-qiniu-starter`，父模块为 `utils-support-cloud-parent`（云服务集成父模块）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 对象存储 | 七牛云 Kodo 的 `FileStorage` SPI 实现（`QiniuKodoFileStorage`）：上传、下载、删除、存在性检查、列举 | 不是通用存储抽象（抽象在 `utils-support-common-starter`） |
| AI 对话 | `ChatClient` SPI 的 `qiniu` 名称占位实现（`QiniuChatClient`，桩） | **不是**真实的 AI 大模型对话实现，调用返回固定提示「七牛云暂不支持 AI 对话」 |
| 依赖 | 只依赖 `utils-support-common-starter` + 七牛官方 `qiniu-java-sdk` + slf4j + lombok | 不依赖 Spring，不引入其它云厂商 SDK |
| 配置 | 通过 `BucketSetting` 以编程方式传入 | 不读取任何 `application.yml` / properties 配置键 |

### 1.3 规模

- Java 源文件 **2** 个：`QiniuKodoFileStorage`、`QiniuChatClient`（均 `@since 4.0.0.42`）。
- SPI 注册文件 **2** 个（`META-INF/services/` 与 `META-INF/extensions/` 各 1 个）。
- 内置 GraalVM `native-image` 配置 3 个：`reflect-config.json` / `proxy-config.json` / `resource-config.json`。

---

## 2. 核心 API 与速查表

别人最常来查的入口：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建 Kodo 存储 | `FileStorage.createStorage("kodo", bucketSetting)` | SPI 名 `kodo` 或 `qiniu` 等价；每次调用新建实例 |
| 上传对象 | `storage.putObject(PutObjectRequest)` | 使用 `auth.uploadToken(bucket)` 凭据上传，成功返回对象 key |
| 下载对象（按 key） | `storage.getObject("docs/a.png")` | 生成 **3600 秒**有效期的私有下载链接并打开流 |
| 下载对象（按请求） | `storage.getObject(GetObjectRequest)` | 同上，`fileName` + `filePath` 组合出 key |
| 删除对象 | `storage.deleteObject("docs/a.png")` | 走 `BucketManager.delete` |
| 是否存在 | `storage.existObject(ExistObjectRequest)` | 走 `BucketManager.stat`；七牛 612（文件不存在）视为「不存在」而非失败 |
| 列举对象 | `storage.listObject(ListObjectRequest)` | 单页返回；结果中的 `marker` 可作为下一页请求的 `marker` |
| AI 对话（桩） | `ServiceProvider.of(ChatClient.class).getExtension("qiniu")` | `chatSync` / `chat` 均返回固定提示，不会发起网络请求 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-qiniu-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 对象存储（Kodo）最小示例

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.*;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;
import java.io.InputStream;

BucketSetting setting = BucketSetting.builder()
        .bucket("my-bucket")
        .accessKeyId("your-access-key")
        .accessKeySecret("your-secret-key")
        .endpoint("https://my-bucket.example.com")   // Bucket 绑定的下载域名
        .build();

try (FileStorage storage = FileStorage.createStorage("kodo", setting)) {

    // 1. 上传：目录 + 文件名 + 内容，最终对象 key = docs/hello.txt
    PutObjectResult put = storage.putObject(PutObjectRequest.builder()
            .filePath("docs")
            .fileName("hello.txt")
            .content("Hello Qiniu Kodo".getBytes())
            .build());
    if (put.getResultCode() != ObjectResult.ResultCode.SUCCESS) {
        throw new IllegalStateException("上传失败: " + put.getMessage());
    }

    // 2. 下载：按完整 key 取回（私有下载链接，有效期 3600 秒）
    GetObjectResult got = storage.getObject("docs/hello.txt");
    if (got.getResultCode() == ObjectResult.ResultCode.SUCCESS) {
        try (InputStream in = got.getInputStream()) {
            Metadata meta = got.getMetadata();   // name / contentType
            // 读取 in ...
        }
    }

    // 3. 存在性检查
    boolean exists = storage.existObject(ExistObjectRequest.builder()
            .filePath("docs").fileName("hello.txt").build()).isExists();

    // 4. 列举（单页；marker 作为下一页起点）
    ListObjectResult page = storage.listObject(ListObjectRequest.builder()
            .filePath("docs").limit(100).build());
    String nextMarker = page.getMarker();
    for (Metadata m : page.getMetadata()) {
        // m.getName() / m.getSize() / m.getLastModified()
    }

    // 5. 删除
    storage.deleteObject("docs/hello.txt");
}
```

### 3.3 AI 对话（桩实现）示例

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.spi.ServiceProvider;

ChatClient qiniu = ServiceProvider.of(ChatClient.class).getExtension("qiniu");
String reply = qiniu.chatSync("你好");   // 固定返回：七牛云暂不支持 AI 对话
```

`QiniuChatClient` 的流式 `chat(prompt, consumer)` 会依次推送 `START` → `STREAMING`（固定提示）→ `STOP`（固定提示 + `AiUsage`，其中 `model` / `provider` 均为 `qiniu`）三个事件，随后执行完成回调；不会发起任何网络请求。

---

## 4. 配置项

本模块**没有** Spring 配置键（不读取 `application.yml` / properties，源码未提供任何 `@ConfigurationProperties` 或属性绑定）。全部配置通过 `com.chua.common.support.storage.setting.BucketSetting` 以编程方式传入构造器。

`BucketSetting` 字段与七牛实现的实际使用情况：

| 字段 | 类型 | 说明 | 七牛实现是否使用 |
| --- | --- | --- | --- |
| `bucket` | String | 存储空间名 | ✅ 上传凭据、删除、stat、列举均基于它 |
| `accessKeyId` | String | Access Key | ✅ `Auth.create(accessKeyId, accessKeySecret)` |
| `accessKeySecret` | String | Secret Key | ✅ 同上 |
| `endpoint` | String | 下载端点（Bucket 绑定的下载域名） | ✅ 拼接 `endpoint + "/" + key` 生成私有下载链接 |
| `region` | String | 区域 | ❌ 未读取；区域由 SDK 的 `Region.autoRegion()` 自动识别 |
| `connectionTimeoutMills` | long | 连接超时（默认 10000） | ❌ 本实现未读取 |
| `sessionTimeoutMills` | long | 会话超时（默认 10000） | ❌ 本实现未读取 |
| `extraProperties` | Map | 附加参数 | ❌ 本实现未读取 |

> 说明：`bucket` / `accessKeyId` / `accessKeySecret` / `endpoint` 由父类 `AbstractFileStorage` 统一从 `BucketSetting` 解出；`connectionTimeoutMills`、`sessionTimeoutMills`、`extraProperties` 是 `BucketSetting` 的公共字段，本模块的七牛实现未读取它们（源码未提供相应处理逻辑）。

---

## 5. SPI 扩展点

### 5.1 本模块提供的 SPI 实现

| SPI 接口（来自 utils-support-common-starter） | 实现类 | SPI 名称 | 行为 |
| --- | --- | --- | --- |
| `com.chua.common.support.storage.FileStorage` | `com.chua.qiniu.support.storage.QiniuKodoFileStorage`（继承 `AbstractFileStorage`） | `kodo`、`qiniu` | 七牛云 Kodo 对象存储真实实现 |
| `com.chua.common.support.ai.chat.ChatClient` | `com.chua.qiniu.support.QiniuChatClient` | `qiniu` | 桩实现，返回固定提示 |

本模块**不定义新的 SPI 接口**，只消费 common-starter 的 `FileStorage` 与 `ChatClient` 契约。

### 5.2 SPI 注册文件

`src/main/resources/META-INF/services/com.chua.common.support.storage.FileStorage`：

```
com.chua.qiniu.support.storage.QiniuKodoFileStorage
```

`src/main/resources/META-INF/extensions/com.chua.common.support.ai.chat.ChatClient`：

```
qiniu=com.chua.qiniu.support.QiniuChatClient
```

### 5.3 GraalVM native-image 配置

`src/main/resources/META-INF/native-image/com.chua/utils-support-qiniu-starter/` 下提供 `reflect-config.json` / `proxy-config.json` / `resource-config.json`，其中 `reflect-config.json` 对 `QiniuChatClient` 与 `QiniuKodoFileStorage` 开放全部声明/公共构造器、方法与字段的反射访问。

### 5.4 `QiniuKodoFileStorage` 方法一览

| 方法 | 行为要点 |
| --- | --- |
| `QiniuKodoFileStorage(BucketSetting)` | 构建 `Configuration(Region.autoRegion())`、`Auth`、`UploadManager`、`BucketManager` |
| `putObject(PutObjectRequest)` | `auth.uploadToken(bucket)` + `uploadManager.put(content, key, upToken)`；失败返回 `FAILURE` + 错误消息 |
| `getObject(GetObjectRequest)` | `auth.privateDownloadUrl(endpoint + "/" + key, 3600)` 生成私有链接后打开流；元数据含 `name`、`contentType` |
| `getObject(String key)` | 按最后一个 `/` 拆出 `fileName` / `filePath` 后委托给上面的方法 |
| `deleteObject(String key)` | `bucketManager.delete(bucket, key)` |
| `existObject(ExistObjectRequest)` | `bucketManager.stat(bucket, key)`；`QiniuException` code 612 → `exists=false`（仍为 `SUCCESS`） |
| `listObject(ListObjectRequest)` | `bucketManager.createFileListIterator(bucket, filePath, limit, marker)`，只取一页；`marker` 缺省为空串（从头），返回结果的 `marker` 为本页最后一个 key |
| `close()` | 空实现（七牛云客户端无需显式关闭） |

---

## 6. 构建与依赖

### 6.1 依赖清单（pom.xml）

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | SPI 契约、`AbstractFileStorage` 基类、`BucketSetting`、请求/结果模型 |
| `com.qiniu:qiniu-java-sdk` | `7.19.0`（由根 pom `dependencyManagement` 统一托管） | 七牛云官方 Java SDK |
| `org.slf4j:slf4j-api` | 由父 pom 托管 | 日志门面 |
| `org.projectlombok:lombok` | 由父 pom 托管（`provided`） | 注解处理 |

依赖关系：

```
utils-support-qiniu-starter
├── utils-support-common-starter
├── com.qiniu:qiniu-java-sdk
├── org.slf4j:slf4j-api
└── org.projectlombok:lombok (provided)
```

### 6.2 构建

模块随 `utils-support-cloud-parent` 聚合构建。单模块编译（仓库约定命令）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 | 应去哪里 |
| --- | --- | --- |
| 真实 AI 对话 / 图像 / 视频生成 | `QiniuChatClient` 是桩，所有调用返回固定提示 | 其它具备大模型能力的 `*-starter`（如 openai、zhipu 等），或 common-starter 的 `ChatClient` SPI 其它实现 |
| 原生分片上传协议 | `createMultipartStorage()` 使用父类 `AbstractFileStorage` 基于**本地临时目录**的兜底实现 | 需要原生分片协议时须另行扩展 |
| HTTP Range / 断点下推 | `supportsRange()` 保持默认 `false`；`GetObjectRequest` 的 `offset` / `length` 由调用方自行处理 | 调用方按偏移 `skip` 读取 |
| 七牛云对象存储之外的其它产品能力（源码未提供任何相关类） | 未实现 | 按需新建 starter 并走对应 SPI |
| Spring Boot 自动装配 / 配置绑定 | 本模块无 Spring 依赖，无自动配置类，无配置键 | 宿主自行创建 `BucketSetting` 并调用 `FileStorage.createStorage(...)` |
| 管理界面 / Web 控制台 | 未实现 | 上层业务模块 |
