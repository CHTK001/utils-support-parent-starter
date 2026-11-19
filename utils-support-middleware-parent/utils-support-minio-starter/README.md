# utils-support-minio-starter

> MinIO 对象存储集成模块，支持本地测试和 S3 兼容存储：把 MinIO 接入 utils 体系统一的 `FileStorage` SPI，上传/下载/删除/存在性/列表五个动作一个入口。
>
> 版本：`4.0.0.51`（继承父 POM `utils-support-middleware-parent`）｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.minio.support.storage`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `FileStorage` SPI 的 **MinIO 实现**（`MinioFileStorage`），基于 MinIO Java SDK（`io.minio:minio:8.5.17`） | 不是存储抽象层——抽象与请求/结果模型在 `utils-support-common-starter` |
| 能力 | 上传、下载（含按 Key 直取）、删除、存在性检查、对象列表、分片上传（继承默认实现）、连接释放 | 不含预签名 URL、桶策略、生命周期、跨区复制等 MinIO SDK 高级能力（源码未提供） |
| 接入方式 | 纯编程式：`BucketSetting` 构建配置 → `FileStorage.createStorage("minio", setting)` | 无 Spring Boot 自动装配、无 `application.yml` 配置键、无管理端点 |
| 兼容性 | 面向 S3 兼容存储（MinIO 本身是 S3 兼容对象存储服务器） | 不承诺未经源码验证的其他后端行为 |
| 依赖 | 只依赖 `utils-support-common-starter`、`io.minio:minio`、`slf4j-api`、`lombok`（provided） | 不依赖 Spring、不依赖任何业务 starter |

**判断口径**：本模块只做「MinIO 协议 → `FileStorage` 契约」的翻译；什么时候传、传什么、失败怎么编排，都由调用方决定。

---

## 2. 核心 API 与速查表

本模块唯一的公共类是 `com.chua.minio.support.storage.MinioFileStorage`；日常使用应面向 SPI 接口 `com.chua.common.support.storage.FileStorage`（由 `FileStorage.createStorage(name, setting)` 按 SPI 名字创建实例）。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建 MinIO 存储实例 | `FileStorage.createStorage("minio", setting)` | SPI 名 `minio`；也接受别名 `minio-s3`；构造时自动校验并创建桶（失败仅 debug 日志，不阻断） |
| 上传对象 | `storage.putObject(PutObjectRequest)` | 内容支持 `content` 字节数组 / `file` 本地文件 / `inputStream` 三种输入；成功返回 `PutObjectResult`（含 `getUrl()`、`getKey()`） |
| 下载对象（按完整 Key） | `storage.getObject("docs/hello.txt")` | `getObject(String key)` 自动按最后一个 `/` 拆成 path + fileName 后委托核心方法 |
| 下载对象（请求对象） | `storage.getObject(GetObjectRequest)` | 返回 `GetObjectResult`：`getInputStream()` 内容流 + `getMetadata()`（name/size/contentType/etag） |
| 删除对象 | `storage.deleteObject("docs/hello.txt")` | 返回 `DeleteObjectResult`（`SUCCESS` / `FAILURE` + `getMessage()`） |
| 判断对象是否存在 | `storage.existObject(ExistObjectRequest)` | 底层 `statObject`；MinIO SDK 对不存在对象抛异常，本实现捕获后返回 `exists=false`（结果码仍为 `SUCCESS`） |
| 列出对象 | `storage.listObject(ListObjectRequest)` | 按 `filePath` 前缀列出，`limit` 控制条数（默认 10）；`hasMore = 条数 >= limit`，翻页标记 `marker` 取最后一条的 Key |
| 分片上传 | `storage.createMultipartStorage()` | 继承 `AbstractFileStorage` 的默认实现：分片暂存本地临时目录，`complete` 时合并后走 `putObject` |
| 释放连接 | `storage.close()` | 关闭内部 `MinioClient`；实现了 `AutoCloseable`，可用 try-with-resources |

> Range 读取：`FileStorage.supportsRange()` 本实现未覆盖，返回默认 `false`——`GetObjectRequest` 的 `offset`/`length` 由调用方按 `skip` 语义兜底处理（见 common-starter 的 `AbstractFileStorage.applyRange` 约定）。

---

## 3. 快速开始

### 3.1 启动本地 MinIO（Docker，示例来自源码注释）

```bash
docker run -d --name minio \
  -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"
```

启动后：API 端口 `9000`，控制台 `http://127.0.0.1:9001`。

### 3.2 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-minio-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.3 最短可运行示例

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;

