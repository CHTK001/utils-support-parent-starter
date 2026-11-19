# utils-support-html-starter

> 文件系统家族中的 **HTML 转换与水印清理**模块：基于 Aspose.HTML 把 EPUB、SVG、MHTML、Markdown 等格式转换为 HTML，并在输出前自动删除 Aspose 评估版注入的水印；另提供可独立使用的 HTML 水印删除工具类。
>
> 版本：`4.0.0.51`｜JDK：`25`（编译 release=25，含 `--enable-preview`）｜包名：`com.chua.html.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 核心 API](#4-核心-api)
  - [4.1 HtmlConvertFileSystem：文件转 HTML](#41-htmlconvertfilesystem文件转-html)
  - [4.2 HtmlWatermarkRemover：水印删除](#42-htmlwatermarkremover水印删除)
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
    <artifactId>utils-support-html-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 转换 | `FileConvertSystem` SPI 的 `aspose-html` 实现：`html`/`htm`/`xhtml`/`svg`/`epub`/`mhtml`/`md` → `html` | 不做 HTML → 其它格式的反向转换 |
| 水印 | 删除 Aspose 评估版注入 HTML 的水印（CSS 样式、div、注释、script、文本行） | 不做通用 HTML 解析/清洗框架 |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `aspose-html` | 不依赖 Spring、不依赖数据库驱动 |
| 配置 | 零配置，引入即用 | 无配置中心、无环境变量读取 |

**判断口径**：需要「某格式 → HTML」找本模块；需要「HTML → 某格式」或其它格式互转，见第 8 节边界。

### 1.3 规模

- Java 源文件 **2** 个：`HtmlConvertFileSystem`、`HtmlWatermarkRemover`（均为 `@since 4.0.0.42`），单包 `com.chua.html.support`。
- SPI 扩展声明文件 **1** 个：`META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`。
- 内置 GraalVM `native-image` 的 `reflect-config.json` / `resource-config.json`。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| EPUB 转 HTML | `ConvertSupport.convert("epub", "html").from("input.epub").to("output.html").convert()` | 自动命中本模块转换器，输出前自动删水印 |
| Markdown 转 HTML | `ConvertSupport.convert("md", "html")...` | 同上 |
| SVG 转 HTML | `ConvertSupport.convert("svg", "html")...` | 同上 |
| MHTML 转 HTML | `ConvertSupport.convert("mhtml", "html")...` | 同上 |
| XHTML/HTM 转 HTML | `ConvertSupport.convert("xhtml", "html")...` | `html`/`htm`/`xhtml` 同理 |
| 按 SPI 名取转换器 | `ServiceProvider.of(FileConvertSystem.class).getExtension("aspose-html")` | 直接操作实现，先 `isSupported` 再 `convert` |
| 删水印（字符串） | `HtmlWatermarkRemover.removeWatermark(String)` | 返回清理后的 HTML 字符串 |
| 删水印（字节） | `HtmlWatermarkRemover.removeWatermark(byte[])` | UTF-8 进、UTF-8 出 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-html-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 文件转 HTML（推荐入口）

```java
import com.chua.common.support.file.converter.ConvertSupport;

public class HtmlConvertDemo {
    public static void main(String[] args) {
        // EPUB → HTML：命中 aspose-html 转换器，输出前自动删除 Aspose 评估水印
        ConvertSupport.convert("epub", "html")
                .from("input.epub")
                .to("output.html")
                .convert();
    }
}
```

`ConvertSupport`（定义在 `utils-support-common-starter`）会遍历所有已注册的 `FileConvertSystem` 实现，找到第一个 `isSupported("epub", "html")` 为真的转换器执行；本模块注册的 `HtmlConvertFileSystem` 即命中该调用。把 `"epub"` 换成 `md` / `svg` / `mhtml` / `xhtml` / `htm` / `html` 同样可用。

### 3.3 直接使用 SPI 实现

```java
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.ServiceProvider;

FileConvertSystem converter =
        ServiceProvider.of(FileConvertSystem.class).getExtension("aspose-html");

boolean supported = converter.isSupported("epub", "html");   // true
converter.convert(
        FileSource.of("input.epub"),
        FileSource.of("output.html"),
        new ConvertSetting());
```

### 3.4 单独删除水印

```java
import com.chua.html.support.HtmlWatermarkRemover;

