# utils-support-ibd-starter

> utils 体系的**智能业务数据（IBD）模块**（POM 官方描述：智能业务数据模块：Python 脚本执行、数据处理、自动解压资源）。核心能力是把 MySQL InnoDB 独立表空间文件（`.ibd`）**离线还原**为 CSV / SQL / Excel：内置纯 Java 的 InnoDB 页解析器——第 0 页 FSP header 推页大小、SDI 页解 zlib JSON 得表结构、索引叶子页按 `REC_NEXT` 记录链解码行数据（扫页而非走 B+ 树），并可由 SDI 表定义直接生成 DDL + INSERT；另附 JAR 内资源解压器 `IbdManager`、Python 脚本执行器与 `ibd`→`sql` 文件转换 SPI。
>
> 版本：`4.0.0.51`（父 POM `utils-support-filesystem-parent`；类自 `4.0.0.42` 起提供）｜JDK：`25`（继承根 POM `utils-support-parent-starter` 的 `maven-compiler-plugin release=25`）｜包名：`com.chua.ibd.support`（子包 `.innodb` / `.restore` / `.converter`）

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 添加依赖](#31-添加依赖)
  - [3.2 一行还原 `.ibd` 为 CSV（零外部依赖）](#32-一行还原-ibd-为-csv零外部依赖)
  - [3.3 还原为 SQL / Excel 并定制目标库表](#33-还原为-sql--excel-并定制目标库表)
  - [3.4 选择解析引擎与 ibd2sql 选项](#34-选择解析引擎与-ibd2sql-选项)
  - [3.5 纯 Java 直接读表](#35-纯-java-直接读表)
  - [3.6 IbdManager 资源解压与 Python 脚本](#36-ibdmanager-资源解压与-python-脚本)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 数据还原 SPI | `DataRestore` 的 `ibd` 实现（`restore.IbdDataRestore`）：`.ibd` → CSV / SQL / Excel，输出到源文件所在目录或 `outputDir` | 不连接 MySQL Server、不执行 SQL、不做备份/增量恢复 |
| InnoDB 解析 | 纯 Java 读页：FSP header 推页大小（4K–64K，不写死 16K）、SDI 页（页类型 17853）解出表结构、叶子页解码行记录 | 不解析 redo / undo / binlog / `.frm`；不支持 MyISAM 等非 InnoDB 文件 |
| SQL 生成 | `innodb.IbdSqlWriter` 依 SDI 表定义生成 `CREATE TABLE IF NOT EXISTS` + `INSERT`（列类型取 SDI 原文；不生成外键） | 只产出脚本文件，不落库 |
| 双引擎 | `native`（默认，零外部依赖）/ `ibd2sql`（外部 Python 工具）/ `auto`（先 native，失败再退 ibd2sql） | ibd2sql 未发布到 PyPI，需自行获取；Python 亦非本模块内置 |
| 文件转换 SPI | `converter.IbdToSqlFileConvertSystem`：源 `ibd` → 目标 `sql` | — |
| 资源与脚本 | `IbdManager`：把 JAR 内指定前缀资源解压到工作目录，经 `ScriptExecutor` 执行脚本；`PythonScriptExecutor` 调系统 Python，上下文以 `IBD_` 前缀环境变量传入 | 不是类加载器 / 资源监控工具；不内置 Python，也不提供 Groovy / JS 执行器（源码未提供） |

**判断口径**：SPI 契约（`DataRestore`、`FileConvertSystem`、`FileSystem`）与还原配置模型（`DataRestoreConfig` / `DataRestoreResult` / `ExportFormat`）都在 `utils-support-common-starter`；本模块只做「`.ibd` 这一类数据源」的落地实现与 InnoDB 物理解析。

**规模**：`src/main/java` 下 **18** 个 `.java` 源文件；`src/main/resources/META-INF/extensions/` 下 **2** 个 SPI 注册文件；另附 GraalVM `native-image` 配置（`META-INF/native-image/com.chua/utils-support-ibd-starter/` 下的 `reflect-config.json`、`resource-config.json`）。仓库内未见本模块的 `src/test` 测试源码。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | SPI Key | 说明 |
| --- | --- | --- | --- |
| `.ibd` 还原为 CSV/SQL/Excel | `restore.IbdDataRestore` | `ibd` | `DataRestore.create("ibd"[, config]).restore(file)`；格式、目标库表、输出目录、字符集由 `DataRestoreConfig` 控制；格式缺省 CSV |
| 纯 Java 读 `.ibd` | `innodb.IbdTableReader` | 非 SPI | `open(File[, ZoneId])`；`definition()` / `columnNames()` / `columns()` / `readAll()` / `readAll(index)` / `estimateRowCount()` / `deletedRows()`；`Closeable` |
| 表空间随机读 | `innodb.IbdTablespace` | 非 SPI | `open(File)`；`pageSize()` / `spaceId()` / `pageCount()` / `readPage(no)` / `sdiPages()` / `leafPagesOf(indexId)`；只读 `FileChannel` |
| 表结构（SDI） | `innodb.IbdSdiReader.readTableDefinition(tablespace)` | 非 SPI | 解 SDI 页 zlib JSON：表名 / 库名 / 行格式 / 列 / 索引；`IbdTableDefinition.userColumns()` 已剔除 `DB_TRX_ID` 等系统列与隐藏列 |
| 生成 SQL | `innodb.IbdSqlWriter` | 非 SPI | `createTable(definition, schema, table)` / `insertStatements(definition, rows, schema, table, columns)`；`IF NOT EXISTS`，不生成外键 |
| 字段解码 | `innodb.IbdTypeDecoder` | 非 SPI | `decode(column, raw[, zone])`：有符号整数高位翻转、`DECIMAL` 紧凑二进制、`TIMESTAMP` UTC 秒按指定时区渲染、二进制列出十六进制 |
| `ibd` → `sql` 文件转换 | `converter.IbdToSqlFileConvertSystem` | `ibd2sql` | `FileConvertSystem` 实现；调外部 ibd2sql（`--ddl --sql`），命令超时 300 秒 |
| IBD 资源与脚本门面 | `IbdManager` + `PythonScriptExecutor` | 非 SPI | `create(prefix)` / `init()` / `getResource(name)` / `setScriptExecutor(executor)` / `executeScript(scriptName, context)`；Python 默认 `python3`、60 秒超时 |

底层组件（一般不直接用，排错时会遇到）：`IbdConstants`（页布局偏移与 `pageSizeOf` / `hasSdi`）、`IbdPageHeader`（页头字段读取）、`IbdRecordCursor`（页内记录链遍历与行解码）、`IbdLobReader`（TEXT/BLOB 溢出页还原）、`IbdColumn` / `IbdColumnType` / `IbdIndex` / `IbdTableDefinition`（SDI 元数据模型）、`ScriptExecutor`（脚本执行契约：`execute(Path, Map)` / `getExtension()`）。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ibd-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 一行还原 `.ibd` 为 CSV（零外部依赖）

```java
import com.chua.common.support.task.restore.DataRestore;
import com.chua.common.support.task.restore.DataRestoreResult;

import java.io.File;

// native 引擎（默认）：不连 MySQL、不需要 Python
DataRestoreResult result = DataRestore.create("ibd").restore(new File("users.ibd"));
result.getOutputFiles().forEach(file -> System.out.println(file.getAbsolutePath()));
```

默认输出 `<源文件目录>/users.csv`；表名优先取 SDI 里的真实表名，取不到时退回「文件名去掉 `.ibd` 与数字前缀」。

### 3.3 还原为 SQL / Excel 并定制目标库表

```java
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.ExportFormat;

import java.io.File;

DataRestoreConfig config = DataRestoreConfig.builder()
        .format(ExportFormat.SQL)        // 或 ExportFormat.EXCEL / ExportFormat.CSV
        .targetSchema("mydb")            // 生成 CREATE DATABASE IF NOT EXISTS ... + USE ...
        .targetTable("users")
        .outputDir(new File("out"))
        .build();

DataRestoreResult result = DataRestore.create("ibd", config).restore(new File("users.ibd"));
```

EXCEL 输出走 `FileSystem.create("excel")`（由 `utils-support-excel-starter` 提供，本模块已声明该依赖）。

### 3.4 选择解析引擎与 ibd2sql 选项

```java
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.ibd.support.restore.IbdDataRestore;

DataRestoreConfig config = DataRestoreConfig.builder()
        .format(ExportFormat.CSV)
        .build();
config.getOptions().put(IbdDataRestore.OPTION_ENGINE, IbdDataRestore.ENGINE_AUTO);  // native 失败自动退 ibd2sql
config.getOptions().put(IbdDataRestore.OPTION_IBD2SQL_PATH, "D:/tools/ibd2sql");    // ibd2sql 仓库根目录或 main.py
config.getOptions().put(IbdDataRestore.OPTION_TIME_ZONE, "Asia/Shanghai");          // TIMESTAMP 渲染时区
```

ibd2sql 引擎要求 PATH 上有 Python（Windows 依次试 `python` / `python3` / `py`，其它系统 `python3` / `python`），并按「显式 `ibd2sql.path` → `python -m ibd2sql` → 自动定位包旁 `main.py`」顺序探测入口；都找不到时给出明确错误提示。

### 3.5 纯 Java 直接读表

```java
import com.chua.ibd.support.innodb.IbdTableDefinition;
import com.chua.ibd.support.innodb.IbdTableReader;

import java.io.File;

try (IbdTableReader reader = IbdTableReader.open(new File("actor.ibd"))) {
    IbdTableDefinition definition = reader.definition();   // 库名.表名、行格式、列、索引
    System.out.println(definition);
    System.out.println(reader.columnNames());              // 用户列（已剔除 DB_TRX_ID 等系统列）
    var rows = reader.readAll();                           // 全部行：列名 -> 值（null = SQL NULL）
}
```

### 3.6 IbdManager 资源解压与 Python 脚本

```java
import com.chua.ibd.support.IbdManager;
import com.chua.ibd.support.PythonScriptExecutor;

import java.util.Map;

IbdManager ibd = IbdManager.create("ibd");     // JAR 内 ibd/ 前缀资源
ibd.init();                                    // 解压到 {java.io.tmpdir}/ibd-ibd
ibd.setScriptExecutor(new PythonScriptExecutor());   // 或 new PythonScriptExecutor("python", 30000)
String output = ibd.executeScript("process.py", Map.of("input", "hello"));
```

上下文会以 `IBD_input=hello` 这样的环境变量传给脚本；进程合并输出（trim 后）即返回值，非 0 退出码或超时抛 `RuntimeException`。

---

## 4. 配置项

**本模块不是 Spring Boot starter：没有 `@ConfigurationProperties`、没有 `application.yml` 配置键（源码未提供）；所有行为通过构造参数与 `DataRestoreConfig` 显式传入。**

`DataRestoreConfig`（定义于 `utils-support-common-starter`，本模块消费）：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `format` | `CSV` | 输出格式：`CSV` / `SQL` / `EXCEL`（`JSON` 本模块未实现，见边界） |
| `outputDir` | 源文件所在目录 | 输出目录 |
| `targetSchema` | 空 | SQL 导出时生成 `CREATE DATABASE IF NOT EXISTS ...` + `USE ...` |
| `targetTable` | 空 | 目标表名；缺省用 SDI 表名，再退回文件名 |
| `includeStructure` | `true` | 是否输出表结构（CSV 首行表头 / SQL 建表语句） |
| `charset` | `UTF-8` | 输出文件字符集 |
| `options` | 空 Map | 扩展参数，见下表 |

`options` 中本模块识别的键（常量定义在 `IbdDataRestore`）：

| 键（常量） | 取值 | 默认 | 说明 |
| --- | --- | --- | --- |
| `engine`（`OPTION_ENGINE`） | `native` / `ibd2sql` / `auto` | `native` | `auto` 先纯 Java，失败退 ibd2sql；两者都失败抛汇总错误 |
| `time.zone`（`OPTION_TIME_ZONE`） | 如 `Asia/Shanghai` | 本机时区 | 渲染 `TIMESTAMP` 用；非法值告警并回退本机时区 |
| `ibd2sql.path`（`OPTION_IBD2SQL_PATH`） | `main.py` 文件或其所在目录 | 空 | 仅 ibd2sql 引擎需要；ibd2sql 未发布到 PyPI，需从其 GitHub 仓库获取 |

关键默认值：

| 类 | 参数 / 常量 | 默认 |
| --- | --- | --- |
| `PythonScriptExecutor` | `pythonPath` / `timeoutMillis` | `"python3"` / `60000` 毫秒 |
| `IbdManager` | `resourcePrefix` / `workDir` | 必填 / `{java.io.tmpdir}/ibd-{resourcePrefix}` |
| `IbdDataRestore` / `IbdToSqlFileConvertSystem` | 命令超时（内部常量 `COMMAND_TIMEOUT_SECONDS`） | 300 秒 |

---

## 5. SPI 扩展点

**本模块注册的 SPI**（`src/main/resources/META-INF/extensions/`）：

| SPI 接口 | 注册键 | 实现 | `@Spi` 注解声明 |
| --- | --- | --- | --- |
| `com.chua.common.support.task.restore.DataRestore` | `ibd` | `com.chua.ibd.support.restore.IbdDataRestore` | `{"ibd", "ibd2sql"}`（注册文件仅含 `ibd` 键） |
| `com.chua.common.support.file.converter.FileConvertSystem` | `ibd2sql` | `com.chua.ibd.support.converter.IbdToSqlFileConvertSystem` | `"ibd2sql"` |

**本模块消费的外部 SPI / 契约**：

| 契约 | 用途 | 提供方 |
| --- | --- | --- |
| `FileSystem`（键 `excel`） | EXCEL 导出（`write(file).withHeaders(...).write(rows).finish()`） | `utils-support-excel-starter`；类路径缺失时抛 `IllegalStateException` 并提示改用 CSV / SQL |
| `DataRestore` / `AbstractDataRestore` / `DataRestoreConfig` / `DataRestoreResult` / `ExportFormat` | 还原契约、配置与结果模型 | `utils-support-common-starter` |
| `FileConvertSystem` / `FileSource` / `ConvertSetting` | 文件转换契约 | `utils-support-common-starter` |
| `CmdExecutors` / `CmdResult` / `OsFamily` | 执行 Python / ibd2sql 外部命令 | `utils-support-common-starter` |
| `ScriptExecutor` | `IbdManager` 的可插拔脚本执行器（`setScriptExecutor` 注入，非全局 SPI） | 本模块自带 `PythonScriptExecutor` 实现 |

---

## 6. 构建与依赖

- **坐标**：`com.chua:utils-support-ibd-starter:4.0.0.51`，父 POM `utils-support-filesystem-parent`（同版本）。
- **JDK / 编码**：Java `25`（根 POM `maven.compiler.release=25`），源码编码 UTF-8 无 BOM。
- **模块依赖**：
  - `utils-support-common-starter` —— SPI 契约（`DataRestore` / `FileConvertSystem` / `FileSystem`）、命令执行（`CmdExecutors`）、集合包装（`Wraps`）与传递的 Jackson（SDI JSON 解析）；
  - `utils-support-excel-starter` —— EXCEL 导出所需的 `excel` FileSystem 实现（含 POI）。
- **可选运行时外部依赖**：Python 3 + ibd2sql 仓库（仅 `ibd2sql` / `auto` 引擎需要；native 引擎零外部依赖）。
- **GraalVM**：附带 `META-INF/native-image/com.chua/utils-support-ibd-starter/`（`reflect-config.json`、`resource-config.json`）。
- **编译验证**：在本模块目录执行 `mvn -o -q compile -DskipTests`，期望 `EXIT=0`。

---

## 7. 边界：这些能力不在本模块

| 不提供 | 说明 |
| --- | --- |
| Spring Boot 自动装配 | 无自动配置类、无 spring-boot 依赖、无配置属性前缀；「starter」仅为本仓命名惯例 |
| 连接 / 查询 MySQL | 不含 JDBC、连接池或 MySQL 协议客户端；SQL 只是落盘脚本，由使用方自行执行 |
| 写入 / 修复表空间 | `IbdTablespace` 以只读方式打开文件；本模块不做 `.ibd` 写入、修复或加解密（源码未提供） |
| 非 InnoDB 数据源 | 不支持 MyISAM / CSV 引擎文件；不解析 redo log、undo、binlog、`.frm`；系统表空间（`ibdata*`）无对应入口（源码未提供） |
| JSON 输出格式 | `ExportFormat.JSON` 存在于公共枚举，但 `IbdDataRestore` 只实现 CSV / SQL / EXCEL，传入其它格式抛 `UnsupportedOperationException` |
| 内置 Python 或 ibd2sql | 解释器与 ibd2sql 工具均需使用方自备；ibd2sql 未发布到 PyPI |
| 其它脚本语言执行器 | `ScriptExecutor` 目前仅有 `PythonScriptExecutor` 一个实现；Groovy / JS 执行器源码未提供 |
| 单元测试 | 本模块仓库内未见 `src/test` 测试源码 |
