# utils-support-onlyoffice-starter

> utils 体系文件转换家族中的 **OnlyOffice 实现**：把 Office 文档交给 OnlyOffice Document Server 在线转换成 PDF，是 `FileConvertSystem` SPI 的一个具体实现模块。
>
> 版本：`4.0.0.51`（继承父 POM）｜JDK：`25`（根 POM `maven.compiler.source`/`maven.compiler.target=25`）｜包名：`com.chua.onlyoffice.support.converter`

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
    <artifactId>utils-support-onlyoffice-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `FileConvertSystem` SPI 的 OnlyOffice 实现（SPI 名 `onlyoffice`），通过 OnlyOffice Document Server 的 `/ConvertService.ashx` 接口做在线格式转换 | 不是 OnlyOffice 的 Java SDK 封装，也不内嵌 Document Server |
| 能力 | `doc`/`docx`/`xls`/`xlsx`/`ppt`/`pptx` → `pdf` 的转换；输入支持文件路径或 `InputStream`，输出支持文件路径或 `OutputStream` | 不支持上述之外的源/目标格式组合（`isSupported` 直接返回 `false`） |
| 依赖 | 只依赖 `utils-support-common-starter`（SPI 契约 + HTTP 客户端）与 lombok | 不依赖 Spring，不是 Spring Boot 自动配置 starter；不依赖 OnlyOffice 官方 SDK |
| 配置 | 仅一个 JVM 系统属性 `onlyoffice.url` | 无配置文件、无 Spring `application.yml` 键、无环境变量读取 |

**判断口径**：凡是「文档转 PDF 走 OnlyOffice 服务」的需求用本模块；需要本地离线转换或其它格式，见第 7 节的同仓兄弟模块。

---

## 2. 核心 API 与速查表

本模块只有 1 个 Java 类：

| 类 | 说明 |
| --- | --- |
| `OnlyOfficeFileConvertSystem` | OnlyOffice 文档转换器，`@Spi("onlyoffice")`，实现 `FileConvertSystem`（`@since 4.0.0.42`） |

它实现的两个 SPI 方法（行为均与源码一致）：

| 方法 | 行为 |
| --- | --- |
| `isSupported(String source, String target)` | `target` 必须是 `pdf`，且 `source` 属于 `doc`/`docx`/`xls`/`xlsx`/`ppt`/`pptx`，否则返回 `false` |
| `convert(FileSource source, FileSource target, ConvertSetting setting)` | 读取源文件全部字节 → `POST {onlyoffice.url}/ConvertService.ashx` → 把响应体原样写入目标；失败时抛 `RuntimeException("OnlyOffice 转换失败", e)` |

常用入口速查：

| 需求 | 入口 |
| --- | --- |
| 最简转换（自动选 SPI 实现） | `ConvertSupport.convert("docx", "pdf").from("input.docx").to("output.pdf").convert()` |
| 从输入流转换 / 转到输出流 | `ConvertSupport.convert("docx", "pdf").from(inputStream, "docx").to(outputStream, "pdf").convert()` |
| 按名字取 OnlyOffice 转换器 | `ServiceProvider.of(FileConvertSystem.class).getExtension("onlyoffice")` |
| 判断转换路径是否支持 | `converter.isSupported("docx", "pdf")` |
| 直接执行转换 | `converter.convert(FileSource.of("in.docx"), FileSource.of("out.pdf"), new ConvertSetting())` |
| 指定 OnlyOffice 服务地址 | JVM 参数 `-Donlyoffice.url=http://host:port` |
| 查看 SPI 登记 | `src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem` |

> `ConvertSupport`、`FileConvertSystem`、`FileSource`、`ConvertSetting`、`ServiceProvider` 均定义在依赖模块 `utils-support-common-starter` 中；本模块只提供 `onlyoffice` 这一个实现。

行为细节（依据源码，使用前请知悉）：

- **`ConvertSetting` 不生效**：本实现的 `convert` 未读取 `setting` 的 `charset`/`overwrite`/`startRow`/`limit`，这些参数对本转换器没有作用。
- **无鉴权**：请求仅 `client.post(url, fileData)`，不附加任何鉴权头或查询参数；如 Document Server 需要鉴权，当前实现不提供。
- **响应即结果**：转换结果就是 HTTP 响应体（`resp.getBody()`），本实现不解析 OnlyOffice 的异步转换流程。
- **失败语义**：网络、IO 等任何异常都被包装为 `RuntimeException("OnlyOffice 转换失败", e)` 抛出。

---

## 3. 快速开始

### 3.1 前置条件

1. 一个可访问的 OnlyOffice Document Server（默认假定 `http://localhost:8088`）；
2. 引入依赖（见 1.1）。

### 3.2 最短可运行示例

