# utils-support-word-starter

> utils 体系的 **Word 文件操作模块**：基于 Apache POI（`poi-ooxml`）提供 Word（`.docx`）文档的读取、写入、文本抽取、模板填充、数据导出与数据库设计文档导出，全部以 SPI 实现的形式接入 `utils-support-common-starter` 的统一门面。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.word.support`｜全部类 `@author CH`、`@since 4.0.0.42`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 能力详解](#4-能力详解)
  - [4.1 文件系统读写](#41-文件系统读写fileimpl)
  - [4.2 文本抽取](#42-文本抽取fileextractor)
  - [4.3 模板填充](#43-模板填充export)
  - [4.4 数据导出](#44-数据导出export)
  - [4.5 数据库设计文档](#45-数据库设计文档document)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-word-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Word（`.docx`）读写、文本抽取、模板填充、数据导出的 **SPI 实现集合** | 不是 Word 渲染/格式转换引擎（转 PDF、转图片等） |
| 依赖 | 只依赖 `utils-support-common-starter` 与 Apache POI（`poi-ooxml`） | 不依赖 Spring、不依赖任何第三方模板引擎 |
| 接入 | 通过 common 层的 `FileSystem` / `TextExtractor` / `TemplateFiller` / `DocumentGenerator` / `DocumentProvider` SPI 使用 | 不自带独立门面 API |
| 格式 | OOXML（`.docx`） | 不支持旧版二进制 `.doc` 的解析（模板填充侧明确不支持） |

**判断口径**：需要 Word 的通用文本读写、按标题切片、`{{}}` 模板填充或 docx 数据导出时引入本模块；重型 Word 处理（格式转换、在线预览）请看同仓 `libreoffice` / `aspose` / `onlyoffice` 等 starter。

### 1.3 源码规模

共 7 个 Java 类，分布在 4 个子包：

| 子包 | 类 | 职责 |
| --- | --- | --- |
| `file.impl` | `WordFileSystem`、`WordReadBuilder`、`WordWriteBuilder` | `FileSystem` SPI 与读写构建器 |
| `file.extractor` | `WordTextExtractor` | 文本抽取 SPI |
| `export` | `PoiWordTemplateFiller`、`PoiWordDocumentGenerator` | 模板填充与数据导出 SPI |
| `document` | `WordDocumentRenderer` | 数据库设计文档导出 SPI |

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 读取 .docx 全部文本 | `WordReadBuilder.text()` | 段落文本按 `\n` 拼接；IO 失败返回空串 |
| 按段落读取 | `WordReadBuilder.paragraphs()` / `asLines()` | 每段一个元素 |
| 读取 Word 表格 | `WordReadBuilder.tableRows()` | 首行作表头，返回 `List<Map<String, String>>`，支持 `columnMapping` 重命名键 |
| 获取文档标题 | `WordReadBuilder.title()` | 读文档属性（CoreProperties）的标题 |
| 写入文本 / 键值数据 | `WordWriteBuilder.write(...).finish()` 或 `writeAndFlush(...)` | Map 数据按 `key: value` 逐段写入 |
| 按章节抽取文本 | `WordTextExtractor.extractText(File)` | 按 Heading1~6 样式切分章节，表格文本单独成段 |
| Word 模板填充 | `PoiWordTemplateFiller`（SPI：`poi-word` / `word` / `docx`） | `{{key}}` 标量、嵌套、表格循环、`{{@index}}` 等元信息 |
| 数据导出 / 设计文档 | `PoiWordDocumentGenerator`（SPI：`poi-word` / `word` / `docx`）、`WordDocumentRenderer`（SPI：`word`） | 表格化导出数据行或数据库表结构 |

---

## 3. 快速开始

引入依赖后，按 SPI 名称拿到 Word 文件系统即可读写（示例改编自 `WordFileSystem` 的 javadoc，写入方法名以 `WordWriteBuilder` 的实际 API 为准）：

```java
import com.chua.common.support.file.FileSystem;
import com.chua.word.support.file.impl.WordReadBuilder;
import com.chua.word.support.file.impl.WordWriteBuilder;

