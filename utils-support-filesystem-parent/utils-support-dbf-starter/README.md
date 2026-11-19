# utils-support-dbf-starter

> utils 体系的 **DBF（dBASE）文件操作模块**：提供 `.dbf` 文件的读取与写入（`FileSystem` SPI 实现，key 为 `dbf`），以及一个内存可变数据表 `DbfDataTable`，底层基于 javadbf 库。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.dbf.support`

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
    <artifactId>utils-support-dbf-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 能力 | `.dbf`（dBASE，dBASE/FoxPro 等使用的表格数据格式）文件的读取与写入；内存可变数据表的增删改与写回 | 不是 dBASE/FoxPro 数据库客户端，不提供 SQL、索引、事务 |
| 架构 | `FileSystem` SPI 的 `dbf` 实现（`DbfFileSystem`）+ 内存表 `DbfDataTable` | 不含 CSV/JSON/Excel 等其它文件格式的实现 |
| 依赖 | 只依赖同仓 `utils-support-common-starter`、`utils-support-datasource-starter` 与第三方 `javadbf` | 不依赖 Spring、不依赖任何数据库驱动 |

模块共 4 个 Java 源文件：

| 类 | 说明 |
| --- | --- |
| `com.chua.dbf.support.file.impl.DbfFileSystem` | DBF 文件系统 SPI 实现（`@Spi("dbf")`），`read(File)` 返回 `DbfReadBuilder`，`write(File)` 返回 `DbfWriteBuilder` |
| `com.chua.dbf.support.file.impl.DbfReadBuilder` | DBF 读取构建器，`rows()` 返回 `List<Map<String, Object>>` |
| `com.chua.dbf.support.file.impl.DbfWriteBuilder` | DBF 写入构建器，支持 `List<Map>`、POJO 等数据源 |
| `com.chua.dbf.support.datasource.DbfDataTable` | DBF 数据表实现（继承 `com.chua.datasource.support.datasource.MutableDataTable`），数据在内存中维护，通过 `save()` 写出到文件 |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 读取 `.dbf` 全部行 | `FileSystem.create("dbf").read(file)` → `DbfReadBuilder.rows()` | 返回 `List<Map<String, Object>>`，键为 DBF 字段名 |
| 按扩展名自动选择 | `FileSystem.auto(new File("x.dbf"))` | `.dbf` 后缀命中 `dbf` 实现 |
| 写入行数据 | `fs.write(file)` → `DbfWriteBuilder.write(rows)` | `write(List<Map<String, Object>>)` 写完自动 `finish()`；`write(Object)` 只入暂存区，需再调 `finish()` |
| 读取列名映射 | `DbfReadBuilder.columnMapping(map)` | 读取时把 DBF 字段名替换为映射后的键名 |
| 读取行过滤 / 行转换 | `filter(predicate)` / `mapRows(function)` | 执行顺序：`columnMapping` → `filter` → `mapRows` |
| 写入列控制与行过滤 | `withHeaders(list)` / `filter(predicate)` | 固定写入列及顺序；不满足谓词的行跳过 |
| 读取回调 / 异步 | `withCallback(ReadCallback)` / `readAsync(callback)` | `onHeader` / `onBody` / `onComplete`；写侧对应 `WriteCallback` |
| 内存表 CRUD + 写回 | `new DbfDataTable(name, file)` + `addColumn` / `addRow` + `save()` | 内存维护，`save()` 写回原文件路径；`columnTypes()` 查看列类型 |

### 2.2 DBF 字段类型映射（读取时）

下表为 `DbfDataTable#dbfTypeToJavaClass` 声明的 DBF 字段类型 → Java 类型映射，经 `columnTypes()` 暴露为列类型元数据；`DbfReadBuilder` 与 `DbfDataTable` 的行数据本身不做二次类型转换，直接保留 javadbf `DBFReader` 的解析值：

| DBF 类型 | Java 类型 |
| --- | --- |
| `CHARACTER` / `MEMO` | `String` |
| `NUMERIC` / `FLOATING_POINT` / `DOUBLE` | `Double` |
| `LONG` | `Long` |
| `LOGICAL` | `Boolean` |
| `DATE` | `java.util.Date` |
| `TIMESTAMP` | `java.sql.Timestamp` |
| 其它 | `String` |

