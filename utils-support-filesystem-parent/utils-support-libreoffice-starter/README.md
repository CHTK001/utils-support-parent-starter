# utils-support-libreoffice-starter

> utils 体系的 **LibreOffice 文档格式转换模块**：通过命令行调用本机安装的 LibreOffice（`soffice`），实现 doc/docx/xls/xlsx/ppt/pptx/odt/ods/odp 等 Office 格式的相互转换；它是 common 层 `FileConvertSystem` SPI 的一个实现（注册名 `libreoffice`）。
>
> 版本：`4.0.0.51`（继承父 POM `utils-support-filesystem-parent`，转换器类自 `4.0.0.42` 起提供）｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.libreoffice.support.converter`

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
    <artifactId>utils-support-libreoffice-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 实现 | `LibreOfficeFileConvertSystem`：用 `ProcessBuilder` 执行 `soffice --headless --convert-to <目标格式> --outdir <目录> <源文件>` 完成格式转换 | 不通过 UNO API 嵌入 LibreOffice 进程（2026-09-28 已移除 juh/jurt/ridl/unoil/officebean 五件套，全仓无 `com.sun.star` 引用） |
| 能力 | 源格式 `doc / docx / xls / xlsx / ppt / pptx / odt / ods / odp` 到任意非同格式目标的转换 | 不做文档内容解析、文本抽取、模板填充、页面渲染 |
| 依赖 | 仅依赖 `utils-support-common-starter`，零第三方转换库 | 不依赖 LibreOffice 的 Java SDK；`soffice` 是**运行期外部前提**，需本机安装并加入 `PATH` |
| 形态 | 纯工具库（名字里的 starter 只是命名习惯） | 无 Spring 自动装配、无配置中心接入、无 Controller/端点 |

**判断口径**：需要把 Office 文档转成另一种 Office/PDF 等格式、且运行环境能装 LibreOffice 时用本模块；要解析文档内容或生成文档，请找 common 层的文档/导出体系，不要用本模块。

---

## 2. 核心 API 与速查表

### 2.1 本模块的唯一实现类

`com.chua.libreoffice.support.converter.LibreOfficeFileConvertSystem`，注解 `@Spi("libreoffice")`，实现 `com.chua.common.support.file.converter.FileConvertSystem`。

| 方法 | 签名 | 说明 |
| --- | --- | --- |
| `isSupported` | `boolean isSupported(String source, String target)` | 源格式在 9 种支持列表内、且 `source != target` 即返回 `true`；**目标格式不做白名单校验** |
| `convert` | `void convert(FileSource source, FileSource target, ConvertSetting setting)` | 执行转换；源支持路径或输入流，目标支持路径或输出流；失败抛 `RuntimeException("LibreOffice 转换失败", e)` |

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| docx → pdf（最常用） | `ConvertSupport.convert("docx", "pdf").from("input.docx").to("output.pdf").convert();` | 统一入口，自动命中本转换器 |
| 任意支持的格式互转 | `ConvertSupport.convert(源格式, 目标格式).from(路径).to(路径).convert();` | 9 种源格式 × 任意不同目标格式 |
| 流 → 流转换 | `.from(InputStream, "docx").to(OutputStream, "pdf").convert();` | 流式输入会先落临时文件再交给 soffice |
| 按 SPI 名直接取实现 | `ServiceProvider.of(FileConvertSystem.class).getExtension("libreoffice");` | 绕过统一入口直接调用 |
| 判断某条转换路径是否支持 | `converter.isSupported("docx", "pdf");` | 同源同格式返回 `false` |
| 查看全部已注册转换器 | `ServiceProvider.of(FileConvertSystem.class).list();` | 名称 → 实例 |
| 传递转换参数 | `.setting(new ConvertSetting());` | 见第 4 节：当前实现未消费该参数 |

> 涉及的 common 层类型：`com.chua.common.support.file.converter.ConvertSupport`（链式统一入口：`convert/from/to/setting/convert()`）、`FileSource`（`of(String)` / `of(URL)` / `of(URL, String)` / `of(InputStream, String)` / `of(OutputStream, String)`）、`ConvertSetting`、`FileConvertSystem`。

---

## 3. 快速开始

**运行前提**：本机已安装 LibreOffice，且 `soffice` 命令在 `PATH` 上（Windows 下一般为 `soffice.exe`）。`soffice` 不存在时，`ProcessBuilder.start()` 抛出的 `IOException` 会被包装成 `RuntimeException("LibreOffice 转换失败")` 抛出。

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-libreoffice-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 文件到文件（最短可运行示例）

```java
import com.chua.common.support.file.converter.ConvertSupport;

