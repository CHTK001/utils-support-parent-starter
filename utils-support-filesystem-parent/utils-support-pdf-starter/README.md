# utils-support-pdf-starter

> utils 体系文件系统家族中的 **PDF 能力模块**：以 SPI 实现的形式提供 PDF 的文件系统式读写（`FileSystem`）、结构化文本抽取（`TextExtractor`）、表格数据导出（`DocumentGenerator`）、AcroForm 表单模板填充（`TemplateFiller`）与 HTML→PDF 文档渲染（`DocumentProvider`），底层基于 Apache PDFBox。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承根 POM `maven.compiler.source/target=25`）｜包根：`com.chua.pdf.support`

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
    <artifactId>utils-support-pdf-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | PDF 单一格式的 **SPI 实现集**：文件读写、文本抽取、表格导出、表单填充、HTML 转 PDF | 不是 PDF 全功能库：图片提取、加解密、数字签名、页面拆分/合并、渲染预览均为源码未提供 |
| 接口 | 只实现 `utils-support-common-starter` 中定义的 SPI 接口，按 `"pdf"` / `"pdfbox"` / `"pdf-form"` 等名字装配 | 不定义新 SPI 接口；不依赖 Spring，无自动装配 |
| 底层 | Apache PDFBox（`3.0.8`，版本由根 POM 统一管理）；HTML→PDF 依赖外部 `wkhtmltopdf` 可执行程序 | 不内置任何字体文件（PDFBox 的 Standard 14 无法渲染中文，需按第 4 节提供字体） |

**判断口径**：PDF 的全部能力只放本模块；SPI 契约在 common-starter；Word / Excel / OFD / HTML 等其它格式在同父模块（`utils-support-filesystem-parent`）的兄弟 starter。

### 1.3 规模

- Java 源文件 **8** 个，分 4 个子包：`file.impl`、`file.extractor`、`export`、`document`。
- SPI 扩展声明文件 **6** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`、`resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 按“文件系统”读写 PDF | `FileSystem.create("pdf")` → `PdfFileSystem` | `read(File)` / `write(File)` 返回 PDF 专用构建器 |
| 提取全文 / 分页文本 | `PdfReadBuilder.text()` / `pages()` | 支持 `startPage(int)` / `endPage(int)` 页码区间 |
| 读元信息 | `PdfReadBuilder.metadata()` / `title()` / `pageCount()` | PDFBox `PDDocumentInformation`、标题、页数 |
| 结构化抽取（带书签章节） | `TextExtractor.create("pdf")` → `PdfTextExtractor` | 每页一条 `TextExtractResult`，章节取自 PDF 书签（大纲） |
| 生成简单文本 PDF | `PdfWriteBuilder.write(...)` + `finish()` | 单页 A4，标题/作者/字号/边距/行距可调（固定 Helvetica，不支持中文） |
| 数据导出表格 PDF | `Documents.of(rows).output(new File("x.pdf")).export()` → `PdfBoxDocumentGenerator` | 标题、深蓝表头、网格线、自动换行与分页，经 `PdfFonts` 支持中文 |
| AcroForm 表单模板填充 | `Documents.ofTemplate(...)` → `PdfAcroFormTemplateFiller` | 文本域/复选框/单选/下拉，支持 `{{key}}` 占位符 |
| HTML → PDF 文档渲染 | `DocumentProvider.create("pdf")` → `PdfDocumentRenderer` | 渲染 HTML 后调用外部 `wkhtmltopdf`；失败时降级保存 HTML |

---

## 2. 核心 API 与速查表

### 2.1 PdfFileSystem —— 文件系统入口（SPI: `pdf`）

`com.chua.pdf.support.file.impl.PdfFileSystem`，实现 `com.chua.common.support.file.FileSystem`。

- `getType()` 返回 `"pdf"`；
- `read(File)` 返回 `PdfReadBuilder`；
- `write(File)` 返回 `PdfWriteBuilder`。

### 2.2 PdfReadBuilder —— 读取文本