String cleaned     = HtmlWatermarkRemover.removeWatermark(htmlString);
byte[] cleanedBytes = HtmlWatermarkRemover.removeWatermark(htmlBytes);
```

---

## 4. 核心 API

### 4.1 HtmlConvertFileSystem：文件转 HTML

`@Spi("aspose-html")`，实现 `com.chua.common.support.file.converter.FileConvertSystem`。

| 方法 | 说明 |
| --- | --- |
| `isSupported(String sourceType, String targetType)` | 目标格式为 `html`（忽略大小写），且源格式（忽略大小写）属于 `html`/`htm`/`xhtml`/`svg`/`epub`/`mhtml`/`md` 时返回 `true` |
| `convert(FileSource source, FileSource target, ConvertSetting setting)` | 执行转换；源格式不合法时内部抛 `IllegalArgumentException`（“不支持的文件格式: ...”），但会被统一捕获并包装为 `RuntimeException`（“文件转HTML转换失败”，原异常作为 cause）抛出；其它转换失败同样抛该 `RuntimeException` |

转换行为细节（与源码一致）：

- 源格式从文件路径后缀、URL 路径后缀或 `FileSource.getType()` 推断；均无法识别时视为不支持。
- `source` 为路径时直接读取；为输入流时先落到临时文件（前缀 `html_convert_`）。
- 通过 `com.aspose.html.HTMLDocument` 打开文档，`save(..., new HTMLSaveOptions())` 写入临时输出文件（前缀 `html_result_`），随后 `dispose()` 释放。
- 输出字节先经 `HtmlWatermarkRemover.removeWatermark(byte[])` 清理水印，再写入目标：`target` 是输出流时写出并 `flush`，否则写入 `target.getPath()` 指向的文件。
- 临时输入文件仅在源是输入流时删除；临时输出文件总是删除；删除失败仅记录 `warn` 日志（“删除临时输入文件失败”/“删除临时输出文件失败”），不影响转换结果。

### 4.2 HtmlWatermarkRemover：水印删除

静态工具类，无需实例化。

| 方法 | 说明 |
| --- | --- |
| `removeWatermark(String htmlContent)` | 空内容原样返回；否则按内置正则与关键字清理水印，并压缩多余空行 |
| `removeWatermark(byte[] htmlBytes)` | `null`/空数组原样返回；按 UTF-8 解码 → 清理 → UTF-8 编码返回 |

清理规则（全部忽略大小写）：

1. 内置 4 个正则（`Pattern.DOTALL`）删除含水印关键字（`eval`/`trial`/`watermark`/`demo`）的：CSS 样式块、`<div>` 标签及其内容、HTML 注释、`<script>` 标签及其内容。
2. 按 7 个关键字 `Evaluation Only`、`Created with Aspose`、`Aspose`、`Trial Version`、`Watermark`、`Demo`、`Evaluation`，删除包含该文本的完整标签与文本行。
3. 清理空白行，并把 3 个以上连续换行压缩为 2 个。

---

## 5. 配置项

**无。** 依据：

- 本模块 2 个源文件均不读取任何配置属性、系统属性或环境变量；
- 模块 `pom.xml` 未声明任何配置插件或参数；
- 转换行为参数由调用方通过 `ConvertSetting` 传入（`charset`/`overwrite`/`startRow`/`limit`），属于 API 入参而非模块配置；本模块的 `convert` 实现未使用 `setting` 的字段。

---

## 6. SPI 扩展点

本模块是 **SPI 实现方**，不定义新的扩展点：

- 实现接口：`com.chua.common.support.file.converter.FileConvertSystem`（定义在 `utils-support-common-starter`）。
- 注册名：`aspose-html`（类上标注 `@Spi("aspose-html")`）。
- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`，内容为：

  ```text
  aspose-html=com.chua.html.support.HtmlConvertFileSystem
  ```

- 消费方式：`ConvertSupport.convert(源格式, "html")...` 自动匹配，或 `ServiceProvider.of(FileConvertSystem.class).getExtension("aspose-html")` 手动获取。
- GraalVM 原生镜像：`reflect-config.json` 为 `HtmlConvertFileSystem` 开放全部构造器/方法/字段的反射；`resource-config.json` 打包 `META-INF/extensions/.*` 资源。

> 注意：同仓另有流式入口 `FileConvertSystemFlow`，它走的是另一个 SPI `ConvertFileSystem`（名称形如 `epub2html`）。本模块未注册 `ConvertFileSystem`，因此 `FileConvertSystemFlow` 不会命中本模块能力；请使用 `ConvertSupport`，或按上面的 SPI 名获取实现。

---

## 7. 构建与依赖

```text
utils-support-html-starter（4.0.0.51）
├── com.chua:utils-support-common-starter（${project.version}，SPI 契约与框架）
├── com.aspose:aspose-html:23.1（classifier jdk11，HTML 引擎）
└── org.projectlombok:lombok（provided，由父 pom 统一注入）
```

- Maven 仓库：模块 `pom.xml` 声明 Aspose 专仓 `https://releases.aspose.com/java/repo/`（id `AsposeJavaAPI`，仅启用 releases）。
- JDK：随父工程以 `maven.compiler.source/target=25`、编译器 `<release>25</release>` 编译，并启用 `--enable-preview`；运行需 JDK 25，涉及 preview 特性时运行时也需加 `--enable-preview`。
- 构建命令：在模块或其父模块目录执行 `mvn -o -q compile -DskipTests`，`EXIT=0` 即通过。
- 测试与示例工程：源码未提供。

---

## 8. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| `FileConvertSystem` / `FileSource` / `ConvertSetting` / `ConvertSupport` 等契约与门面 | `utils-support-common-starter` |
| Word / PDF / Excel 等其它格式转换 | `utils-support-word-starter` / `utils-support-pdf-starter` / `utils-support-excel-starter` 等（同在 `utils-support-filesystem-parent` 下） |
| 通用 HTML 解析、DOM 操作、CSS 选择器 | 源码未提供；本模块只做「转 HTML + 删水印」两件事 |
| HTML → 其它格式的反向转换 | 源码未提供；本模块目标格式固定为 `html` |
| Spring 自动装配、配置绑定、配置中心 | 源码未提供；本模块不依赖 Spring |
