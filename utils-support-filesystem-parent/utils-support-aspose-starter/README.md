# utils-support-aspose-starter

> utils 体系文件系统家族中的 **Aspose 文档格式转换实现模块**：把 Word / Excel / PPT 三类办公文档的格式互转（含转 PDF、HTML 等）以 `FileConvertSystem` SPI 实现的形式提供，由统一入口 `ConvertSupport` 按源/目标格式自动装配。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.aspose.support.converter`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 支持的格式矩阵](#4-支持的格式矩阵)
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
    <artifactId>utils-support-aspose-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Aspose 办公文档格式转换的 SPI 实现集（Word / Excel / PPT 三类，共 3 个 Java 源文件） | 不是文档编辑、渲染、模板填充库 |
| 机制 | 三个 `com.chua.common.support.file.converter.FileConvertSystem` 实现，按 SPI 名 `aspose-word` / `aspose-excel` / `aspose-ppt` 装配 | 不定义新 SPI；统一入口 `ConvertSupport` 与契约接口都在 `utils-support-common-starter` |
| 依赖 | `utils-support-common-starter` + `com.luhuiguo` 重打包的 Aspose 23.1 四件套（words/cells/pdf/slides） | 不依赖 Spring；虽名为 starter，**不是** Spring Boot starter |
| 配置 | 零配置，引入依赖即可用 | 无任何配置项 |

---

## 2. 核心 API 与速查表

### 2.1 三个实现类

| 类 | SPI 名 | 底层 | `@since` |
| --- | --- | --- | --- |
| `AsposeWordFileConvertSystem` | `aspose-word` | Aspose.Words `Document` | 4.0.0.42 |
| `AsposeExcelFileConvertSystem` | `aspose-excel` | Aspose.Cells `Workbook` | 4.0.0.42 |
| `AsposePptFileConvertSystem` | `aspose-ppt` | Aspose.Slides `Presentation` | 4.0.0.42 |

三个类共同实现 `FileConvertSystem` 的两个方法：

```java
boolean isSupported(String sourceType, String targetType);   // 源、目标都在清单内才返回 true
void convert(FileSource source, FileSource target, ConvertSetting setting);  // 执行转换
```

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| Word 转 PDF / HTML / txt 等 | `ConvertSupport.convert("docx", "pdf").from("input.docx").to("output.pdf").convert()` | 自动命中 `aspose-word` |
| Excel 转 PDF / CSV / JSON 等 | `ConvertSupport.convert("xlsx", "pdf")...` | 自动命中 `aspose-excel` |
| PPT 转 PDF 等 | `ConvertSupport.convert("pptx", "pdf")...` | 自动命中 `aspose-ppt` |
| 按 SPI 名直取实现 | `ServiceProvider.of(FileConvertSystem.class).getExtension("aspose-word")` | 三个名字：`aspose-word` / `aspose-excel` / `aspose-ppt` |
| 判断是否支持某转换 | `isSupported(源格式, 目标格式)` | 无需实例化转换即可预判 |
| 流式输入 / 输出 | `FileSource.of(InputStream, type)` / `FileSource.of(OutputStream, type)` | 目标格式此时取 `getType()` |
| ODF / RTF 等冷门源格式 | 源格式清单已含 `odt/ott/rtf`（Word）、`odp/otp`（PPT）、`ods/ots`（Excel） | 不必再配 LibreOffice |
| 失败语义 | 目标格式未映射 → `UnsupportedOperationException`；转换失败 → `RuntimeException` | 消息见 2.3 |

### 2.3 转换执行约定（源码事实）

- 输入：`source.isInputStream()` 为真用其流，否则按 `getPath()` 开 `FileInputStream`；输出同理（`OutputStream` / `FileOutputStream`）。两个流由 try-with-resources 统一关闭——**调用方传入的流也会被关闭**。
- 目标格式解析：目标为路径时取扩展名（`replaceAll(".*\\.", "")`），为目标流时取 `getType()`。
- 目标格式在 `FORMAT_MAP` 中无映射时抛 `UnsupportedOperationException("不支持的目标格式: " + ext)`。
- 异常统一包装为 `RuntimeException`，消息分别为 `Aspose Word 转换失败` / `Aspose PPT 转换失败` / `Aspose Excel 转换失败`，原异常作为 cause 保留。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-aspose-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例（链式入口）

```java
import com.chua.common.support.file.converter.ConvertSupport;