```java
import com.chua.common.support.file.converter.ConvertSupport;

public class Demo {
    public static void main(String[] args) {
        // 可省略：默认值就是 http://localhost:8088
        System.setProperty("onlyoffice.url", "http://localhost:8088");

        ConvertSupport.convert("docx", "pdf")
                .from("input.docx")
                .to("output.pdf")
                .convert();   // 转换结果写入 output.pdf
    }
}
```

### 3.3 直接使用 SPI（不经过 ConvertSupport）

```java
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.ServiceProvider;

FileConvertSystem converter = ServiceProvider.of(FileConvertSystem.class).getExtension("onlyoffice");

if (converter.isSupported("xlsx", "pdf")) {
    converter.convert(FileSource.of("report.xlsx"), FileSource.of("report.pdf"), new ConvertSetting());
}
```

> `ConvertSupport.convert(...)` 会在**所有**已注册的 `FileConvertSystem` 实现中取第一个 `isSupported` 通过的实现。若 classpath 上同时存在多个支持同一转换路径的实现（如同仓的 aspose/libreoffice），请按名指定（3.3 的方式），或只引入需要的转换器模块。

---

## 4. 配置项

| 配置 | 读取方式 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `onlyoffice.url` | JVM 系统属性（`System.getProperty`） | `http://localhost:8088` | OnlyOffice Document Server 地址；转换时向 `{onlyoffice.url}/ConvertService.ashx` 发起 POST |

除该系统属性外**无其它配置项**：本模块没有 Spring 依赖、没有配置文件、没有环境变量读取；`ConvertSetting` 的参数对本实现不生效（见第 2 节行为细节）。

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，不是扩展点定义方。

- **SPI 契约**：`com.chua.common.support.file.converter.FileConvertSystem`（定义于 `utils-support-common-starter`）。
- **本模块登记**：`src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`，内容为 `onlyoffice=com.chua.onlyoffice.support.converter.OnlyOfficeFileConvertSystem`；实现类标注 `@Spi("onlyoffice")`。
- **加载方式**：`ServiceProvider.of(FileConvertSystem.class)` 按名字获取；`ConvertSupport.convert(...)` 则遍历全部实现，取第一个 `isSupported(sourceType, targetType)` 通过的实现执行转换。
- **GraalVM native-image**：`src/main/resources/META-INF/native-image/com.chua/utils-support-onlyoffice-starter/` 下提供 `reflect-config.json`（注册实现类全部构造器/方法/字段）与 `resource-config.json`（打包 `META-INF/extensions/.*`），native 编译后 SPI 仍可发现。

若要新增自己的转换器（例如接入其它转换服务）：实现 `FileConvertSystem`，标注 `@Spi("你的名字")`，并按同样格式在自家模块的 `META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem` 登记，无需改动本模块。

---

## 6. 构建与依赖

```text
utils-support-parent-starter (4.0.0.51)
└── utils-support-filesystem-parent (4.0.0.51)
    └── utils-support-onlyoffice-starter (4.0.0.51)
        └── utils-support-common-starter (4.0.0.51)   ← SPI 契约、HttpClient、ConvertSupport
```

- 父模块 `utils-support-filesystem-parent` 统一注入 `lombok`（`provided` scope）。
- JDK 25；根 POM 统一 `maven.compiler.source`/`maven.compiler.target=25`。
- 模块内构建：`mvn compile -DskipTests`；与全仓口径一致可用 `mvn -o -q compile -DskipTests`。
- GraalVM：`utils-support-filesystem-parent` 定义了 `native` profile（`native-maven-plugin` 1.1.14），配合本模块的 native-image 配置文件使用。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| `FileConvertSystem` / `FileSource` / `ConvertSetting` / `ConvertSupport` 等转换契约与统一入口 | `utils-support-common-starter` |
| 其它文档转换实现 | 同仓 `utils-support-aspose-starter`（`AsposeWordFileConvertSystem` / `AsposePptFileConvertSystem` / `AsposeExcelFileConvertSystem`）、`utils-support-libreoffice-starter`（`LibreOfficeFileConvertSystem`）、`utils-support-pandoc-starter`（`PandocFileConvertSystem`）、`utils-support-hprof-starter`、`utils-support-ibd-starter`、`utils-support-html-starter` 等 |
| Excel / Word / PDF 等格式本身的读写 | 同仓 `utils-support-excel-starter`、`utils-support-word-starter`、`utils-support-pdf-starter` 等 |
| 文件系统、对象存储、HTTP 客户端等基础能力 | `utils-support-common-starter`（或对应 starter） |
| OnlyOffice Document Server 本身的部署、配置与鉴权 | 外部服务，需自行部署；本模块只按 `onlyoffice.url` 调用其 `/ConvertService.ashx` |
