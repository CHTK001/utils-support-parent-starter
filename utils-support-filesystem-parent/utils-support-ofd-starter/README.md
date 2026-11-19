# utils-support-ofd-starter

> utils 体系的 **OFD 版式文档读取模块**：把 `.ofd`（Open Format Document，国家版式文档标准）当作 ZIP 包处理，提取其中 XML 版式内容的纯文本，并以 `FileSystem` SPI（名称 `ofd`）接入统一的文件读写门面。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.ofd.support`

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
    <artifactId>utils-support-ofd-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | OFD 版式文件的**文本提取**器：按 ZIP 流读取 `.ofd`，取所有 `.xml` 条目，去标签、压缩空白后拼成纯文本 | 不是 OFD 版式渲染器，不还原版面、印章、发票字段 |
| 接入方式 | `FileSystem` SPI 实现（`@Spi("ofd")`），可被 `FileSystem.create("ofd")` / `FileSystem.auto(file)` 发现 | 不是独立框架，没有自己的调度、配置、注解体系 |
| 依赖 | 只依赖 `utils-support-common-starter`（契约与 `ReadBuilder` 基类），纯 JDK ZIP/XML 处理，无第三方 OFD 库 | 不依赖 Spring、不依赖任何 OFD 解析 SDK |
| 读写能力 | 只实现**读**（`read()` → `OfdReadBuilder#text()`） | 不支持**写**（`write()` 抛 `UnsupportedOperationException`） |

### 1.3 模块构成（全部源码，共 2 个类）

| 类型 | 类 | 说明 |
| --- | --- | --- |
| SPI 实现 | `OfdFileSystem` | `@Spi("ofd")`，实现 `com.chua.common.support.file.FileSystem`；`getType()` 返回 `"ofd"`；`read(File)` 返回 `OfdReadBuilder`；`write(File)` 抛 `UnsupportedOperationException("OFD write not yet supported")`。`@since 4.0.0.42` |
| 读取器 | `OfdReadBuilder` | 继承 `com.chua.common.support.file.builder.ReadBuilder`；构造器 `OfdReadBuilder(File file)`；核心方法 `text()` 提取纯文本。`@since 4.0.0.42` |

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 按 SPI 名拿文件系统 | `FileSystem.create("ofd")` | 经 `ServiceProvider` 按 `ofd` 名称发现 `OfdFileSystem` |
| 按扩展名自动推断 | `FileSystem.auto(new File("demo.ofd"))` | 扩展名 `ofd` → `create("ofd")` |
| 带类型创建（免强转） | `FileSystem.create("ofd", OfdFileSystem.class)` | 返回 `OfdFileSystem` 类型 |
| 提取纯文本 | `new OfdReadBuilder(file).text()` | 最直接入口；返回 `String` |
| 经 SPI 读取后提取 | `((OfdReadBuilder) fs.read(file)).text()` | `read()` 的声明类型是 `ReadBuilder`，`text()` 需转回 `OfdReadBuilder` |
| 指定解码字符集 | `OfdReadBuilder b = new OfdReadBuilder(file); b.withCharset(StandardCharsets.UTF_8); b.text();` | `withCharset` 继承自 `ReadBuilder`（返回基类，其上无 `text()`），需分步调用或转回 `OfdReadBuilder`；默认系统编码 |
| 查询类型名 | `fs.getType()` | 返回 `"ofd"` |
| 写入 OFD | `fs.write(file)` | 当前不支持，抛 `UnsupportedOperationException` |

`OfdReadBuilder#text()` 的确切行为（与源码一致）：

1. 用 `ZipInputStream` 打开文件，遍历全部 `ZipEntry`；
2. 仅处理条目名以 `.xml` 结尾的条目；
3. 每个条目按当前 `charset` 解码为字符串，用 `replaceAll("<[^>]+>", " ")` 去掉全部 XML 标签，再用 `replaceAll("\\s+", " ")` 把连续空白压成单个空格并 `trim`；
4. 若累积文本非空（`sb.length() > 0`），在该条目结果末尾追加一个换行符 `\n`，空结果不追加；
5. 全部拼接后整体 `trim` 返回；
6. 过程中出现 `IOException` 时**不抛异常**，返回空字符串 `""`。