`com.chua.pdf.support.file.impl.PdfReadBuilder`，继承 `ReadBuilder`，基于 PDFBox `Loader.loadPDF` + `PDFTextStripper`。

| 方法 | 说明 |
| --- | --- |
| `startPage(int)` / `endPage(int)` | 页码区间，默认 `1` ～ `Integer.MAX_VALUE`（页码从 1 开始） |
| `text()` | 提取区间内全部文本；IO 失败返回空串 `""` |
| `pages()` | 逐页提取，返回 `List<String>`；每页会回调 `ReadCallback.onBody`，结束回调 `onComplete` |
| `read()` | 等价于 `text()`（满足统一读取契约） |
| `asLines()` / `asString()` | 按换行切分的行列表 / 全文文本 |
| `metadata()` | 返回 `PDDocumentInformation`；IO 失败返回 `null` |
| `title()` | 文档标题（取自元信息） |
| `pageCount()` | 总页数；IO 失败返回 `0` |
| `withCharset(String)` | 覆写为返回 `PdfReadBuilder`，支持链式调用 |

### 2.3 PdfWriteBuilder —— 写入文本 PDF

`com.chua.pdf.support.file.impl.PdfWriteBuilder`，继承 `WriteBuilder`。生成**单页 A4** 文本 PDF，字体固定为 Standard 14 的 `HELVETICA`（因此**不支持中文**；中文表格导出请用 2.5 的 `PdfBoxDocumentGenerator`）。

| 方法 | 默认值 / 说明 |
| --- | --- |
| `title(String)` / `author(String)` | 写入 PDF 元信息（不设则不写） |
| `fontSize(float)` | 字号，默认 `12` |
| `leftMargin(float)` | 左边距（点），默认 `50` |
| `topMargin(float)` | 顶部起始位置（点），默认 `750` |
| `lineSpacing(float)` | 行间距（点），默认 `16` |
| `write(List<String>)` / `write(Object)` | 加入延迟写入队列，`finish()` 时统一落盘 |
| `writeMap(List<Map<String, Object>>)` | Map 行按 `列名: 值` 拼接写入；键可经基类 `columnMapping` 做列名映射 |
| `writeAndFlush(List<String>)` / `writeAndFlushMap(...)` | 实时写入（立即生成文件），并触发 `WriteCallback` 回调 |
| `finish()` | 汇总 `pending` 队列生成 PDF；IO 失败抛 `RuntimeException("PDF 写入失败", e)` |

### 2.4 PdfTextExtractor —— 结构化文本抽取（SPI: `pdf`）

`com.chua.pdf.support.file.extractor.PdfTextExtractor`，实现 `com.chua.common.support.file.txtractor.TextExtractor`。

- `extractText(File)` 返回 `List<TextExtractResult>`：**逐页**提取，跳过空白页；
- 每条结果的 `section` 来自 PDF 书签（文档大纲）解析：按书签树递归，父级路径用 `" > "` 连接（如 `第一章 > 1.1 概述`），并映射到书签指向的页码；
- `TextExtractResult` 是 record，取值方法：`text()`、`section()`、`pageNumber()`（从 1 开始）、`sourceFile()`；
- `type()` 返回 `"pdf"`。

### 2.5 PdfBoxDocumentGenerator —— 表格导出（SPI: `pdfbox` / `pdf`）

`com.chua.pdf.support.export.PdfBoxDocumentGenerator`，实现 `com.chua.common.support.export.spi.DocumentGenerator`，`@Spi(value = {"pdfbox", "pdf"}, order = 0)`。

- `formats()` 返回 `{ExportFormats.PDF}`（即 `"pdf"`），`engine()` 返回 `"pdfbox"`；
- `generate(ExportContext, OutputStream)` 输出 A4 表格 PDF：标题（`DocumentMeta.title`，缺省用 sheet 名）、表头（深蓝底 `RGB(0.12, 0.31, 0.47)` 白字）、灰色网格线、按列宽自动换行、页面写满自动分页；
- 列宽按表头名长度分配（每列钳制在 60～240 点内），`rowsPerSheet > 0` 时按行数强制翻页；
- 字体经 `PdfFonts.load(document)` 解析，默认自动挑选系统中文字体；
- 列为空时抛 `ExportException("没有可导出的列，请检查 @ExportColumn 或列限定参数")`。

