# utils-support-pandoc-starter

> 基于 **Pandoc** 的通用文档格式转换模块：通过调用本机 `pandoc` 命令行进程，在 markdown、HTML、docx、epub、LaTeX、rst、org、textile、mediawiki 等格式之间交叉转换；本机未安装 Pandoc 时，可按操作系统自动下载安装。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.pandoc.support.converter`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 支持的格式与转换行为](#4-支持的格式与转换行为)
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
    <artifactId>utils-support-pandoc-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 文档转换 | `FileConvertSystem` 的 SPI 实现（SPI 名 `pandoc`），通过外部 `pandoc` 进程做多格式交叉转换 | 不是 Java 实现的文档解析/渲染器，不内置任何 Office/PDF/HTML 解析库 |
| 环境管理 | `PandocEnvironment` 探测本机 pandoc；未安装时先走系统包管理器、再回退 GitHub Release 直接下载（版本 `3.6.4`） | 不负责安装 LaTeX 等其它外部引擎（输出 PDF 需系统已装 LaTeX） |
| 依赖 | 仅依赖 `utils-support-common-starter`（SPI、`FileSource`、命令执行等契约） | 不依赖 Spring、不依赖数据库驱动、不携带业务 starter |
| 配置 | 零配置，行为由源码常量决定 | 无 `application.yml` / `@ConfigurationProperties` 配置项 |

> 说明：本模块名为 `starter`，实为纯 Java 工具模块（无 Spring 依赖、无自动配置类），隶属 `utils-support-filesystem-parent` 聚合模块。

---

## 2. 核心 API 与速查表

### 2.1 类一览

| 类 | 说明 |
| --- | --- |
| `PandocFileConvertSystem` | `FileConvertSystem` 的 SPI 实现，`@Spi("pandoc")`（两个类均为 `@since 4.0.0.42`）。`isSupported(source, target)` 判断格式组合是否支持；`convert(FileSource, FileSource, ConvertSetting)` 执行转换 |
| `PandocEnvironment` | Pandoc 环境探测与自动安装。`getPandocPath()` 返回可执行文件路径（结果静态缓存）；未安装时按「包管理器 → GitHub 直接下载」顺序兜底 |

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| Markdown 转 HTML | `ConvertSupport.convert("md", "html").from("in.md").to("out.html").convert()` | 统一入口按 SPI 自动匹配本模块 |
| docx 转 PDF | `ConvertSupport.convert("docx", "pdf").from("in.docx").to("out.pdf").convert()` | 需系统已安装 LaTeX 引擎 |
| HTML 转 docx | `ConvertSupport.convert("html", "docx").from("in.html").to("out.docx").convert()` | 常见文档互转 |
| 流 → 流转换 | `FileSource.of(inputStream, "md")` + `FileSource.of(outputStream, "html")` | 内存流进出，内部经临时文件 |
| 判断是否支持某组合 | `ServiceProvider.of(FileConvertSystem.class).getExtension("pandoc").isSupported("md", "pdf")` | 源/目标相同返回 `false` |
| 按 SPI 名取实现 | `ServiceProvider.of(FileConvertSystem.class).getExtension("pandoc")` | 注册文件：`META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem` |
| 拿 pandoc 可执行路径 | `PandocEnvironment.getPandocPath()` | 探测 → 自动安装，逐级兜底 |

> `ConvertSupport` / `FileSource` / `ConvertSetting` / `ServiceProvider` 均来自依赖的 `utils-support-common-starter`。

---

## 3. 快速开始

### 3.1 添加依赖

见 [1.1 Maven 坐标](#11-maven-坐标)。

### 3.2 最短可运行示例

```java
import com.chua.common.support.file.converter.ConvertSupport;

public class PandocDemo {
    public static void main(String[] args) {
        // Markdown → HTML；首次调用会自动探测/安装本机 pandoc
        ConvertSupport.convert("md", "html")
                .from("input.md")
                .to("output.html")
                .convert();
    }
}
```

### 3.3 直接使用 SPI 实现

```java
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.ServiceProvider;

FileConvertSystem pandoc = ServiceProvider.of(FileConvertSystem.class).getExtension("pandoc");