### 2.3 读取/写入的固定行为（源码写死，不可配置）

- 读取：IO 异常只记日志不抛出（`DbfReadBuilder` 记 `debug`、`DbfDataTable` 记 `warn`），返回已解析到的部分数据。
- 写入：所有值经 `String.valueOf` 转为字符串；字段类型统一 `CHARACTER`、长度 `254`；列名超过 10 字符截断为前 10 字符（DBF 规范限制）。
- `DbfDataTable.save()`：若解析文件时保留了原 DBF 字段定义，则按原字段写回；否则从当前列名推断字段。
- `DbfWriteBuilder.finish()`：遍历暂存区中的每个 `List` 条目逐条写出；条目为空或首元素不是 `Map` 时跳过；写失败时回调 `onComplete(false)` 并抛 `RuntimeException("DBF 写入失败")`。

---

## 3. 快速开始

### 3.1 读取 DBF 文件

```java
import com.chua.common.support.file.FileSystem;
import com.chua.dbf.support.file.impl.DbfReadBuilder;

import java.io.File;
import java.util.List;
import java.util.Map;

FileSystem fs = FileSystem.create("dbf");

// 读取全部行，键为 DBF 字段名
List<Map<String, Object>> rows =
        ((DbfReadBuilder) fs.read(new File("data.dbf"))).rows();
```

### 3.2 写入 DBF 文件

```java
import com.chua.dbf.support.file.impl.DbfWriteBuilder;

import java.util.List;
import java.util.Map;

DbfWriteBuilder wb = (DbfWriteBuilder) fs.write(new File("out.dbf"));

// write(List<Map<String, Object>>)：写入并自动 finish() 落盘
wb.write(rows);

// 注意：write(Object)（含 POJO，内部自动转 Map 列表）只入暂存区，
// 必须再调用 wb.finish() 才会真正写文件。
```

写入时可固定列与顺序、跳过不满足条件的行（先配置、后 write）：

```java
DbfWriteBuilder wb = (DbfWriteBuilder) fs.write(new File("out.dbf"));
wb.withHeaders(List.of("NAME", "CITY"));                 // 只写这两列，且按此顺序
wb.filter(row -> row.get("NAME") != null);               // 谓词为 false 的行跳过
wb.write(rows);                                          // 自动 finish()
```

### 3.3 读取配置：列名映射、行过滤、回调

```java
import com.chua.common.support.file.builder.ReadCallback;

DbfReadBuilder rb = (DbfReadBuilder) fs.read(new File("data.dbf"));

rb.columnMapping(Map.of("XM", "姓名", "CSNY", "出生年月"))  // 字段名 → 输出键名
  .filter(row -> "北京".equals(row.get("姓名")))            // 列映射之后、行转换之前生效
  .mapRows(row -> { row.remove("内部字段"); return row; })   // 行数据转换
  .withCallback(new ReadCallback() {
      @Override
      public void onBody(Object row) {
          System.out.println(row);                          // 逐行回调
      }
  });

List<Map<String, Object>> rows = rb.rows();                // read() 等价于 rows()
```

### 3.4 内存表 CRUD 与写回

```java
import com.chua.dbf.support.datasource.DbfDataTable;

import java.io.File;
import java.util.Map;

// 文件存在则载入列与行；不存在则为空表
DbfDataTable table = new DbfDataTable("people", new File("people.dbf"));

table.addColumn("AGE");                          // 新增列
table.addRow(Map.of("NAME", "张三", "AGE", "18")); // 追加一行（键与列名对应）
table.save();                                     // 写回 people.dbf

table.getColumnNames();                           // 列名
table.getData();                                  // 行数据
table.getRowCount();                              // 行数
table.columnTypes();                              // 各列对应的 Java 类型
```

---

## 4. 配置项

**无。** 依据：本模块源码与资源中不存在任何配置键——没有 `@ConfigurationProperties` / `@Value` 注解，没有 `application.yml` / `properties` 资源文件。仅有的资源文件是：