### 2.6 PdfAcroFormTemplateFiller —— AcroForm 模板填充（SPI: `pdfbox` / `pdf-form` / `pdf`）

`com.chua.pdf.support.export.PdfAcroFormTemplateFiller`，实现 `com.chua.common.support.export.spi.TemplateFiller`，`@Spi(value = {"pdfbox", "pdf-form"}, order = 0)`。

- PDF 的“模板替换”以 **AcroForm 表单域**为准：表单域全名即标签名，由 `TemplateContext.variables()` 按名填充；
- 支持的域类型：`PDTextField`（写文本）、`PDCheckBox`（`true`/`"Yes"`/`"On"`/`"Y"`/`"1"` 视为勾选）、`PDRadioButton`（取值匹配选项名，先精确后忽略大小写）、`PDComboBox`（匹配选项，无匹配则追加后选中）；
- 变量查找顺序：域全名 → 域短名（`getPartialName()`）→ 变量表忽略大小写匹配；
- 文本域**默认值**中可写 `{{key}}` 占位符（语法与 Excel/Word 侧一致：`{{key}}`、`{{a.b.c}}` 嵌套、`{{items.0}}` 下标），由 common-starter 的 `Placeholders` 引擎统一解析；
- 模板不含 AcroForm 域、或域与变量完全不匹配时抛 `ExportException`；
- 填充后尽力 `refreshAppearances()`（失败忽略），随后 `document.save(out)`。

### 2.7 PdfFonts —— 字体解析器

`com.chua.pdf.support.export.PdfFonts`（final 工具类）。PDFBox 不内置字体，Standard 14 无法渲染中文，因此导出前必须解析到一个覆盖目标字符集的字体文件。解析顺序：

1. 系统属性 `chua.export.pdf.font` 或环境变量 `CHUA_PDF_FONT` 指定的字体文件；
2. 类路径资源：`fonts/pdf-font.ttf` / `.ttc` / `.otf`、`fonts/NotoSansSC-Regular.otf`、`fonts/source-han-sans.otf`；
3. 操作系统字体目录中的候选（Windows：`msyh.ttf`、`simhei.ttf`、`Deng.ttf`、`simsun.ttf`、`msyh.ttc`、`simsun.ttc`；macOS：`PingFang.ttc`、`STHeiti Light.ttc`；Linux：`NotoSansCJK-*.ttc`、`wqy-zenhei.ttc`、`uming.ttc`、`DejaVuSans.ttf` 等），并在字体目录中按 `NotoSansCJK*`、`msyh*`、`simhei*`、`simsun*`、`wqy-zenhei*` 浅层扫描；
4. 兜底：Standard 14 的 `HELVETICA`（**不支持中文**，并输出告警提示用 `-Dchua.export.pdf.font=/path/to/font.ttf` 指定）。

另提供 `cjkAvailable()`（当前环境是否具备中文渲染能力）与 `candidates()`（候选字体名列表，排障用）。`.ttc`/`.otc` 字体集合会按优选子字体名（`MicrosoftYaHei`、`SimSun`、`SimHei`、`DengXian`、`NotoSansCJKsc-Regular`、`PingFangSC-Regular`、`WenQuanYiZenHei` 等）挑选，失败退回集合第一个子字体。

### 2.8 PdfDocumentRenderer —— HTML→PDF 文档渲染（SPI: `pdf`）

`com.chua.pdf.support.document.PdfDocumentRenderer`，实现 `com.chua.common.support.lang.document.DocumentProvider`，`@Spi("pdf")`。