public class Demo {
    public static void main(String[] args) {
        // docx → pdf：ConvertSupport 遍历 FileConvertSystem 的 SPI 实现，
        // 自动匹配到 AsposeWordFileConvertSystem
        ConvertSupport.convert("docx", "pdf")
                .from("input.docx")
                .to("output.pdf")
                .convert();
    }
}
```

### 3.3 流式输入输出

```java
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import com.chua.common.support.file.converter.ConvertSupport;

try (InputStream in = Files.newInputStream(Path.of("input.xlsx"));
     OutputStream out = Files.newOutputStream(Path.of("output.pdf"))) {
    ConvertSupport.convert("xlsx", "pdf")
            .from(in, "xlsx")     // 流方式必须显式给出格式类型
            .to(out, "pdf")
            .convert();
}
```

### 3.4 直接使用 SPI 实现

```java
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.ServiceProvider;

FileConvertSystem word = ServiceProvider.of(FileConvertSystem.class).getExtension("aspose-word");
boolean ok = word.isSupported("docx", "pdf");   // true
word.convert(FileSource.of("input.docx"), FileSource.of("output.pdf"), new ConvertSetting());
```

---

## 4. 支持的格式矩阵

### 4.1 Word：`aspose-word`（Aspose.Words `Document`）

| 方向 | 格式 |
| --- | --- |
| 源 | `doc`、`docx`、`dot`、`dotx`、`docm`、`dotm`、`wps`、`odt`、`ott`、`rtf` |
| 目标 | `pdf`、`doc`、`docx`、`dot`、`dotx`、`html`、`htm`、`txt`、`rtf`、`epub`、`odt`、`xps` |

### 4.2 Excel：`aspose-excel`（Aspose.Cells `Workbook`）

| 方向 | 格式 |
| --- | --- |
| 源 | `xls`、`xlsx`、`xlsm`、`xlsb`、`csv`、`ods`、`ots` |
| 目标 | `pdf`、`xls`、`xlsx`、`xlsm`、`xlsb`、`csv`、`html`、`htm`、`json`、`markdown`、`md`、`xml`、`tsv` |

### 4.3 PPT：`aspose-ppt`（Aspose.Slides `Presentation`）

| 方向 | 格式 |
| --- | --- |
| 源 | `ppt`、`pptx`、`pptm`、`potx`、`potm`、`odp`、`otp` |
| 目标 | `pdf`、`ppt`、`pptx`、`html`、`htm`、`tiff`、`xps` |

### 4.4 源码注释说明的取舍依据

- Word 补 `odt/ott`：Aspose.Words 可直接读取（源码注释实测 odt → pdf 产出 44 KB 有效 PDF）。
- Word 补 `rtf`：SPI 预览对 RTF 的中文会退化成 `???`，而 Aspose 转 PDF 后中文与加粗/斜体完整保留。
- PPT 补 `odp/otp`：Aspose.Slides 可读写（源码注释实测往返 generate → read(slides=2) → pdf 31 KB 全通过）。
- Excel 补 `ods/ots`：Aspose.Cells 可直接读取（源码注释实测 ods → pdf 产出 21 KB 有效 PDF）。

### 4.5 注意（源码事实）

- `docm`/`dotm`/`wps`/`ott`（Word）、`pptm`/`potx`/`potm`/`odp`/`otp`（PPT）、`ods`/`ots`（Excel）只出现在**源**格式清单，不在目标清单——这些格式只能作为输入，不能作为输出目标。注意 `odt` 与 `rtf`（Word）同时出现在源、目标两个清单中，可双向转换。
- PPT 的类注释提到「svg 和常见图片格式」等目标，但代码中的 `TARGETS` 列表并未包含（仅 `pdf/ppt/pptx/html/htm/tiff/xps`）；实际支持范围以 `TARGETS` 为准。
- Excel 侧 `markdown` 与 `md` 等价（均映射 `SaveFormat.MARKDOWN`）；`html` 与 `htm` 等价（三个转换器均如此）。

---

## 5. 配置项

**无。** 依据：

- 模块内没有任何配置类、配置键或 Spring 配置文件；`META-INF` 下仅有 SPI 注册文件与 GraalVM native-image 配置。
- SPI 契约参数 `ConvertSetting`（`charset` / `overwrite` / `startRow` / `limit`）在三个实现的 `convert(...)` 中均未被读取；调用时传默认 `new ConvertSetting()` 即可，参数仅为满足接口契约。

---

## 6. SPI 扩展点

本模块**只实现** `utils-support-common-starter` 中的 `com.chua.common.support.file.converter.FileConvertSystem`，不定义新的 SPI 接口。

装配逻辑（`ConvertSupport.convert()`，位于 common-starter）：

```java
ServiceProvider<FileConvertSystem> sp = ServiceProvider.of(FileConvertSystem.class);
for (FileConvertSystem converter : sp.getNewExtensions(null)) {
    if (converter.isSupported(sourceType, targetType)) {
        converter.convert(source, target, setting);
        return;
    }
}
throw new UnsupportedOperationException("未找到类型转换器: " + sourceType + " -> " + targetType);
```

本模块的 SPI 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`

