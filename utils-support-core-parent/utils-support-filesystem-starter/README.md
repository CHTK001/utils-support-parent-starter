# utils-support-filesystem-starter

> utils 体系的**文件系统扩展实现层**：在 `utils-support-common-starter` 的文件/存储/配置 SPI 契约之上，提供 ZIP（zip4j，支持密码）、7Z（commons-compress，支持分卷）、YAML、INI 文件系统实现，本地磁盘对象存储（`filesystem`/`file`/`local`）、归档流读写、YAML/TOML 配置解析、Smile 二进制序列化、ZIP 下载解压与数据恢复/文件检索契约。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承自 `utils-support-core-parent` 的 `maven-compiler-plugin release=25`）｜包名：`com.chua.filesystem.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 规模](#13-规模)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 带密码 ZIP 打包 / 解包（zip4j）](#31-带密码-zip-打包--解包zip4j)
  - [3.2 本地对象存储（filesystem）](#32-本地对象存储filesystem)
  - [3.3 YAML / INI 读写](#33-yaml--ini-读写)
  - [3.4 7Z 分卷读写](#34-7z-分卷读写)
- [4. 分卷与密码的实际情况](#4-分卷与密码的实际情况)
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
    <artifactId>utils-support-filesystem-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 文件系统 SPI | 提供 `FileSystem` 的 `zip4j` / `7z` / `yaml`,`yml` / `ini` 实现 | 不提供 CSV/JSON/XML/TXT 的 `FileSystem` 实现（由 `utils-support-common-starter` 的 `file` 包提供，按扩展名自动推断） |
| 对象存储 SPI | 提供 `FileStorage` 的 `filesystem` / `file` / `local` 本地磁盘实现，支持 Range 读取 | 不是远程对象存储协议（S3/OSS/COS/MinIO 等）客户端 |
| 配置解析 SPI | 提供 `ConfigParser` 的 `yaml` / `toml` 实现，`StringToConfigConverter` 的 `toml` 实现 | 不提供 properties/xml/json 等其它解析器（在 common-starter） |
| 序列化 SPI | 提供 `Serialization` 的 `smile`（二进制 JSON）实现 | — |
| 下载解压 SPI | 提供 `Extractor` 的 `zip` 实现（支持密码，解压后逐条目校验，越界仅告警） | — |
| 归档流 | 提供 `CompressArchiveInputStream` / `CompressArchiveOutputStream` / `CompressInputStream` 的 zip、tar、tar.gz、tar.xz、tar.bz2、jar、gz、7z、rar、dump、cpio、ar 实现（基于 commons-compress / junrar） | 压缩解压算法本身由三方库承担，本模块只做格式识别与适配 |
| 契约层 | 定义 `Restore`（数据恢复）接口与 `FileSearchProvider`（文件检索）接口及模型 | 不提供这两类契约的任何实现类（源码未提供实现与 SPI 注册声明） |

**判断口径**：接口与公共门面在 `utils-support-common-starter`，本模块只做「协议/格式驱动层」——每个能力都是对 common-starter 某个 SPI 的落地实现。

### 1.3 规模

- `src/main/java` 下 **43** 个 `.java` 源文件，顶层包 `com.chua.filesystem.support`。
- SPI 扩展注册文件 **5** 个（`src/main/resources/META-INF/extensions/`），另有 `stream` 包内 **16** 个类以 `@Spi` 注解声明（无对应注册文件）。
- 附带 GraalVM `native-image` 配置（`META-INF/native-image/com.chua/utils-support-filesystem-starter/` 下的 `reflect-config.json`、`resource-config.json`）。
- 仓库内未见 `src/test` 测试源码（`src/test/**` 无文件）。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | SPI Key | 说明 |
| --- | --- | --- | --- |
| 带密码 ZIP 打包/解包 | `file.impl.Zip4jFileSystem` | `zip4j` | zip4j 2.11.6；写入支持密码（AES）、压缩级别；读取支持 `listEntries` / `readEntry` / `extractAll` / 指定条目提取 |
| 7Z 读写与分卷 | `file.impl.SevenZFileSystem` | `7z` | commons-compress；写入支持 `splitSize` 真实分卷，读取支持 `split()` 合并分卷；解包含路径穿越防护 |
| YAML 读写 | `file.impl.YamlFileSystem` + `YamlReadBuilder` / `YamlWriteBuilder` | `yaml` / `yml` | snakeyaml 2.7，读取为 `Map<String, Object>`，任意对象 dump 写入 |
| INI 读写 | `file.impl.IniFileSystem` + `IniReadBuilder` / `IniWriteBuilder`；工具类 `ini.IniParser` | `ini` | Section/键-值、注释（`;` `#`）、`__section__` 表格格式 |
| 本地对象存储 | `storage.FileSystemFileStorage` | `filesystem` / `file` / `local` | 基于 `BucketSetting`；`supportsRange() == true`，`skip` + 长度受限流实现 Range 读取；含路径穿越校验 |
| 归档/压缩流 | `stream.*`（`CompressArchiveInputStream`、`CompressArchiveOutputStream`、`CompressInputStream` 实现） | `zip`/`tar`/`tar.gz`/`tar.xz`/`tar.bz2`/`jar`/`gz`/`7z`/`rar`/`dump`/`cpio`/`ar` | 按文件后缀识别；7z、rar 必须传 `File` 对象；gz 不支持密码 |
| 配置文件解析 | `config.parser.*`（`YamlConfigParser`、`TomlConfigParser`）、`converter.TomlStringToConfigConverter` | `yaml` / `toml` | YAML 保留嵌套结构，读取时支持点分隔键嵌套穿透；TOML 嵌套 Map 扁平化为点分隔键后返回 `PropertySource` |
| 二进制序列化 | `serialize.SmileSerialization` | `smile` | Jackson Smile，静态线程安全 `ObjectMapper`，`serialize` / `deserialize` |
| ZIP 下载解压 | `extractor.ZipExtractor` | `zip` | 支持密码（`isEncrypted` 检测），先整体解压、再逐条目校验，越界条目仅告警（不阻断） |
| 数据恢复契约 | `restore.Restore` / `AbstractRestore` / `RestoreSetting` / `RestoreResult` | — | 仅接口与基类，本模块无实现；`Restore.createRestore(name, ...)` 走 SPI 装配 |
| 文件检索契约 | `filesearch.spi.FileSearchProvider` + `model.FileSearchCriteria` / `model.FileInfo` | — | 仅接口与模型（record），本模块无实现 |
| JDK ZIP 工具类 | `file.impl.CompressedFileSystem` | 非 SPI | `create`（写）/ `open`（读）；`addFile` / `addBytes` / `addString` / `list` / `extract` / `extractAll` / `readBytes` |

---

## 3. 快速开始

### 3.1 带密码 ZIP 打包 / 解包（zip4j）

```java
import com.chua.common.support.file.FileSystem;
import com.chua.filesystem.support.file.impl.Zip4jFileSystem;
import net.lingala.zip4j.model.enums.CompressionLevel;
import java.io.File;
import java.util.List;

// 通过门面创建 zip4j 文件系统
FileSystem fs = FileSystem.create("zip4j");

// 写入：密码 + 压缩级别，链式添加条目
Zip4jFileSystem.Zip4jWriteBuilder wb = (Zip4jFileSystem.Zip4jWriteBuilder) fs.write(new File("out.zip"));
wb.password("secret")
  .setCompressionLevel(CompressionLevel.ULTRA)
  .addFile("docs/a.txt", new File("a.txt"))          // 文件
  .addStream("stream/data.txt", inputStream)          // 输入流
  .addBytes("bin/data.bin", new byte[] {1, 2, 3})     // 字节数组
  .finish();

// 读取：列条目、读内容、解压
Zip4jFileSystem.Zip4jReadBuilder rb = (Zip4jFileSystem.Zip4jReadBuilder) fs.read(new File("out.zip"));
rb.password("secret");                                // 无密码可省略
List<String> names = rb.listEntries();                // 全部条目名
String text = rb.readEntry("docs/a.txt");             // 条目内容（UTF-8 字符串）
rb.extractAll(new File("out"));                       // 全量解压
rb.extract("docs/a.txt", new File("out2"));           // 指定条目解压
```

### 3.2 本地对象存储（filesystem）

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;

BucketSetting setting = BucketSetting.builder()
        .bucket("/tmp/filestorage")   // bucket：本地存储根目录（必填）
        .build();
FileStorage storage = FileStorage.createStorage("filesystem", setting);

// 上传
storage.putObject(PutObjectRequest.builder()
        .fileName("test.txt")
        .filePath("docs")
        .content("hello".getBytes())
        .build());

// 下载（支持 Range：GetObjectRequest 可带 offset/length）
GetObjectResult result = storage.getObject("docs/test.txt");
```

`bucket` 与 `endpoint` 均未配置时，根目录回退到 `System.getProperty("user.home") + File.separator + "filestorage"`。

### 3.3 YAML / INI 读写

```java
import com.chua.common.support.file.FileSystem;
import com.chua.filesystem.support.file.impl.YamlReadBuilder;
import com.chua.filesystem.support.file.impl.IniReadBuilder;
import com.chua.filesystem.support.file.impl.IniWriteBuilder;
import java.io.File;
import java.util.List;
import java.util.Map;

FileSystem fs = FileSystem.create("yaml");            // 或 "yml"
// 读取 YAML → Map
Map<String, Object> data = ((YamlReadBuilder) fs.read(new File("config.yml"))).toMap();

// INI：读取
FileSystem iniFs = FileSystem.create("ini");
IniReadBuilder iniRb = (IniReadBuilder) iniFs.read(new File("config.ini"));
Map<String, Object> nested  = iniRb.toMap();          // Section → {Key → Value}
List<Map<String, Object>> rows = iniRb.rows();        // 表格格式，每行含 __section__ 字段
iniRb.sections();                                     // Section 名称集合
iniRb.section("server");                              // 指定 Section 属性

// INI：写入（嵌套 Map 或含 __section__ 的行列表）
IniWriteBuilder iniWb = (IniWriteBuilder) iniFs.write(new File("output.ini"));
iniWb.write(Map.of("server", Map.of("port", "8080"))).finish();
```

### 3.4 7Z 分卷读写

```java
import com.chua.common.support.file.FileSystem;
import com.chua.filesystem.support.file.impl.SevenZFileSystem;
import java.io.File;

FileSystem fs = FileSystem.create("7z");

// 写：设置分卷大小后，先写临时文件再按 splitSize 切分
SevenZFileSystem.SevenZWriteBuilder wb = (SevenZFileSystem.SevenZWriteBuilder) fs.write(new File("out.7z"));
wb.splitSize(1024L * 1024 * 100)          // 每卷最大字节数
  .compressionMethod(org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2) // 默认即 LZMA2
  .compressionLevel(5)                     // LZMA2: 0~9，-1 表示方法默认
  .addFile("large.bin", new File("large.bin"))
  .finish();

// 读：split() 启用分卷合并
SevenZFileSystem.SevenZReadBuilder rb = (SevenZFileSystem.SevenZReadBuilder) fs.read(new File("out.7z"));
rb.split();
rb.listEntries();
rb.readEntry("large.bin");
rb.extractAll(new File("out"));
```

---

## 4. 分卷与密码的实际情况

以源码为准（与旧版 README 描述不同，此处逐条列明）：

| 事项 | 源码事实 |
| --- | --- |
| `Zip4jWriteBuilder.splitSize(long)` | 仅校验 `size >= 65536`（64KB），否则抛 `UncheckedIOException("Split size must be at least 65536 bytes (64KB)")`；设置后仅存入字段，**`finish()` 中没有任何分卷逻辑**。源码注释原文：「zip4j 2.11.x 分卷通过创建分割压缩文件实现，不在压缩参数中设置」——本实现未调用分卷创建 API，因此 zip4j 写入路径**不会产生 `.z01/.z02` 分卷文件** |
| `Zip4jReadBuilder` | **没有 `split()` 方法**。`openZip()` 注释：「zip4j 原生支持分卷读取，无需特殊处理。只需指向主 .zip 文件，zip4j 会自动检测并读取分卷」 |
| `Zip4jWriteBuilder` 密码 | 有密码时对所有条目 `setEncryptFiles(true)` + `setEncryptionMethod(EncryptionMethod.AES)`，即 AES 加密 |
| `SevenZWriteBuilder.splitSize(long)` | **真实分卷**：`finishSplit()` 先 `File.createTempFile("7z-split-", ".7z", ...)` 写完整归档，再按 `splitSize` 切分。命名：非末尾卷为 `主名.001`、`主名.002`（`"%03d"` 序号），最后一块写入主文件名（如 `out.7z`）；文件整体不大于 `splitSize` 时只生成主文件 |
| `SevenZReadBuilder.split()` | 真实合并：`findSplitFiles()` 查找同目录下 `主名.` 前缀 + 纯数字后缀（匹配 `\d+`）的分卷并按数字升序排列，通过 `MultiReadOnlySeekableByteChannel` 合并后 `new SevenZFile(mergedChannel)` 读取 |
| 7Z 解包路径穿越 | `extract` 中每个条目校验 `outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())`，越界抛 `IOException("7z entry outside target: ...")` |
| GZ 密码 | `GzipCompressArchiveInputStream.createInputStream` 密码非空时抛 `UnsupportedOperationException("GZ 格式不支持密码保护")` |
| 7Z / RAR 读取 | 需要随机访问，必须传 `File` 对象；`file == null` 时抛 `IOException("7Z格式需要File对象，不能使用InputStream")`（RAR 同理） |

---

## 5. 配置项

本模块**没有配置文件、没有系统属性读取逻辑**（源码未提供 `@Value` / `@ConfigurationProperties` / `System.getProperty` 之外的配置入口，唯一例外是存储根目录缺省值使用的 `user.home` 系统属性）。一切参数通过对象构建器传入：

### 5.1 本地存储（`BucketSetting`）

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `bucket` | 是（源码 Javadoc 标注） | 本地存储根目录，如 `/data/storage` 或 `C:/storage` |
| `endpoint` | 否 | 备用根目录，优先级低于 `bucket` |
| （缺省） | — | `user.home/filestorage` |
| `maxFileSize` / 其它 | 源码未提供 | `FileSystemFileStorage` 只使用 `bucket`、`endpoint` 两个字段 |

### 5.2 数据恢复（`RestoreSetting`）

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `restoreType` | `ALL` | `STRUCTURE`（仅 DDL）/ `DATA` / `ALL` |
| `targetSchema` / `targetTable` | 空 | 目标库名 / 目标表名（可选） |
| `dropIfExists` | `false` | 恢复前删除已存在的表 |
| `truncateBeforeRestore` | `false` | 恢复前清空表数据 |
| `batchSize` | `1000` | 批量插入大小 |
| `useTransaction` | `true` | 是否使用事务 |
| `continueOnError` | `false` | 忽略错误继续执行 |
| `useLoadData` | `true` | 使用 LOAD DATA 快速导入（仅 MySQL 支持） |
| `tempDirectory` | 空 | 临时文件目录 |
| `timeout` | `3600` | 超时时间（秒） |
| `mysql5` | `false` | 是否为 MySQL 5.7（IBD 文件解析） |
| `keyringFile` | 空 | keyring 文件路径（加密 IBD 文件） |
| `force` | `false` | 是否强制解析（忽略错误页） |
| `charset` | `"UTF-8"` | 字符编码 |

### 5.3 数据库连接参数载体（`DataSourceOptions`）

`url`、`username`、`password`、`driverClassName`、`maximumPoolSize`、`minimumIdle`、`connectionTimeout`（毫秒）、`idleTimeout`（毫秒）、`maxLifetime`（毫秒）。仅作为 `Restore` 契约的参数对象，本模块不进行任何连接管理。

### 5.4 文件检索（`FileSearchCriteria`，record + Builder）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `rootPath` | 空 | 根目录路径，空表示当前工作目录 |
| `namePattern` | 空 | 文件名通配符模式（如 `*.txt`、`report?.pdf`） |
| `minSize` / `maxSize` | `-1` | 最小/最大文件大小（字节），-1 表示不限 |
| `maxResults` | `1000`（`DEFAULT_MAX_RESULTS`） | 最大返回结果数，0 表示不限；负数归一化为 1000 |
| `maxDepth` | `0`（`DEFAULT_MAX_DEPTH`） | 目录遍历最大深度，0 表示不限 |
| `excludeDirs` / `excludePatterns` | `null` | 排除目录名 / 排除文件名模式（可空，访问器返回防御性拷贝） |
| `followLinks` | `false` | 是否跟踪符号链接 |
| `includeHidden` | `false` | 是否包含隐藏文件 |
| `sortBy` | `"size"`（`SORT_BY_SIZE`） | `name` / `size` / `path` / `modified` |
| `order` | `"desc"`（`ORDER_DESC`） | `asc` / `desc` |

---

## 6. SPI 扩展点

本模块所有扩展注册在 `src/main/resources/META-INF/extensions/`，文件名为接口全限定名，内容为 `key=实现类`（`FileStorage` 为单行全类名，key 来自类上 `@Spi`）：

| 注册文件（接口） | 注册内容 |
| --- | --- |
| `com.chua.common.support.file.FileSystem` | `yaml=...file.impl.YamlFileSystem`、`zip4j=...file.impl.Zip4jFileSystem`、`7z=...file.impl.SevenZFileSystem`、`ini=...file.impl.IniFileSystem` |
| `com.chua.common.support.storage.FileStorage` | `com.chua.filesystem.support.storage.FileSystemFileStorage`（`@Spi({"filesystem","file","local"})`） |
| `com.chua.common.support.config.parser.ConfigParser` | `yaml=...config.parser.impl.YamlConfigParser`、`toml=...config.parser.TomlConfigParser` |
| `com.chua.common.support.base.serialize.Serialization` | `smile=...serialize.SmileSerialization` |
| `com.chua.common.support.network.download.extractor.Extractor` | `zip=...extractor.ZipExtractor` |

`stream` 包的流式实现**没有**注册文件，仅以 `@Spi` 注解声明（源码头部可见）：

| 接口 | 实现（`@Spi` key） |
| --- | --- |
| `com.chua.common.support.io.file.stream.CompressArchiveInputStream` | `zip` / `tar` / `tar.gz`,`tgz` / `tar.xz`,`txz` / `tar.bz2`,`tbz2`,`tbz` / `jar` / `gz`,`gzip` / `7z` / `rar` / `dump` / `cpio` / `ar` |
| `...io.file.stream.CompressInputStream` | `gz`（`GzipCompressInputStreamProvider`） |
| `...io.file.stream.CompressArchiveOutputStream` | `zip` / `tar` / `tar.gz`,`tgz` |
| 内部适配器 | `ArchiveInputStreamAdapter`（commons-compress → 项目接口）、`ArchiveEntryAdapter`、`SevenZArchiveInputStreamAdapter`、RAR 内部类适配器（`RarArchiveInputStreamAdapter` / `RarArchiveEntryAdapter`） |

**契约但无实现**（源码未提供实现类与注册声明）：

- `com.chua.filesystem.support.restore.Restore`：接口 + `AbstractRestore` 基类 + `RestoreSetting` / `RestoreResult` 模型。装配入口 `Restore.createRestore(String name, DataSourceOptions, RestoreSetting)`，实现方需自行注册到 `META-INF/extensions/com.chua.filesystem.support.restore.Restore`（该文件在本模块不存在）。
- `com.chua.filesystem.support.filesearch.spi.FileSearchProvider`：接口 `searchFiles(FileSearchCriteria)` 返回 `List<FileInfo>`，本模块无实现。

`RestoreResult` 静态工厂：`success(schema, table, rowCount)`、`structureSuccess(schema, table, ddl)`、`dataSuccess(schema, table, rowCount)`、`failure(errorMessage)`、`failure(errorMessage, exception)`。

---

## 7. 构建与依赖

### 7.1 构建

- 聚合工程：`utils-support-parent-starter` → `utils-support-core-parent`（本模块为其子模块，`<packaging>jar</packaging>`）。
- JDK：`25`（父工程 `maven-compiler-plugin` 的 `<release>25</release>`，`-Xlint:all`）。
- 模块自身编译参数：针对注解处理开放 `jdk.compiler/com.sun.tools.javac.*` 的 `-J--add-exports` / `-J--add-opens`，以及 `-J--enable-native-access=ALL-UNNAMED`、`-proc:full`、`-parameters`。
- GraalVM：父工程提供 `native` profile（`native-maven-plugin` 1.1.14），本模块附带 `reflect-config.json` / `resource-config.json`。
- 由于依赖同版本的 `utils-support-common-starter`，需在 `utils-support-core-parent` 聚合工程内一起构建。

### 7.2 依赖（pom.xml 实测）

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 与父工程一致（`${project.version}`） | SPI 契约、门面、工具 |
| `org.projectlombok:lombok` | `${lombok.version}`（provided） | 注解处理 |
| `net.lingala.zip4j:zip4j` | `2.11.6` | `Zip4jFileSystem`、`ZipExtractor` |
| `org.yaml:snakeyaml` | `2.7` | `YamlFileSystem`、`YamlConfigParser` |
| `org.apache.commons:commons-compress` | `1.28.0` | 7z/tar/gz/bz2/xz/dump/cpio/ar 等归档流 |
| `org.tukaani:xz` | 继承父工程管理 | XZ 解压（`tar.xz`） |
| `com.github.junrar:junrar` | `7.5.5` | RAR 读取（`RarCompressArchiveInputStream`） |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-smile` | 继承父工程管理 | `SmileSerialization` |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-toml` | 继承父工程管理 | `TomlConfigParser`、`TomlStringToConfigConverter` |

---

## 8. 边界：这些能力不在本模块

- **远程对象存储协议客户端**（S3/OSS/COS/MinIO 等）：本模块只有本地磁盘实现 `FileSystemFileStorage`；`FileStorage` 的接口与请求/结果模型在 common-starter。
- **CSV/JSON/XML/TXT 的 `FileSystem` 实现**：pom.xml 的 description 提到「CSV/JSON/XML/TXT/YAML/ZIP 读写」，其中 CSV/JSON/XML/TXT 由 `utils-support-common-starter` 的 `file` 包提供（按扩展名自动推断，见其 README 6.1）；本模块新增的是 `zip4j` / `7z` / `yaml` / `yml` / `ini`。
- **`Restore` 的具体实现**（如 MySQL/PostgreSQL 恢复器）：接口与基类在本模块，无任何实现注册；`RestoreSetting` 中 `useLoadData`、`mysql5`、`keyringFile` 等字段为契约预留，本模块不消费。
- **`FileSearchProvider` 的具体实现**：本模块只有接口与 `FileSearchCriteria` / `FileInfo` 模型（record），无输出结果的搜索器。
- **数据库驱动与连接池**：`DataSourceOptions` 只是参数载体（9 个字段），没有连接管理逻辑。
- **zip4j 分卷写入**：`Zip4jWriteBuilder.splitSize` 只做 64KB 下限校验，`finish()` 不分卷（详见第 4 节）；要做 zip4j 分卷需在外部自行调用 zip4j 的 `createSplitZipFile`，本模块未封装。
- **RAR 写入**：`stream` 包只提供 RAR 读取（junrar），没有 RAR 归档输出流。
- **GZ 密码**：单文件 gzip 压缩不支持密码，密码非空直接抛 `UnsupportedOperationException`。
- **纯流式 7Z / RAR 输入**：两者需要文件对象（随机访问），不接受纯 `InputStream`。