- `getType()` 返回 `"pdf"`，`getExtensions()` 返回 `{".pdf"}`；
- `export(DocumentData, File, DocumentExportConfig)`：先经 `DocumentTemplate.create(config.getTemplateType()).renderHtml(data, config)` 渲染出 HTML，写入临时文件，再调用外部 `wkhtmltopdf` 转 PDF；
- `wkhtmltopdf` 以 `ProcessBuilder` 参数数组直接调用（不经 shell 外壳），依次尝试 `--encoding utf-8 --enable-local-file-access …` 与 `--encoding utf-8 …` 等参数组合；
- 转换失败**不抛错**：把渲染好的 HTML 以同名 `.html` 保存到目标目录并告警；渲染阶段异常则抛 `RuntimeException("PDF 导出失败", e)`；
- `config` 传 `null` 时按 `DocumentExportConfig.builder().format("pdf").templateType(DocumentTemplateType.DEFAULT).build()` 处理。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-pdf-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 读 / 写 PDF（FileSystem 方式）

```java
import com.chua.common.support.file.FileSystem;
import com.chua.pdf.support.file.impl.PdfReadBuilder;
import com.chua.pdf.support.file.impl.PdfWriteBuilder;

FileSystem fs = FileSystem.create("pdf");

// 读：全文、分页、页数
PdfReadBuilder reader = (PdfReadBuilder) fs.read(new File("doc.pdf"));
String all = reader.text();
List<String> pages = reader.startPage(1).endPage(3).pages();
int total = reader.pageCount();

// 写：文本行 → 单页 PDF
((PdfWriteBuilder) fs.write(new File("out.pdf")))
        .title("演示文档")
        .author("chua")
        .fontSize(12f)
        .lineSpacing(16f)
        .write(List.of("第一行", "第二行"))
        .finish();
```

### 3.3 结构化抽取（带书签章节）

```java
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.file.txtractor.TextExtractResult;

for (TextExtractResult r : TextExtractor.create("pdf").extractText(new File("doc.pdf"))) {
    System.out.printf("[第%d页][%s] %s%n", r.pageNumber(), r.section(), r.text());
}
```

### 3.4 数据导出表格 PDF（Documents 门面）

```java
import com.chua.common.support.export.Documents;

// 由输出扩展名 .pdf 自动推断格式，命中 PdfBoxDocumentGenerator
Documents.of(rows)
        .sheet("用户")
        .output(new File("用户.pdf"))
        .export();
```

### 3.5 AcroForm 表单模板填充

```java
// 模板：含 AcroForm 表单域的 PDF；表单域名即标签名
Documents.ofTemplate(Path.of("tpl.pdf"))
        .put("title", "2024 年度报表")
        .bind("rows", rows)
        .output(Path.of("report.pdf"))
        .export();
```

### 3.6 HTML → PDF 文档渲染

```java
import com.chua.common.support.lang.document.DocumentData;
import com.chua.common.support.lang.document.DocumentExportConfig;
import com.chua.common.support.lang.document.DocumentProvider;
import com.chua.common.support.lang.document.DocumentTemplateType;

DocumentData data = DocumentData.builder()
        .title("数据库设计文档")
        .databaseName("demo")
        .build();

DocumentProvider.create("pdf").export(data, new File("db-doc.pdf"),
        DocumentExportConfig.builder()
                .format("pdf")
                .templateType(DocumentTemplateType.DEFAULT)
                .build());
// 注意：需本机可执行 wkhtmltopdf；转换失败时会在同目录得到 db-doc.html
```

---

## 4. 配置项

| 配置 | 位置 | 说明 |
| --- | --- | --- |
| `chua.export.pdf.font` | JVM 系统属性（`-D`） | 指定 PDF 字体文件（`.ttf` / `.otf` / `.ttc`）路径，优先级最高（常量 `PdfFonts.FONT_PROPERTY`） |
| `CHUA_PDF_FONT` | 环境变量 | 同上；未设置系统属性时生效（常量 `PdfFonts.FONT_ENV`） |
| `fonts/pdf-font.ttf` / `.ttc` / `.otf`、`fonts/NotoSansSC-Regular.otf`、`fonts/source-han-sans.otf` | 类路径 | 打包内置字体的约定路径，存在即优先于系统字体 |
| `wkhtmltopdf` | PATH | `PdfDocumentRenderer` 渲染 HTML→PDF 依赖的外部可执行程序，需预先安装 |
| Spring Boot 配置项 | — | **无**。本模块不依赖 Spring、无自动装配类，因此没有任何 `application.yml` 配置键 |