public class LibreOfficeDemo {
    public static void main(String[] args) {
        // docx → pdf；实际执行 soffice --headless --convert-to pdf --outdir D:/tmp D:/tmp/input.docx
        ConvertSupport.convert("docx", "pdf")
                .from("D:/tmp/input.docx")
                .to("D:/tmp/output.pdf")
                .convert();
    }
}
```

### 3.3 流到流

```java
import com.chua.common.support.file.converter.ConvertSupport;

try (InputStream in = Files.newInputStream(Path.of("D:/tmp/input.xlsx"));
     OutputStream out = Files.newOutputStream(Path.of("D:/tmp/output.pdf"))) {
    ConvertSupport.convert("xlsx", "pdf")
            .from(in, "xlsx")
            .to(out, "pdf")
            .convert();
}
```

### 3.4 直接使用 SPI 实现

```java
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.spi.ServiceProvider;

FileConvertSystem converter = ServiceProvider.of(FileConvertSystem.class).getExtension("libreoffice");

if (converter.isSupported("docx", "pdf")) {
    converter.convert(FileSource.of("D:/tmp/input.docx"),
            FileSource.of("D:/tmp/output.pdf"),
            new ConvertSetting());
}
```

### 3.5 行为细节（与源码一致，使用前须知）

| 行为 | 说明 |
| --- | --- |
| 输出文件名 | `soffice` 按源文件主名生成结果（如 `input.docx` → `input.pdf`）；目标路径只决定**输出目录与目标扩展名**，`to("D:/tmp/output.pdf")` 实际得到 `D:/tmp/input.pdf` |
| 输出目录 | 目标是路径时取其父目录；目标是输出流时写入 `java.io.tmpdir`，再把结果文件内容转入该输出流 |
| 流式输入 | 先写入 `java.io.tmpdir` 下 `lo_` 前缀临时文件（后缀为 `.` + 源类型），当前实现**不会自动删除**临时文件，需自行清理 |
| 转换参数 | `ConvertSetting`（charset/overwrite/startRow/limit）会传入 `convert(...)`，但当前实现未使用 |
| 失败语义 | 进程退出码非 0 → `IOException("LibreOffice 转换失败, exit=N")`；其余异常统一包装为 `RuntimeException("LibreOffice 转换失败", e)` |
| 进程输出 | `redirectErrorStream(true)` 合并了 soffice 的输出，但代码未读取该流；soffice 大量输出时存在管道缓冲写满阻塞的风险 |

---

## 4. 配置项

**无。**

依据：本模块源码不读取任何配置属性、系统属性或配置文件键，也没有 Spring 配置类；唯一与“配置”相关的类型是 common 层的 `ConvertSetting`（默认值：`charset=null`、`overwrite=true`、`startRow=0`、`limit=0`），但它在本模块的 `convert` 实现中**未被消费**——即调整这些参数对 LibreOffice 转换没有效果。

soffice 本身的转换行为（如滤镜参数、超时）当前未暴露任何配置入口。

---

## 5. SPI 扩展点

本模块**不提供扩展点**，它是 common 层 `com.chua.common.support.file.converter.FileConvertSystem` SPI 的**实现方**：

- 注册名：`libreoffice`
- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`
  ```
  libreoffice=com.chua.libreoffice.support.converter.LibreOfficeFileConvertSystem
  ```