boolean ok = pandoc.isSupported("md", "html");
pandoc.convert(FileSource.of("input.md"), FileSource.of("output.html"), new ConvertSetting());
```

### 3.4 流式转换

```java
try (InputStream in = Files.newInputStream(Path.of("input.md"));
     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
    pandoc.convert(FileSource.of(in, "md"), FileSource.of(out, "html"), new ConvertSetting());
    byte[] html = out.toByteArray();
}
```

> 前置条件：本机需存在 `pandoc` 可执行文件；若无，`PandocEnvironment` 会在首次转换时尝试自动安装（Windows 走 MSI、macOS 走 DMG、Linux 走 tar.gz，均来自 GitHub Release `3.6.4`）。自动安装失败时抛 `IllegalStateException`，日志中含手动安装指引。

---

## 4. 支持的格式与转换行为

### 4.1 源格式（`SOURCES`）

md、markdown、html、htm、xhtml、docx、epub、latex、tex、rst、org、textile、mediawiki、opml、creole、commonmark、gfm、tsv、csv、json、doc、odt、ods、odp、pptx、rtf、t2t

### 4.2 目标格式（`TARGETS`）

md、markdown、html、htm、xhtml、html5、docx、epub、epub3、latex、tex、pdf、rst、org、textile、mediawiki、opml、creole、commonmark、gfm、tsv、json、doc、odt、ods、odp、pptx、rtf、asciidoc、man、t2t

### 4.3 扩展名 → Pandoc 格式映射

转换前会把扩展名映射为 Pandoc 格式名，主要映射：`htm`/`xhtml` → `html`、`tex` → `latex`、`doc` → `docx`、`md`/`markdown` → `markdown`；`html5`、`epub3`、`asciidoc`、`man` 等原样传递；无映射时直接使用扩展名本身。

### 4.4 执行行为

- 实际执行命令：`pandoc -f <源格式> -t <目标格式> -o <临时输出> <临时输入>`，超时 **300 秒**。
- 输入为流时先写入临时文件 `pandoc_*.扩展名`；输出先落到 `pandoc_result_*.扩展名`，转换成功后写回目标路径或输出流；临时文件在 `finally` 中删除（源为路径时不删除原文件）。
- 失败行为：格式不支持抛 `IllegalArgumentException`；进程退出码非 0 抛 `RuntimeException`（含 exit code 与 stderr/stdout）；输出为空抛 `RuntimeException("Pandoc 转换结果为空")`。
- `isSupported` 规则：源或目标为 `null` → `false`；源与目标相同（忽略大小写）→ `false`；两者均在支持列表内 → `true`。
- `ConvertSetting`（charset / overwrite / startRow / limit）参数会被接收，但当前实现**未读取**其中任何字段。
- 类注释提及支持「多种幻灯片格式」，但 `TARGETS` 常量中不含 beamer、revealjs、slidy、s5 等 Pandoc 专用幻灯片输出格式（仅含 `pptx`、`odp` 等演示文档格式）；实际支持范围以 `isSupported` 校验的列表为准。
- 输出 PDF 依赖系统已安装的 LaTeX 引擎（类注释说明），本模块不负责安装 LaTeX。

### 4.5 Pandoc 环境探测与自动安装（`PandocEnvironment`）

- 探测顺序：`where pandoc`（Windows）/ `which pandoc`（5 秒超时）→ 常见路径：
  - Windows：`%USERPROFILE%\AppData\Local\Pandoc\pandoc.exe`、`C:\Program Files\Pandoc\pandoc.exe`、`C:\Program Files (x86)\Pandoc\pandoc.exe`
  - macOS：`/usr/local/bin/pandoc`、`/opt/homebrew/bin/pandoc`
  - Linux：`/usr/local/bin/pandoc`、`/usr/bin/pandoc`
- 自动安装顺序：系统包管理器（`detectPackageManagers()` → `installPackageWith(pm, "pandoc")`）→ GitHub Release 直接下载：
  - Windows：`pandoc-3.6.4-windows-x86_64.msi`，`msiexec /i ... /qn /norestart`（120 秒超时）
  - macOS：`pandoc-3.6.4-macOS.dmg`，`hdiutil attach` → `installer -pkg /tmp/pandoc.pkg -target /` → `hdiutil detach`
  - Linux：`pandoc-3.6.4-linux-amd64|arm64.tar.gz`（按 `os.arch` 选择），解压后复制 `bin/pandoc` 到 `/usr/local/bin`（不可写时返回解压路径）
- 全部失败抛 `IllegalStateException("Pandoc 未安装且自动安装失败，请查看上方日志中的手动安装指引")`，并按 OS 打印手动安装指引日志。
- 注意：直接下载使用「信任所有证书」的 HTTPS 连接（跳过 SSL 证书与主机名校验），仅用于从 `https://github.com/jgm/pandoc/releases/download/3.6.4` 下载安装包。

