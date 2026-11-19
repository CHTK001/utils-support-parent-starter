# utils-support-tika-starter

> utils 体系中的**通用文档文本提取模块**：基于 Apache Tika 的 `AutoDetectParser` 自动识别文件类型并抽取纯文本，一个 SPI 入口（`tika`）覆盖 PDF、Word、Excel、HTML 等多种格式。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 POM `maven.compiler.source/target=25`）｜包名：`com.chua.tika.support.file.extractor`

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
    <artifactId>utils-support-tika-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父模块：`utils-support-extra-parent`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 基于 Apache Tika 的**通用文本提取 SPI 实现**：一个入口吃多种文档格式，返回带页码/标题的结构化片段 | 不是格式转换/渲染库；不是某个单一格式的深度解析器（PDF/Word 专用能力在各自 starter） |
| 接口 | 只实现 `utils-support-common-starter` 的 `TextExtractor` 扩展点，SPI 名 `"tika"` | 不定义新 SPI 接口；不依赖 Spring，无自动装配 |
| 行为 | `AutoDetectParser` 自动检测类型；提取失败返回空列表，不抛异常 | 无任何配置项；不支持 `InputStream`/字节数组入参（契约只收 `java.io.File`） |

**判断口径**：只想「把一个文档变成文本」且不关心具体格式时用本模块；要对 PDF/Word 做深度操作（页区间、书签、表单、导出等）时用对应专用 starter。

支持格式（类注释口径，「包括但不限于」）：PDF、Word（`.docx`/`.doc`）、Excel（`.xlsx`/`.xls`）、PowerPoint（`.pptx`）、HTML、XML、CSV、RTF、EPUB、邮件（`.msg`/`.eml`）等。

### 1.3 规模

- Java 源文件 **1** 个：`TikaTextExtractor`（`@Spi("tika")`，`@since 4.0.0.42`）。
- SPI 扩展声明文件 **1** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置 **3** 个：`reflect-config.json`、`proxy-config.json`、`resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿一个 Tika 提取器 | `TextExtractor.create("tika")` | SPI 名固定为 `tika`；找不到实现时抛 `IllegalArgumentException` |
| 提取结构化文本 | `extractText(File)` → `List<TextExtractResult>` | 每条含 `text()` / `section()` / `pageNumber()` / `sourceFile()` |
| 只要合并全文 | `extractFullText(File)` | 接口 default 方法，把所有片段用 `\n` 拼接 |
| 按扩展名自动路由 | `TextExtractor.auto(File)` | xlsx/xls/xlsm 与未识别格式兜底走 `tika` |
| 判断是否有结构信息 | `TextExtractResult.hasStructure()` | 有章节名或页码即为 true |
| 查看 SPI 名 | `type()` | 返回 `"tika"` |

---

## 2. 核心 API 与速查表

### 2.1 TikaTextExtractor —— 唯一的实现类

`com.chua.tika.support.file.extractor.TikaTextExtractor`，实现 `com.chua.common.support.file.txtractor.TextExtractor`，注解 `@Spi("tika")`。

| 方法 | 说明 |
| --- | --- |
| `extractText(File)` | 内部持有 `AutoDetectParser`，解析时经 `ToTextContentHandler` 收集纯文本；文本为空返回空列表；发生 `IOException` / `SAXException` / `TikaException` 时记录 warn 日志并返回空列表（不抛出） |
| `type()` | 返回 `"tika"` |

### 2.2 TextExtractResult —— 结果结构

`com.chua.common.support.file.txtractor.TextExtractResult`（record，定义在 common-starter）：

| 取值方法 | 含义 |
| --- | --- |
| `text()` | 文本内容 |
| `section()` | 章节/标题名；本模块取自文档元数据标题，无则空字符串 |
| `pageNumber()` | 页码，从 1 开始；无页码信息时为 0 |
| `sourceFile()` | 来源文件名（`file.getName()`） |
| `hasStructure()` | 是否有章节名或页码 |

### 2.3 提取行为细节（源码事实）

- 解析前把文件名写入元数据 `TikaCoreProperties.RESOURCE_NAME_KEY`；
- 页数取自元数据 `xmpTPg:NPages`，解析失败按 0 处理（仅记 debug 日志）；
- 标题先取 `dc:title`、再取 `title`，均无则为空串；
- 正文按空行（`\n\s*\n`）切段：有页数元数据时按「段落数 ÷ 页数」**大致等分**到每页（源码注释：简单分块，每 N 段为一页），每块一条结果、页码从 1 递增；无页数或只有一段时返回单条结果（页码 0）。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tika-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 提取文本

```java
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.file.txtractor.TextExtractResult;