import java.io.File;
import java.util.List;
import java.util.Map;

public class WordQuickStart {
    public static void main(String[] args) {
        // 1. 按 SPI 名称创建 Word 文件系统（key：docx / doc）
        FileSystem fs = FileSystem.create("docx");

        // 2. 读取
        WordReadBuilder read = (WordReadBuilder) fs.read(new File("demo.docx"));
        String text = read.text();                          // 全文（段落以 \n 分隔）
        List<String> paragraphs = read.paragraphs();        // 按段落
        List<Map<String, String>> rows = read.tableRows();  // 表格数据（首行作表头）
        String title = read.title();                        // 文档属性标题

        // 3. 写入（延迟写入：先入队，finish() 统一落盘）
        WordWriteBuilder write = (WordWriteBuilder) fs.write(new File("out.docx"));
        write.write(List.of("标题", "正文内容"));
        write.finish();

        // 4. 或实时写入
        write.writeAndFlush(List.of("实时", "写入"));
    }
}
```

Word 模板填充的表格循环语法（取自 `PoiWordTemplateFiller` 的 javadoc）：

```text
| 订单号      | 状态       |   ← 表头
| {{#rows}}   |            |   ← 标记行，填充后移除
| {{orderNo}} | 第{{@index}}条 | ← 模板行，复制 N 次
| {{/rows}}   |            |   ← 标记行，填充后移除
```

---

## 4. 能力详解

### 4.1 文件系统读写（`file.impl`）

**`WordFileSystem`**：`FileSystem` 的 SPI 实现，`@Spi({"docx", "doc"})`；`getType()` 返回 `"docx"`；`read(File)` 返回 `WordReadBuilder`，`write(File)` 返回 `WordWriteBuilder`。

**`WordReadBuilder`**（继承 common 层 `ReadBuilder`）：

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `text()` | `String` | 全部段落文本，`\n` 分隔；IO 异常返回空串 |
| `paragraphs()` | `List<String>` | 段落列表；IO 异常记 debug 日志并返回已读取内容 |
| `tableRows()` | `List<Map<String, String>>` | 所有表格的数据行；首行作表头；配置 `columnMapping` 后按映射重命名键 |
| `title()` | `String` | 文档属性中的标题，读取失败返回 `null` |
| `read()` | `Object` | 等价 `text()` |
| `asLines()` / `asString()` | `List<String>` / `String` | 等价 `paragraphs()` / `text()` |
| `withCharset(String)` | `WordReadBuilder` | 链式设置字符集 |

配置 `withCallback(ReadCallback)` 后，以上读取会依次触发 `onHeader` / `onBody` / `onComplete` 回调。

**`WordWriteBuilder`**（继承 common 层 `WriteBuilder`），支持两种写入方式：

| 方法 | 方式 | 说明 |
| --- | --- | --- |
| `write(List<String>)` / `write(Object)` / `writeMap(List<Map<String, Object>>)` | 延迟写入 | 数据进入暂存区，调用 `finish()` 时统一写入文件 |
| `writeAndFlush(List<String>)` / `writeAndFlushMap(List<Map<String, Object>>)` | 实时写入 | 立即落盘 |

- `finish()` 落盘规则：`String` → 一个段落；`Map` → 每个 entry 写成 `key: value` 段落；配置 `columnMapping` 后键名先做映射。
- 回调：`onStart` → `onBeginWrite` → `onProgress` → `onComplete(true)`；IO 失败时 `onComplete(false)` 并抛 `RuntimeException("Word 写入失败")`。

### 4.2 文本抽取（`file.extractor`）

**`WordTextExtractor`**：`TextExtractor` 的 SPI 实现，`@Spi({"docx", "doc"})`，`type()` 返回 `"docx"`。

`extractText(File)` 的切分规则：

1. 按 Word 内置标题样式（`Heading1`~`Heading6`，含小写变体）识别章节标题，标题之间的段落聚为一条 `TextExtractResult`（内容、所属章节标题、文件名）；
2. 全部表格的单元格文本合并为一条结果，归入最后一个章节；
3. 空文档或只有标题时，返回一条空内容结果兜底；
4. IO 失败时记 warn 日志，返回已收集的结果。

### 4.3 模板填充（`export`）

**`PoiWordTemplateFiller`**：`TemplateFiller` 的 SPI 实现，`@Spi(value = {"poi-word", "word"}, order = 0)`；`formats()` 为 `ExportFormats.DOCX`，`engine()` 为 `"poi-word"`。

入口为 `fill(TemplateContext context, InputStream template, OutputStream out)`：读入模板 → 展开表格循环区块 → 渲染正文段落 → 写出到输出流。

语法（与 Excel / PDF 侧完全一致，占位符由 common 层 `Placeholders` 解析，标签前后缀取自 `ExportConfig#getTagPrefix()` / `getTagSuffix()`）：

| 语法 | 作用 |
| --- | --- |
| `{{key}}` | 标量替换：段落与单元格中替换，格式保持；一个段落内多个占位符同样支持 |
| `{{customer.address.city}}` | 嵌套取值；循环行内未导出为列的字段用 `{{_row.field}}` |
| `{{#tag}}` … `{{/tag}}` | 表格循环：两标记行之间的整行为模板，按数据条数复制；标记行填充后移除，块内无数据时整块消失 |
| `{{@index}}` / `{{@first}}` / `{{@last}}` | 循环元信息 |

**限制**：仅支持 OOXML（`.docx`）；旧版二进制 `.doc` 无法处理。

### 4.4 数据导出（`export`）

**`PoiWordDocumentGenerator`**：`DocumentGenerator` 的 SPI 实现，`@Spi(value = {"poi-word", "word"}, order = 0)`；`formats()` 为 `ExportFormats.DOCX`，`engine()` 为 `"poi-word"`。

入口为 `generate(ExportContext context, OutputStream out)`，按 `DocumentMeta` 从零构建 docx：

- 可选居中加粗标题（字号 16）：优先取 `title`，否则取 `resolveSheetName()`；
- 表头行：深蓝底（`1F4E79`）白字、居中加粗，表格宽度 5000；
- 数据行按 `@ExportColumn` 列顺序写入；`rowsPerSheet > 0` 时自动分页——插入分页符、写 `sheetName (N)` 标题并重建表头；
- 列为空时抛 `ExportException("没有可导出的列，请检查 @ExportColumn 或列限定参数")`。

### 4.5 数据库设计文档（`document`）

**`WordDocumentRenderer`**：`DocumentProvider` 的 SPI 实现，`@Spi("word")`；`getType()` 返回 `"word"`，`getExtensions()` 为 `{".docx", ".doc"}`。

入口为 `export(DocumentData data, File outputFile, DocumentExportConfig config)`：

- 标题段落：居中、加粗、18 号，标题为空时默认「数据库设计文档」；
- 元信息行：数据库、产品名 + 版本、描述；
- 每张表一个 9 列固定表格：序号、列名、类型、大小、小数位、可空、主键、默认值、备注；表头深蓝底（`1F4E79`）白字、微软雅黑；
- 表格带全边框，输出目录不存在时自动创建。

javadoc 说明：先通过 `DocumentTemplate` 渲染 Markdown，再写入 Word 段落/表格结构，保证与 默认 / Swagger 模板内容一致。

---

## 5. 配置项

**无**。依据：本模块源码中没有配置类、配置键或 properties 文件（`src/main/resources` 下只有 SPI 注册文件与 GraalVM native-image 配置），属零配置模块，引入依赖后按 SPI 名称直接使用。

可选的行为开关全部来自 common 层基类/上下文：读写构建器上的 `withCharset` / `columnMapping` / `withCallback`，以及导出侧 `ExportConfig` 的标签前后缀。

---

## 6. SPI 扩展点

本模块**只实现、不定义** SPI：接口全部来自 `utils-support-common-starter`，本模块通过 `src/main/resources/META-INF/extensions/` 登记以下实现：

| SPI 接口（登记文件） | 注册名 | 实现 | order |
| --- | --- | --- | --- |
| `com.chua.common.support.file.FileSystem` | `docx`、`doc` | `WordFileSystem` | — |
| `com.chua.common.support.file.txtractor.TextExtractor` | `docx`、`doc` | `WordTextExtractor` | — |
| `com.chua.common.support.export.spi.TemplateFiller` | `poi-word`、`word`、`docx` | `PoiWordTemplateFiller` | 0 |
| `com.chua.common.support.export.spi.DocumentGenerator` | `poi-word`、`word`、`docx` | `PoiWordDocumentGenerator` | 0 |
| `com.chua.common.support.lang.document.DocumentProvider` | `word` | `WordDocumentRenderer` | — |

注：`META-INF/extensions/` 下另有一份 `com.chua.common.support.document.DocumentProvider` 登记文件，内容同为 `word=WordDocumentRenderer`；但 `WordDocumentRenderer` 实现的是 `lang.document` 包的 `DocumentProvider`，并未实现 `document` 包接口的 `generate(GenerateRequest)`，SPI 装配器（`CustomServiceResolver`）会以「未实现接口」为由跳过该行，实际生效的登记是上表中的 `lang.document.DocumentProvider`。

获取方式：`FileSystem.create("docx")`，或 `ServiceProvider.of(对应接口).getExtension("名称")`。

要扩展 Word 能力（例如新的导出引擎或填充引擎），请实现 common 层对应 SPI 并自行登记，而不是修改本模块。

---

## 7. 构建与依赖

```text
utils-support-word-starter (com.chua:4.0.0.51，父模块 utils-support-filesystem-parent)
├── utils-support-common-starter        ← SPI 契约与门面
└── org.apache.poi:poi-ooxml            ← Word 读写，版本由父 pom 统一管理
```

- lombok 由父模块以 `provided` 引入。
- pom 注释明确：**Word 模板填充由 `PoiWordTemplateFiller` 基于 POI 原生实现，不引入第三方模板引擎，避免 POI 版本耦合**。
- 编译：在 `utils-support-parent-starter` 下执行 `mvn -o -q compile -DskipTests`。
- GraalVM：`src/main/resources/META-INF/native-image/com.chua/utils-support-word-starter/` 内置 `reflect-config.json` 与 `resource-config.json`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| Excel 读写 / 导出 | `utils-support-excel-starter` |
| PDF 读写 / 导出 | `utils-support-pdf-starter` |
| HTML | `utils-support-html-starter` |
| OFD | `utils-support-ofd-starter` |
| 重型 Word 处理（格式转换、在线预览等） | `utils-support-libreoffice-starter` / `utils-support-aspose-starter` / `utils-support-onlyoffice-starter` |
| `FileSystem` / `TextExtractor` / `TemplateFiller` / `DocumentGenerator` / `DocumentProvider` 接口、`Placeholders` 占位符引擎 | `utils-support-common-starter` |

**格式与能力限制**：

- 读取与模板填充均基于 POI XWPF（OOXML），实际仅支持 `.docx`；旧版二进制 `.doc` 无法解析（模板填充 javadoc 明确说明；SPI 名 `doc` 底层同样走 XWPF 实现）。
- `WordWriteBuilder` 只写纯文本段落与 `key: value` 行，不生成复杂样式、图片、目录。
- 模板填充的循环仅作用于表格行；正文段落只做标量替换。