---

## 5. SPI 扩展点

本模块是 **SPI 实现方**。`src/main/resources/META-INF/extensions/` 下的注册表：

| SPI 接口（common-starter 定义） | SPI 名 | 本模块实现 |
| --- | --- | --- |
| `com.chua.common.support.file.FileSystem` | `pdf` | `com.chua.pdf.support.file.impl.PdfFileSystem` |
| `com.chua.common.support.file.txtractor.TextExtractor` | `pdf` | `com.chua.pdf.support.file.extractor.PdfTextExtractor` |
| `com.chua.common.support.export.spi.DocumentGenerator` | `pdfbox`、`pdf` | `com.chua.pdf.support.export.PdfBoxDocumentGenerator` |
| `com.chua.common.support.export.spi.TemplateFiller` | `pdfbox`、`pdf-form`、`pdf` | `com.chua.pdf.support.export.PdfAcroFormTemplateFiller` |
| `com.chua.common.support.lang.document.DocumentProvider` | `pdf` | `com.chua.pdf.support.document.PdfDocumentRenderer` |
| `com.chua.common.support.document.DocumentProvider` | `pdf` | `com.chua.pdf.support.document.PdfDocumentRenderer`（同名旧接口的注册文件，两者均已登记） |

扩展方式与本模块一致：实现 common-starter 的 SPI 接口，标注 `@Spi("名字")`，并在自己的 `META-INF/extensions/<接口全限定名>` 文件中登记 `名字=实现类全名`；本模块各文件即是最简样例。

---

## 6. 构建与依赖

模块层级与依赖关系（版本均为 `4.0.0.51`）：

```text
utils-support-parent-starter（根 POM，JDK 25）
└── utils-support-filesystem-parent（文件系统家族父模块）
    └── utils-support-pdf-starter
        ├── com.chua:utils-support-common-starter（SPI 契约 + 工具，同版本）
        ├── org.apache.pdfbox:pdfbox（3.0.8，版本由根 POM dependencyManagement 统一管理）
        └── org.projectlombok:lombok（provided，由父 POM 注入）
```

编译验证（在本仓库根目录或本模块目录）：

```bash
mvn -o -q compile -DskipTests
```

GraalVM 原生镜像：`native` profile 挂载 `native-maven-plugin`，模块自带 `META-INF/native-image/com.chua/utils-support-pdf-starter/` 下的 `reflect-config.json` 与 `resource-config.json`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| Word / Excel / OFD / HTML 等其它格式 | 同父模块的兄弟 starter：`utils-support-word-starter`、`utils-support-excel-starter`、`utils-support-ofd-starter`、`utils-support-html-starter` 等 |
| SPI 接口本身（`FileSystem`、`TextExtractor`、`DocumentGenerator`、`TemplateFiller`、`DocumentProvider`） | `utils-support-common-starter`，本模块只提供 `"pdf"` 实现 |
| PDF→图片渲染/预览、加密解密、数字签名、页面拆分合并、图片/附件提取、表单**读取**（本模块只做表单**填充**） | 源码未提供 |
| Excel 侧 `{{key}}` / Word 侧模板语法与 `Documents` 门面 | `utils-support-common-starter` 的 `export` 包；本模块只负责 PDF 侧落盘 |
| `wkhtmltopdf` 程序本身的安装 | 外部环境；缺失时 `PdfDocumentRenderer` 降级为输出 HTML 并告警 |

另需注意的两个实现限制（来自源码）：

- `PdfWriteBuilder` 固定使用 `HELVETICA` 字体，**中文无法显示**；需要中文的表格 PDF 请走 `PdfBoxDocumentGenerator`（`PdfFonts` 会自动挑选系统中文字体，或按第 4 节配置字体）。
- `PdfReadBuilder.text()` 在 IO 异常时返回空串、`pageCount()` 返回 `0`、`metadata()` 返回 `null`，调用方需自行判空。