```text
aspose-excel=com.chua.aspose.support.converter.AsposeExcelFileConvertSystem
aspose-ppt=com.chua.aspose.support.converter.AsposePptFileConvertSystem
aspose-word=com.chua.aspose.support.converter.AsposeWordFileConvertSystem
```

新增一个转换器（参考本模块写法）：

1. 实现类标注 `@Spi("你的名字")`，实现 `isSupported` 与 `convert`；
2. 在自己模块的 `src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem` 中追加一行 `名字=实现类全名`；
3. `ConvertSupport` 即可按源/目标格式自动命中。

---

## 7. 构建与依赖

### 7.1 依赖关系

```text
utils-support-aspose-starter（com.chua:4.0.0.51）
├── com.chua:utils-support-common-starter   ← FileConvertSystem / FileSource / ConvertSetting / ConvertSupport / ServiceProvider
├── com.luhuiguo:aspose-words:23.1          ← AsposeWordFileConvertSystem 使用
├── com.luhuiguo:aspose-cells:23.1          ← AsposeExcelFileConvertSystem 使用
├── com.luhuiguo:aspose-pdf:23.1            ← pom 已声明；本模块源码未提供使用处
└── com.luhuiguo:aspose-slides:23.1         ← AsposePptFileConvertSystem 使用
```

### 7.2 构建

- JDK 25；随 `utils-support-parent-starter` 聚合构建：`mvn -o -q compile -DskipTests`。
- 模块坐标继承自父级 `com.chua:utils-support-filesystem-parent:4.0.0.51`。

### 7.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-aspose-starter/` 下提供：

- `reflect-config.json`：登记三个转换器类（构造器/方法/字段全量开放），供 SPI 反射实例化；
- `resource-config.json`：包含 `META-INF/extensions/.*`，保证 native 模式下 SPI 注册文件可被读取。

---

## 8. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| 统一转换入口 `ConvertSupport`、契约 `FileConvertSystem` / `FileSource` / `ConvertSetting` | `utils-support-common-starter`（`com.chua.common.support.file.converter`） |
| PDF 作为**源**格式的转换（如 pdf → word） | 本模块无 PDF 转换器；`pdf` 仅作为 word / ppt / excel 的**目标**格式出现；`aspose-pdf` 依赖在 pom 中声明，但源码未提供使用处 |
| LibreOffice / OnlyOffice / Pandoc 等其它转换通道 | `utils-support-libreoffice-starter` / `utils-support-onlyoffice-starter` / `utils-support-pandoc-starter` |
| 文档编辑、渲染、模板填充、邮件合并等 Aspose 高级能力 | 本模块只做「打开 → 按目标格式保存」 |
| Excel 的读写与解析（非格式转换） | `utils-support-excel-starter` 等 |