public class MinioDemo {
    public static void main(String[] args) throws Exception {
        // 1. 配置（编程式，无配置文件）
        BucketSetting setting = BucketSetting.builder()
                .endpoint("http://127.0.0.1:9000")
                .bucket("test-bucket")
                .accessKeyId("minioadmin")
                .accessKeySecret("minioadmin")
                .region("us-east-1")
                .build();

        // 2. 通过 SPI 创建实例（按名字 "minio" 命中 MinioFileStorage）
        try (FileStorage storage = FileStorage.createStorage("minio", setting)) {

            // 3. 上传：内容 + 路径 + 文件名（Key 自动规范化为 docs/hello.txt）
            PutObjectResult put = storage.putObject(PutObjectRequest.builder()
                    .filePath("docs")
                    .fileName("hello.txt")
                    .content("Hello MinIO".getBytes())
                    .build());
            System.out.println(put.getResultCode() + " -> " + put.getUrl());

            // 4. 下载：按完整 Key 取回内容流
            GetObjectResult get = storage.getObject("docs/hello.txt");
            System.out.println(new String(get.getInputStream().readAllBytes()));

            // 5. 删除
            storage.deleteObject("docs/hello.txt");
        } // close() 自动释放 MinioClient
    }
}
```

运行前提：本地 MinIO 已按 3.1 启动，且凭据与 `setting` 一致。实例创建时会自动执行 `bucketExists → makeBucket`，桶不存在会自动创建。

---

## 4. 配置项

**本模块是零配置文件模块**：`src/main/resources` 下只有 SPI 注册文件与 GraalVM `native-image` 配置，没有 `application.yml` / `application.properties`，也没有 `@ConfigurationProperties` 绑定。所有配置通过 common-starter 的 `BucketSetting`（编程式 Builder）传入。

`BucketSetting` 字段一览（依据：`com.chua.common.support.storage.setting.BucketSetting` 源码）：

| 字段 | 类型 | 默认值 | 本实现是否消费 |
| --- | --- | --- | --- |
| `endpoint` | `String` | 无 | 是（`MinioClient.builder().endpoint(...)`） |
| `bucket` | `String` | 无 | 是（所有操作的桶名；构造时 ensureBucket） |
| `accessKeyId` | `String` | 无 | 是（`credentials(accessKeyId, accessKeySecret)`） |
| `accessKeySecret` | `String` | 无 | 是（同上） |
| `region` | `String` | 无 | 是（`region(region)`） |
| `connectionTimeoutMills` | `long` | `10 * 1000` | 否（common 契约字段，`MinioFileStorage` 构造未读取） |
| `sessionTimeoutMills` | `long` | `10 * 1000` | 否（同上） |
| `extraProperties` | `Map<String, String>` | 无 | 否（同上） |

> 说明：`MinioFileStorage` 构造器只读取 `endpoint` / `accessKeyId` / `accessKeySecret` / `region` / `bucket` 五项；其余字段属于 `FileStorage` SPI 的通用契约，本实现未消费。除 `BucketSetting` 外，本模块没有其他配置项。

---

## 5. SPI 扩展点

本模块是 `FileStorage` SPI 的**实现方**，不向外暴露新的扩展点。

- **SPI 接口**：`com.chua.common.support.storage.FileStorage`（定义在 `utils-support-common-starter`）。
- **实现类**：`com.chua.minio.support.storage.MinioFileStorage`，注解 `@Spi({"minio", "minio-s3"})`——两个名字都能命中同一实现。
- **注册文件**：`src/main/resources/META-INF/services/com.chua.common.support.storage.FileStorage`，内容为一行 `com.chua.minio.support.storage.MinioFileStorage`。
- **装载方式**：`FileStorage.createStorage(name, bucketSetting)` 内部通过 `ServiceProvider.of(FileStorage.class).getNewExtension(name, bucketSetting)` 完成发现与实例化。
- **GraalVM**：`src/main/resources/META-INF/native-image/com.chua/utils-support-minio-starter/` 下带有 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。
- **想接入别的对象存储**：去实现 common-starter 的 `FileStorage`（或继承 `AbstractFileStorage`）并用 `@Spi` 注册，不要在本模块里扩展；同一能力出现两份实现属于 S0 违规。

---

## 6. 构建与依赖

Maven 坐标：`com.chua:utils-support-minio-starter:4.0.0.51`，父模块 `utils-support-middleware-parent`（其父为 `utils-support-parent-starter`）。

```text
utils-support-minio-starter
├── utils-support-common-starter      （FileStorage SPI、请求/结果模型、AbstractFileStorage）
├── io.minio:minio:8.5.17             （MinIO Java SDK）
├── org.slf4j:slf4j-api
└── org.projectlombok:lombok          （provided）
```

模块内源码只有一个类：`src/main/java/com/chua/minio/support/storage/MinioFileStorage.java`。编译命令（仓库根目录）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 去哪里找 |
| --- | --- |
| `FileStorage` 接口、`BucketSetting`、`PutObjectRequest` 等请求/结果模型、`AbstractFileStorage` 基类 | `utils-support-common-starter`（`com.chua.common.support.storage`） |
| Spring Boot 自动装配、配置文件绑定、管理端点 / Web 控制台 | 源码未提供——本模块无任何 Spring 依赖与 HTTP 端点 |
| 预签名 URL、桶策略、生命周期规则、跨区复制、桶级管理 API | 源码未提供；确需这些能力时应基于本模块下沉扩展，而不是在业务层绕过 SPI 直连 MinIO SDK |
| 原生 S3 Multipart 协议 | 本实现 `createMultipartStorage()` 走的是 `AbstractFileStorage` 的本地临时目录通用实现（分片落本地、complete 时合并上传） |
| Range/断点下推 | `supportsRange()` 未覆盖（`false`），区间读取由调用方 `skip` 兜底 |
| MinIO 服务端的部署、运维、监控 | 仅源码注释提供一条 Docker 启动示例；服务端运维不在本模块职责内 |
| 多桶管理 | 一个 `MinioFileStorage` 实例绑定 `BucketSetting.bucket` 指定的单个桶；多桶请创建多个实例 |