> 注意：`OfdReadBuilder` 只实现了 `text()`。继承自 `ReadBuilder` 的 `asLines()`、`asString()`、`toMap()`、`toObject(Class)`、`read()`、`listEntries()`、`extractAll(File)` 等方法未被覆盖，调用会抛 `UnsupportedOperationException`（基类默认行为）。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ofd-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.file.FileSystem;
import com.chua.ofd.support.OfdFileSystem;
import com.chua.ofd.support.OfdReadBuilder;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class OfdDemo {

    public static void main(String[] args) {
        File ofd = new File("demo.ofd");

        // 方式一：SPI 按名获取，读取后转回 OfdReadBuilder 取 text()
        FileSystem fs = FileSystem.create("ofd");
        String text = ((OfdReadBuilder) fs.read(ofd)).text();
        System.out.println(text);

        // 方式二：带类型创建（返回 OfdFileSystem）
        OfdFileSystem typed = FileSystem.create("ofd", OfdFileSystem.class);
        System.out.println(typed.getType());                       // ofd

        // 方式三：按扩展名自动推断（.ofd -> ofd）
        FileSystem auto = FileSystem.auto(ofd);

        // 方式四：直接实例化读取器（最短路径），并可指定解码字符集
        // 注意：withCharset 返回基类 ReadBuilder（无 text()），需分步调用
        OfdReadBuilder builder = new OfdReadBuilder(ofd);
        builder.withCharset(StandardCharsets.UTF_8);
        String text2 = builder.text();
        System.out.println(text2);
    }
}
```

### 3.3 行为提示

- `text()` 对损坏/非 ZIP 文件不抛异常，返回 `""`，调用方需自行判空。
- 提取的是**全部 `.xml` 条目**的文本（含 OFD 包内文档结构 XML），不是按版面顺序的正文；对版式敏感的场景需自行过滤。
- `write()` 当前必定抛 `UnsupportedOperationException`。

---

## 4. 配置项

**无。**

依据：模块源码仅包含 `OfdFileSystem`、`OfdReadBuilder` 两个类，`src/main/resources` 下只有 SPI 注册文件与 GraalVM `native-image` 元数据，没有任何 `@ConfigurationProperties` 配置类，也没有 properties/yml 配置键；`pom.xml` 亦未声明配置相关插件参数。字符集等读取行为通过 `ReadBuilder` 的链式方法（如 `withCharset`）在代码中指定，不走配置文件。

---

## 5. SPI 扩展点

本模块是 `FileSystem` SPI 的**实现方**，不是新扩展点的提供方。

- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.file.FileSystem`
- 注册内容：`ofd=com.chua.ofd.support.OfdFileSystem`
- 消费方式：`FileSystem.create("ofd")` / `FileSystem.auto(file)`（`FileSystem` 接口内置静态入口，位于 `utils-support-common-starter`）。

若要扩展 OFD 能力（如实现写入、按条目读取），做法是：

1. 在本模块内为 `OfdFileSystem` / `OfdReadBuilder` 增加实现（覆盖 `write()` 或新增读取方法）；或
2. 按 `utils-support-common-starter` 的 SPI 约定，另注册一个新的 `FileSystem` 实现（在 `META-INF/extensions/com.chua.common.support.file.FileSystem` 中追加 `<名称>=<实现类>` 一行）。

本模块自身**没有**可供外部扩展的新 SPI 接口（源码未提供）。

---

## 6. 构建与依赖

### 6.1 依赖关系

```
utils-support-ofd-starter
└── utils-support-common-starter
```

- 父模块：`utils-support-filesystem-parent`（`com.chua:utils-support-filesystem-parent:4.0.0.51`）。
- 唯一依赖：`com.chua:utils-support-common-starter`（`${project.version}`），提供 `FileSystem` 接口、`ReadBuilder` 基类与 SPI 基础设施。
- 无其它第三方依赖；OFD 的 ZIP 解析使用 JDK 内置 `java.util.zip`。

### 6.2 构建

```bash
# 在本模块目录下编译
mvn -o -q compile -DskipTests

# 或从 utils-support-parent-starter 根目录构建整个文件系统族
mvn -o -q compile -DskipTests -pl utils-support-filesystem-parent/utils-support-ofd-starter -am
```

### 6.3 GraalVM Native Image

`src/main/resources/META-INF/native-image/com.chua/utils-support-ofd-starter/` 内置：

- `reflect-config.json`
- `resource-config.json`

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 | 应去何处 |
| --- | --- | --- |
| OFD **写入/生成** | `OfdFileSystem.write()` 抛 `UnsupportedOperationException`，源码未提供写入实现 | 源码未提供；需自行扩展或引入第三方库 |
| OFD 版式渲染（转 PDF/图片、还原版面） | 源码未提供 | 源码未提供 |
| OFD 发票/单据字段结构化解析、签章验签 | 源码未提供 | 源码未提供 |
| PDF 读取/导出 | 不在本模块 | `utils-support-pdf-starter` |
| Word 读取 | 不在本模块 | `utils-support-word-starter` |
| Excel/CSV 读写 | 不在本模块 | `utils-support-excel-starter` 等 |
| `FileSystem` SPI 接口与 `ReadBuilder`/`WriteBuilder` 基类 | 契约定义不在本模块（本模块只做实现） | `utils-support-common-starter` |
| 其它格式文件系统（zip、tar、json、xml、yaml、7z 等） | 不在本模块 | `utils-support-common-starter`（内置）与 `utils-support-filesystem-starter`（聚合） |