若要新增自己的文档转换器（例如基于 Aspose、WPS 或云服务）：

1. 实现接口：
   ```java
   @Spi("aspose-word")
   public class MyConverter implements FileConvertSystem {
       public boolean isSupported(String sourceType, String targetType) { /* ... */ }
       public void convert(FileSource source, FileSource target, ConvertSetting setting) { /* ... */ }
   }
   ```
2. 在自己模块的 `src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem` 中登记 `名称=实现类全名`。

`ConvertSupport.convert(...)` 会遍历所有已注册实现，命中第一个 `isSupported(源, 目标) == true` 的转换器。注意：同一能力注册多个实现时按遍历顺序命中第一个，不要重复声明同一条转换路径。

---

## 6. 构建与依赖

### 6.1 Maven 结构

- 父 POM：`com.chua:utils-support-filesystem-parent:4.0.0.51`
- 模块：`com.chua:utils-support-libreoffice-starter`（版本随父 POM）
- 编译目标：JDK 25（`maven.compiler.release=25`）

### 6.2 依赖清单

| 依赖 | 范围 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | 提供 SPI 契约（`FileConvertSystem`/`FileSource`/`ConvertSetting`）与 `ServiceProvider` |
| `org.projectlombok:lombok` | provided | `@Slf4j` 日志注解 |
| `org.jetbrains:annotations`、`com.google.code.findbugs:jsr305` | provided | 注解辅助 |

> 完整传递依赖树见模块目录下的 `dtrees.txt` 快照（guava / jackson / reactor-core 等来自 common-starter）。

### 6.3 运行期外部依赖

- **LibreOffice**：必须安装，且 `soffice` 可执行文件在 `PATH` 上。这是唯一的外部前提，Maven 不会替你安装。

### 6.4 关于 UNO 五件套（重要说明，引自 pom 注释）

2026-09-28 起，本模块**移除**了 `org.libreoffice` 的 UNO 五件套（`juh` / `jurt` / `ridl` / `unoil` / `officebean`）。本模块唯一实现类通过 `ProcessBuilder` 调用 `soffice` 命令行完成转换，不走 UNO 远程桥，全仓对 `com.sun.star` 的引用数为 0。如将来确需改用 UNO API 直接嵌入，必须**整组加回**这五个 jar（UNO 桥版本强耦合，混版会在运行期抛 `UnsatisfiedLinkError`），不要只加其中一两个。

### 6.5 构建命令

```bash
mvn -o -q compile -DskipTests
```

### 6.6 GraalVM native-image

模块自带 `src/main/resources/META-INF/native-image/com.chua/utils-support-libreoffice-starter/` 下的：

- `reflect-config.json`：注册 `LibreOfficeFileConvertSystem` 的全部构造器/方法/字段反射；
- `resource-config.json`：包含 `META-INF/extensions/.*`（保证 SPI 注册文件进入原生镜像）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去哪找 |
| --- | --- |
| 文档内容解析、文本抽取、逐行读取 | common 层文件体系（`file.FileSystem` 及各格式实现），不在本模块 |
| 文档生成、模板填充、导出 | common 层 `export` / `document` 体系，不在本模块 |
| 图片、音视频等非 Office 格式转换（如 png2jpg） | 由其它 `FileConvertSystem` SPI 实现提供；本模块仅覆盖 9 种 Office 源格式 |
| PNG/JPG 等图像处理 | common 层 `image` 体系与 `BufferedImageUtils` |
| Excel/Word 的结构化读写 | `utils-support-excel-starter` / `utils-support-word-starter` 等对应格式模块 |
| 嵌入式 LibreOffice / UNO 编程 | 本模块明确不走 UNO（见 6.3）；需要 UNO 需自行整组加回五件套并改造实现 |
| soffice 进程池、并发转换队列、超时控制 | 源码未提供；当前实现为每次转换起一个 `soffice` 进程并同步等待 |
| URL 远程文件作为输入源 | 源码未提供；`convert` 只处理路径或输入流两种源 |