import java.io.File;
import java.util.List;

// 1) 显式用 tika 提取，得到分段结果
List<TextExtractResult> results =
        TextExtractor.create("tika").extractText(new File("document.pdf"));
for (TextExtractResult r : results) {
    System.out.printf("[第%d页][%s] %s%n", r.pageNumber(), r.section(), r.text());
}

// 2) 只要合并后的纯文本
String full = TextExtractor.create("tika").extractFullText(new File("report.docx"));

// 3) 按扩展名自动路由：pptx/html/msg 等未识别格式兜底落到 tika
TextExtractor extractor = TextExtractor.auto(new File("slides.pptx"));
```

---

## 4. 配置项

**无。** 依据：模块源码只有 `TikaTextExtractor` 一个类，未读取任何系统属性、环境变量或配置文件，也没有配置类；Tika 标准解析器由 `AutoDetectParser` 在运行期按 classpath 自动发现。唯一可选项是 SPI 名称 `tika`。

---

## 5. SPI 扩展点

- 本模块**不定义**新扩展点；它自身是 common-starter 中 `com.chua.common.support.file.txtractor.TextExtractor` 扩展点的一个实现，SPI 名 `tika`。
- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.file.txtractor.TextExtractor`，内容一行：

```text
tika=com.chua.tika.support.file.extractor.TikaTextExtractor
```

- `TextExtractor.auto(File)` 的路由口径（定义在 common-starter）：`.xlsx`/`.xls`/`.xlsm` → `tika`；`.pdf` → `pdf`；`.docx`/`.doc` → `docx`；`.csv`/`.tsv` → `csv`；`.txt` → `txt`；**其余（pptx/ppt/html/xml/rtf/epub/msg/eml 等）兜底 → `tika`**。
- 要新增别的提取器实现：在自己的模块里实现 `TextExtractor`，并在自身的 `META-INF/extensions/com.chua.common.support.file.txtractor.TextExtractor` 登记，无需改动本模块。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（根 POM `maven.compiler.source/target=25`）；
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译（`@Slf4j`）。

```bash
mvn -pl utils-support-extra-parent/utils-support-tika-starter -am clean package
```

### 6.2 依赖清单（pom.xml 事实）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM | `TextExtractor` 契约与 SPI 装配 |
| `org.apache.tika:tika-core` | 2.9.3 | 解析内核与类型检测 |
| `org.apache.tika:tika-parsers-standard-package` | 2.9.3 | 标准格式解析器集合（已排除 `pdfbox-debugger`） |
| `org.apache.pdfbox:pdfbox` | 未显式声明（父 POM 管理） | PDF 解析；POM 注释说明为「离线环境兜底」显式钉住 |
| `org.apache.pdfbox:fontbox` / `pdfbox-tools` / `xmpbox` | 2.0.33 | PDF 字体/工具/元数据（离线兜底；`pdfbox-tools` 同样排除 `pdfbox-debugger`） |
| `org.apache.pdfbox:jempbox` | 1.8.17 | 旧版 PDF 元数据 |
| `org.apache.pdfbox:jbig2-imageio` | 3.0.5 | JBIG2 图像解码 |
| `org.apache.pdfbox:pdfbox-io` | 3.0.8 | PDF IO |

### 6.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-tika-starter/` 下：

- `reflect-config.json`：`TikaTextExtractor`、`TextExtractor`、`TextExtractResult`、`AutoDetectParser`、`ParseContext`、`Metadata`、`ToTextContentHandler`；
- `proxy-config.json`：`TextExtractor` 接口代理；
- `resource-config.json`：包含 `META-INF/services/**`、`META-INF/extensions/**`、`org/apache/tika/mime/**`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 / 去向 |
| --- | --- |
| PDF 深度能力（页区间读取、书签章节、AcroForm 表单、表格导出、HTML→PDF） | 在 `utils-support-pdf-starter` |
| Word 专用能力 | 在 `utils-support-word-starter` |
| OCR 图片文字识别 | 源码未提供 |
| `InputStream` / `byte[]` 直接提取 | 接口契约只接受 `java.io.File`，源码未提供 |
| 按真实页边界精确切分 | 源码未提供；有页数元数据时按段落大致等分近似 |
| 文件格式转换（如 docx→pdf）、渲染、水印、加解密 | 源码未提供 |
| Spring 自动装配 / 配置项 | 源码未提供；本模块是纯库，不依赖 Spring |

---

## 许可证

Apache License 2.0