---

## 5. 配置项

**无。**

依据：本模块源码（`pom.xml` + 2 个 Java 文件 + 3 个资源文件）中没有任何 `@ConfigurationProperties`、`@Value`、`application.yml`/`properties` 配置，也不读取除 `user.home`、`os.arch`、`os.name` 之外的系统属性。行为由源码内常量决定：

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `PANDOC_VERSION` | `3.6.4` | 自动下载安装的 Pandoc 版本 |
| `GITHUB_RELEASE` | `https://github.com/jgm/pandoc/releases/download/3.6.4` | 安装包下载基址 |
| `COMMAND_TIMEOUT_SECONDS` | `300` | 单次 pandoc 命令超时（秒） |

---

## 6. SPI 扩展点

- 本模块向 `com.chua.common.support.file.converter.FileConvertSystem` SPI 注册了一个实现：
  - 名称：`pandoc`
  - 实现：`com.chua.pandoc.support.converter.PandocFileConvertSystem`
  - 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`，内容 `pandoc=com.chua.pandoc.support.converter.PandocFileConvertSystem`
- 若要扩展新的转换器（如其它引擎），应在**其它模块**实现 `FileConvertSystem` 并用 `@Spi("名称")` 标注 + 同名 `META-INF/extensions/` 文件登记；`ConvertSupport` 会按 `isSupported` 自动挑选第一个支持的实现。
- 本模块自身不定义新的 SPI 接口，也不提供 `FileConvertSystem` 之外的扩展点。
- GraalVM native-image：`META-INF/native-image/com.chua/utils-support-pandoc-starter/` 内置 `reflect-config.json`（注册 `PandocFileConvertSystem` 全量反射）与 `resource-config.json`（包含 `META-INF/extensions/.*`）。

---

## 7. 构建与依赖

### 7.1 依赖关系

```
utils-support-pandoc-starter  (com.chua:4.0.0.51)
└── utils-support-common-starter  (${project.version})
    └── （SPI、FileSource/ConvertSetting/ConvertSupport、CmdExecutors 等契约与工具）
```

- 父模块：`utils-support-filesystem-parent`（聚合于 `utils-support-parent-starter`）。
- 外部运行时依赖：**本机 pandoc 可执行文件**（未安装时自动下载，见 4.5）；输出 PDF 时另需 LaTeX 引擎。
- 编译仅依赖 lombok（provided）与 `utils-support-common-starter`。

### 7.2 构建命令

```bash
# 仓库根目录 utils-support-parent-starter 下
mvn -o -q compile -DskipTests
```

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属/说明 |
| --- | --- |
| Excel / PDF / Word 等专用格式读写 | 见同仓 `utils-support-excel-starter`、`utils-support-pdf-starter`、`utils-support-word-starter` 等 |
| 文件读写 DSL、归档（zip/tar） | `utils-support-common-starter` 的 `FileSystem` 体系 |
| 统一转换入口与参数模型 | `ConvertSupport` / `FileSource` / `ConvertSetting` 定义在 `utils-support-common-starter` |
| 文档解析/渲染本身 | 本模块只拼装并调用外部 `pandoc` 命令，转换质量取决于所装 Pandoc 版本 |
| LaTeX 引擎安装 | 输出 PDF 需系统自带 LaTeX；本模块不检测、不安装 |
| 其它 SPI 能力（HTTP、存储、任务等） | 均不在本模块；本模块仅注册 `FileConvertSystem` 的 `pandoc` 实现 |
| `ConvertSetting` 参数生效 | 当前 pandoc 实现未读取 charset/overwrite/startRow/limit，设置不生效 |
