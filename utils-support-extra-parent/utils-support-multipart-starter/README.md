# utils-support-multipart-starter

基于 Apache Commons FileUpload2 的 `multipart/form-data` 解析器模块：为 common-starter 的 `MultipartParser` SPI 提供名为 `fileupload` 的实现，供 HTTP 服务器实现解析文件上传与表单字段。

- **坐标**：`com.chua:utils-support-multipart-starter`（名称 `Utils Support Multipart Starter`）
- **版本**：`4.0.0.51`（父模块 `utils-support-extra-parent` ← `utils-support-parent-starter`）
- **JDK**：25（根 POM `maven.compiler.release=25`）
- **包名**：`com.chua.multipart.support`

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界：这些能力不在本模块](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

| 它是 | 说明 |
|---|---|
| `MultipartParser` SPI 的实现 | 模块内唯一实现类 `ApacheMultipartParser`，SPI 名称 `fileupload`，注解 `@Spi("fileupload")` + `@SpiDescribe("Apache Commons FileUpload2 解析器")` |
| 文件上传解析器 | `parse(...)` 将 multipart 请求体中的文件部分解析为 `List<FormFile>`（字段名、文件名、内容类型、字节数据） |
| 表单字段解析器 | `parseFormFields(...)` 将 multipart 请求体中的普通表单字段解析为 `Map<String, String>`（UTF-8 解码、保持顺序） |
| GraalVM Native Image 友好 | 自带 `META-INF/native-image/com.chua/utils-support-multipart-starter/` 下的 reflect / proxy / resource 配置 |

| 它不是 | 说明 |
|---|---|
| 不是 HTTP 服务器 | 服务器能力在 common-starter 的 `com.chua.common.support.network.server` 体系，本模块只提供解析器 |
| 不做文件落盘 / 存储 | 只把文件内容读入内存字节交给调用方，不提供保存到磁盘或对象存储的能力 |
| 不解析其他请求体 | 仅当 Content-Type 转小写后以 `multipart/form-data` 开头时支持；JSON、`application/x-www-form-urlencoded` 等不在范围 |
| 不是 Spring Boot Starter | 无自动装配类、无 `AutoConfiguration.imports`、无配置元数据；「starter」仅为本仓模块命名惯例 |
| 不支持流式 / 分片上传 | SPI 契约是整包字节数组 `byte[] body` 一次性解析 |

---

## 核心 API 与速查表

模块源码只有一个类：`com.chua.multipart.support.ApacheMultipartParser`（`@author CH`，`@since 2026/07/17`）。

| 入口 | 签名 / 形式 | 说明 |
|---|---|---|
| 获取解析器（推荐） | `ServiceProvider.of(MultipartParser.class).getExtension("fileupload")` | common-starter 内部消费方（`HttpServerRequest`、`NioServerRequest`）即以此方式获取 |
| 直接实例化 | `new ApacheMultipartParser()` | 公有无参构造；native-image `reflect-config.json` 已登记其全部构造器 / 方法 / 字段 |
| `support` | `boolean support(String contentType)` | `contentType` 非空且转小写后以 `multipart/form-data` 开头时返回 `true` |
| `parse` | `List<FormFile> parse(byte[] body, String contentType)` | 解析文件部分；`body` 为 null / 空或 `contentType` 为 null 时返回空列表；跳过普通表单字段；解析失败（IOException）返回空列表 |
| `parseFormFields` | `Map<String, String> parseFormFields(byte[] body, String contentType)` | 只解析普通表单字段；返回有序 Map（`Wraps.linkedMap()`），值为 UTF-8 解码字符串；`body` 为 null / 空或解析失败时返回空 Map |
| `FormFile`（契约类，来自 common-starter） | `getFieldName()` / `getFileName()` / `getContentType()` / `getData()` / `getSize()` | 上传文件描述：表单字段名、原始文件名、内容类型、字节数据、大小 |
| SPI 注册文件 | `META-INF/services/com.chua.common.support.network.server.request.MultipartParser` | 文件内容为 `com.chua.multipart.support.ApacheMultipartParser` |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-multipart-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最短可运行示例

调用方式与本仓 common-starter 消费方一致（见 `HttpServerRequest` / `NioServerRequest`）：

```java
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.MultipartParser;
import com.chua.common.support.spi.ServiceProvider;

MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");

if (parser.support(contentType)) {
    // 文件部分：字段名 / 文件名 / 内容类型 / 字节数据
    for (FormFile file : parser.parse(body, contentType)) {
        String fieldName = file.getFieldName();
        String fileName = file.getFileName();
        String fileContentType = file.getContentType();
        byte[] data = file.getData();
        long size = file.getSize();
    }
    // 普通表单字段：字段名 -> 值（UTF-8）
    Map<String, String> fields = parser.parseFormFields(body, contentType);
}
```

---

## 配置项

**无。** 依据：

- 模块 `pom.xml` 仅声明依赖，未定义任何可配置属性；
- `src/main/resources` 下仅有 SPI 注册文件与 native-image 配置，无 `application.yml` / `*.properties` / `AutoConfiguration.imports`；
- Java 源码中无配置类（无 `@ConfigurationProperties` 等）。

解析行为在代码中固定：字符集为 UTF-8；FileUpload2 临时目录取系统属性 `java.io.tmpdir`；流读取缓冲区为 8192 字节。

---

## SPI 扩展点

- **SPI 接口**：`com.chua.common.support.network.server.request.MultipartParser`（定义于 common-starter，接口带 `@Spi` 注解）。抽象方法为 `parse` 与 `support`；`parseFormFields` 为 default 方法（默认返回空 Map）。
- **本模块注册方式**：标准 Java SPI 文件 `META-INF/services/com.chua.common.support.network.server.request.MultipartParser`，注册实现 `com.chua.multipart.support.ApacheMultipartParser`，SPI 名称 `fileupload`。
- **消费方**：common-starter 的 `HttpServerRequest` 与 `NioServerRequest` 在处理 multipart 请求时，通过 `ServiceProvider.of(MultipartParser.class).getExtension("fileupload")` 拉取实现。
- **替换 / 新增实现**：common-starter 本身未内置其他 `MultipartParser` 实现；接口 javadoc 亦说明「默认无内置实现，可通过扩展模块引入」。如需替换，按同一 `META-INF/services` 机制注册自己的实现类即可。

---

## 构建与依赖

```text
utils-support-parent-starter (com.chua:4.0.0.51, JDK 25)
└── utils-support-extra-parent
    └── utils-support-multipart-starter (本模块)
```

| 依赖 | 版本 | 说明 |
|---|---|---|
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | SPI 契约（`MultipartParser`、`FormFile`）、`@Spi` / `@SpiDescribe`、`ServiceProvider`、`Wraps` |
| `org.apache.commons:commons-fileupload2-jakarta` | `${commons.fileupload2.version}`（2.0.0-M1） | 提供 `JakartaServletFileUpload` |
| `org.apache.commons:commons-fileupload2-core` | `${commons.fileupload2.version}`（2.0.0-M1） | 提供 `DiskFileItemFactory`、`FileItemInput`、`RequestContext` 等 |
| `jakarta.servlet:jakarta.servlet-api` | 6.1.0（`provided`） | FileUpload2 jakarta 适配的编译期依赖 |

构建命令（仓库约定）：

```bash
mvn -o -q compile -DskipTests
```

测试：本模块当前无 `src/test` 测试源码。

---

## 边界：这些能力不在本模块

| 能力 | 所在位置 |
|---|---|
| `MultipartParser` 接口、`FormFile` 契约类 | `utils-support-common-starter`（`com.chua.common.support.network.server.request`） |
| SPI 框架（`@Spi`、`@SpiDescribe`、`ServiceProvider`） | `utils-support-common-starter`（`com.chua.common.support.spi`） |
| HTTP 服务器与请求处理（`HttpServerRequest`、`NioServerRequest`） | `utils-support-common-starter`（`com.chua.common.support.network.server`） |
| `Wraps` 集合工具 | `utils-support-common-starter`（`com.chua.common.support.collection.wrap`） |
| 文件存储 / 上传落盘 | 本模块不提供（见「它不是」） |
| 配置项与自动装配 | 本模块无（见「配置项」） |