- `META-INF/extensions/com.chua.common.support.file.FileSystem`（SPI 注册文件，内容：`dbf=com.chua.dbf.support.file.impl.DbfFileSystem`）；
- `META-INF/native-image/com.chua/utils-support-dbf-starter/` 下的 `reflect-config.json` 与 `resource-config.json`（GraalVM native-image 元数据）。

写入格式相关的行为（列名截断 10 字符、`CHARACTER(254)`、值转字符串）均为源码中的固定逻辑，见 [2.3](#23-读取写入的固定行为源码写死不可配置)。

---

## 5. SPI 扩展点

本模块**不定义新的 SPI 接口**，只向 utils 体系的 `FileSystem` SPI 注册一个实现：

| 项 | 值 |
| --- | --- |
| SPI 接口 | `com.chua.common.support.file.FileSystem` |
| 注册 key | `dbf` |
| 实现类 | `com.chua.dbf.support.file.impl.DbfFileSystem` |
| 注册文件 | `src/main/resources/META-INF/extensions/com.chua.common.support.file.FileSystem` |

消费方式（均来自 `FileSystem` / `ServiceProvider` 的既有机制）：

```java
FileSystem fs1 = FileSystem.create("dbf");                 // 按 SPI 名
FileSystem fs2 = FileSystem.auto(new File("x.dbf"));       // 按扩展名推断
// 或 ServiceProvider.of(FileSystem.class).getExtension("dbf")
```

读写构建器的链式能力（`withCharset`、`withCallback`、`columnMapping`、`filter`、`withHeaders`、`mapRows` 等）继承自 `utils-support-common-starter` 的 `ReadBuilder` / `WriteBuilder`；本模块只覆盖了表格读取 `rows()` / `read()` 与写入 `write(...)` / `finish()`。`asLines`、`asString`、`toMap`、`toObject`、`listEntries`、`extractAll` 等归档/文本能力未被 DBF 覆盖，调用会抛 `UnsupportedOperationException`。

---

## 6. 构建与依赖

父模块：`utils-support-filesystem-parent`（聚合于 `utils-support-parent-starter`），版本 `4.0.0.51`。

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | `FileSystem` SPI、`ReadBuilder` / `WriteBuilder`、`Wraps` 等基础能力 |
| `com.chua:utils-support-datasource-starter` | `${project.version}`（4.0.0.51） | `MutableDataTable` / `DataTable` 内存表契约 |
| `com.github.albfernandez:javadbf` | `1.14.1`（由根 pom `dependencyManagement` 统一管理） | DBF 读写底层库 |
| `org.projectlombok:lombok` | provided（父 pom 统一提供） | `@Slf4j` 日志 |

GraalVM 原生镜像：父 pom 提供 `native` profile（`native-maven-plugin`），本模块在 `src/main/resources/META-INF/native-image/com.chua/utils-support-dbf-starter/` 下携带 `reflect-config.json` 与 `resource-config.json`。

本模块目录内编译验证：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

- **不是数据库**：不连接 dBASE / FoxPro 数据库服务器，无 JDBC、无网络访问；只读写 `.dbf` 文件本身与内存表。
- **无 SQL / 索引 / 事务**：`DbfDataTable` 是内存中的列 + 行结构，`save()` 为全量覆写文件，不提供增量提交、回滚或查询引擎。
- **只有 `dbf` 一种格式**：CSV/JSON/XML/TXT/ZIP/TAR 等由 `utils-support-common-starter` 内置，YAML 由 `utils-support-core-parent` 下的 `utils-support-filesystem-starter` 提供，Excel/PDF/Word 等由 `utils-support-filesystem-parent` 下对应 starter 提供，本模块不重复实现。
- **写出不保留原字段类型**：`DbfWriteBuilder` 写出的所有列都是 `CHARACTER(254)` 字符串；只有 `DbfDataTable` 在已解析出原字段定义时才按原字段写回。
- **列名长度受 DBF 规范限制**：写出时超过 10 字符的列名会被截断为前 10 字符。
- **读取无异常上抛**：文件损坏等 IO 异常只记日志，`rows()` / 构造 `DbfDataTable` 返回已解析到的部分数据，需自行校验结果完整性。
