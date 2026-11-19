# utils-support-calcite-starter

> 基于 **Apache Calcite** 的多源 SQL 聚合门面：把多个 JDBC `DataSource`、内存虚拟表（`DataTable`）与 `Engine` 实体表聚合为一个可直接执行标准 SQL 的 `javax.sql.DataSource`，并支持 WHERE 条件下推、INSERT/DELETE 写回与简单 UPDATE 路由。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承仓库父级 `java.version=25`、`maven.compiler.release=25`）｜包名：`com.chua.calcite.support.datasource`、`com.chua.calcite.support.conversion`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 规模](#13-规模)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 API 分组与关键方法](#22-api-分组与关键方法)
  - [2.3 行为要点](#23-行为要点)
- [3. 快速开始](#3-快速开始)
  - [3.1 内存虚拟表 + SQL 查询](#31-内存虚拟表--sql-查询)
  - [3.2 Engine 文件表 + SQL 查询](#32-engine-文件表--sql-查询)
  - [3.3 简单 UPDATE 路由到 Engine](#33-简单-update-路由到-engine)
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
    <artifactId>utils-support-calcite-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 用 Calcite 把多个数据源「粘」成一个统一 `DataSource`，对其执行标准 SQL（聚合、JOIN、过滤等由 Calcite 完成） | 不是数据库驱动，也不是连接池 |
| 数据来源 | ① JDBC `DataSource`（内部转 `JdbcSchema`）② `DataScheme` 虚拟库 ③ `DataTable` 虚拟表 ④ `Engine` 实体表 | 不管理真实数据库连接的生命周期，连接由调用方提供与关闭 |
| 读写 | SELECT 跨源查询；`MutableDataTable` 支持 INSERT/DELETE 写回；`SourceDataTable` 额外支持简单 UPDATE 路由到 `Engine` | Calcite `ModifiableTable` 本身不支持 SQL UPDATE（源码注释明确），UPDATE 走 `EngineUpdateSqlExecutor` 路由，且仅支持简单形态 |
| 配置 | 零配置，编程式 API 为主 | 无 Spring 自动配置、无 `application*.yml` 配置键 |
| 依赖 | 仅依赖 `utils-support-common-starter`、`utils-support-datasource-starter`（provided）、`calcite-core` | 不依赖任何 `spring-*` 上层模块 |

**判断口径**：只要「用 SQL 查一批异构数据」，无论数据来自真实 JDBC 库、内存表还是 Engine 实体表，都可以用本模块聚合；需要新数据库协议/驱动支持的，是各 `*-starter`（如 mysql-starter）的职责，不是本模块的职责。

### 1.3 规模

- Java 源文件 **10** 个：`datasource` 包 9 个 + `conversion` 包 1 个。
- SPI 扩展声明文件 **2** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json` / `proxy-config.json` / `resource-config.json`（`META-INF/native-image/com.chua/utils-support-calcite-starter/`）。
- 内置 `saffron.properties`（Calcite 字符集/排序默认值，见 [4. 配置项](#4-配置项)）。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 多个 JDBC 数据源用一条 SQL 联查 | `CalciteDataSourceCreator.addDataSource(name, ds)` + `create()` | 每个 `DataSource` 注册为一个同名 schema（内部 `JdbcSchema`） |
| 内存造虚拟表跑 SQL | `CalciteDataScheme` + `CalciteDataTable` | `addRow(Object...)` 灌数据，`SELECT * FROM schema.table` 直接查 |
| 把 Engine 实体表暴露成 SQL 表 | `EngineDataSourceFactory.from(...)` / `fromFile(...)` | 实体类 → 表，表名为驼峰转下划线（如 `User` → `user`） |
| SQL UPDATE 直接落到 Engine | `EngineAwareDataSource`（自动拦截）/ `EngineUpdateSqlExecutor` | 仅支持「简单 SET + 等值 WHERE」形态，详见 2.3 |
| WHERE 条件下推到 Engine | `CalciteDataTableAdapter`（对 `SourceDataTable` 自动启用） | 支持 `=`、`!=`、`>`、`>=`、`<`、`<=`、`LIKE`、`IN`、`NOT IN`、`BETWEEN`、`IS NULL`、`IS NOT NULL` 及 `AND`/`OR`/`NOT` 组合 |
| SQL INSERT/DELETE 写回 | `CalciteDataTableAdapter.of(dataTable)`（`MutableDataTable` 自动启用 `ModifiableTable`） | 变更同步回内存表；`SourceDataTable` 再持久化到 Engine 存储/文件 |
| 多数据源转换（datasource 引擎侧 SPI） | `CalciteDataSourceConversion`（SPI 名 `CALCITE`） | `List<DataSource>` → Calcite `DataSource`，schema 固定命名 `ds_0`、`ds_1`… |

### 2.2 API 分组与关键方法

| 类 | 关键方法 / 构造 | 说明 |
| --- | --- | --- |
| `CalciteDataSourceCreator` | `static newCreator()`；`addDataSource(String, DataSource)`；`addScheme(DataScheme)`；`addTable(String schemaName, DataTable)`；`create()` | 链式注册，`create()` 返回聚合后的 `DataSource`；SPI 名 `calcite`，且标注 `@SpiDefault` |
| `CalciteDataScheme` | `new CalciteDataScheme(String name)`；`addTable(DataTable)`；`getName()`；`getTableNames()`；`getTable(String)`；`close()` | 虚拟库：一个名字 + 多张 `DataTable` |
| `CalciteDataTable` | `new CalciteDataTable(String name, List<String> columnNames, List<Class<?>> columnTypes)`；`addRow(Object... values)`；`getColumnTypes()`；`getName()`；`getColumnNames()`；`getData()`；`getRowCount()` | 内存虚拟表，列名 + 列类型 + 行数据 |
| `CalciteDataTableAdapter` | `static of(DataTable)` | 把 `DataTable` 适配为 Calcite `FilterableTable`；`MutableDataTable` 返回支持 `ModifiableTable` 的子适配器，其余只读 |
| `SourceDataTable` | `new SourceDataTable(String name, Engine engine, Class<?> entityClass)`；`getData()`；`addRow(Map)`；`replaceAllRows(List<Map<String,Object>>)`；`getEngine()`；`getEntityClass()`；`getColumnTypes()` | Engine 实体表 ↔ SQL 表：读走 `engine.query(entityClass).list()`，写回经 `AbstractEngine.store` / `FileEngine.save` 持久化 |
| `EngineDataSchema` | `new EngineDataSchema(String name, Engine engine)`；`addEntity(Class)`；`addEntity(String tableName, Class)`；`addEntities(Class...)`；`getEngine()`；`close()` | 把 `Engine` 适配为 `DataScheme`，每个实体类一张表；`close()` 会联动 `engine.close()` |
| `EngineDataSourceFactory` | `static from(Engine, String schemaName, Class<?>... entityClasses)`；`static fromFile(FileEngine, Class<?>...)`；`static fromFile(FileEngine, String schemaName, Class<?>...)` | 工厂：Engine → 统一 `DataSource`（自动包一层 `EngineAwareDataSource` 以支持 UPDATE 路由）；`fromFile` 默认 schema 名为 `file` |
| `EngineAwareDataSource` | `new EngineAwareDataSource(DataSource delegate, List<DataScheme> schemes)` | 代理 `Connection`/`Statement`/`PreparedStatement`：可路由的 UPDATE 在 `executeUpdate`/`execute`/`executeLargeUpdate` 时落到 Engine，其余调用透传 |
| `EngineUpdateSqlExecutor` | `Integer tryExecute(String sql)`；`RoutableUpdate parse(String sql)`；`RoutableUpdate.execute()` | 解析并路由简单 UPDATE；`parse` 只判定不执行，`execute` 才真正写入并返回影响行数 |
| `CalciteDataSourceConversion` | `DataSource convert(List<DataSource>, DataSourceEnvironment)`；`String type()` | SPI 名 `CALCITE`；`type()` 返回 `"CALCITE"`；入参列表为空抛 `IllegalArgumentException("至少需要一个数据源")` |

### 2.3 行为要点

**条件下推（只读查询加速）**
- 仅当底层表是 `SourceDataTable` 时启用：Calcite 传入的 `RexNode` 过滤条件被翻译为 Engine 的 `LambdaQueryWrapper` 条件后由 Engine 原生执行。
- 支持的 SQL 操作符见速查表；`NOT` 会对操作符取反（如 `=`→`!=`、`>`→`<=`、`LIKE`→`NOT LIKE`）。
- 下推失败（条件不可翻译、Engine 查询异常等）自动回退为全量扫描。
- 非 `SourceDataTable` 的只读表始终全量扫描。

**INSERT/DELETE 写回**
- Calcite `ModifiableTable` 仅支持 INSERT / DELETE，不支持 SQL UPDATE（源码注释明确）。
- 写回时按「列名 → 值」整表刷新：`SourceDataTable` 走 `replaceAllRows` 并持久化（`AbstractEngine.store` + `FileEngine.save`）；其它 `MutableDataTable` 直接改写其 `getData()` 列表。
- `SourceDataTable` 的存储名规则：实体类简单名驼峰转下划线（如 `User` → `user`），并与表名各存一份以兼容。

**UPDATE 路由（`EngineUpdateSqlExecutor`）**
- 支持形态（MySQL 词法，反引号可选）：
  `UPDATE [`schema`.]`table` SET `c1`=v1, `c2`=v2 WHERE `c3`=v3 [AND `c4`=v4 ...]`
- SET/WHERE 值支持：字符串字面量、整数/小数、`NULL`、`TRUE`/`FALSE`。
- 以下情况**不路由**（交由 Calcite 或直接拒绝）：
  - 一条 SQL 里含多条语句（出现 `;`）；
  - 含 `?` 参数占位符（绑定值只在 JDBC 侧可见，引擎侧无法复现）；
  - WHERE 含非等值条件（存在却解析不出完整等值条件时必须拒绝，避免退化为全表更新）；
  - SET/WHERE 出现列引用、表达式、函数、子查询等无法解析的字面量。
- 执行时机：`EngineAwareDataSource` 在 `prepareStatement` 阶段只做 `parse` 判定，不落库；真正写入推迟到 `executeUpdate`/`execute`/`executeLargeUpdate` 时刻，与 JDBC 语义一致。

**SQL 类型映射（`CalciteDataTableAdapter`）**

| Java 类型 | Calcite 类型 |
| --- | --- |
| `String` | `VARCHAR` |
| `Integer` / `int` | `INTEGER` |
| `Long` / `long` | `BIGINT` |
| `Short` / `short` | `SMALLINT` |
| `Byte` / `byte` | `TINYINT` |
| `Double` / `double` | `DOUBLE` |
| `Float` / `float` | `FLOAT` |
| `Boolean` / `boolean` | `BOOLEAN` |
| `java.math.BigDecimal` | `DECIMAL` |
| `java.sql.Date` | `DATE` |
| `java.sql.Time` | `TIME` |
| `java.sql.Timestamp` / `java.util.Date` | `TIMESTAMP` |
| `byte[]` | `VARBINARY` |
| 其它 | `VARCHAR` |

- `SourceDataTable` 的列类型直接取 getter 返回类型；其它表从首行数据推断（取不到按 `Object` 处理）。
- 所有列均声明为可空（`NULL` 允许）。

**Calcite 连接属性**
- `CalciteDataSourceCreator` 默认携带 `lex=MYSQL`、`fun=mysql`（源码内写死）。
- `CalciteDataSourceConversion` 固定使用 `lex=MYSQL`。
- 每次调用 `getConnection()` 都会新建一个 `jdbc:calcite:` 连接并重新注册 schema。

---

## 3. 快速开始

### 3.1 内存虚拟表 + SQL 查询

```java
import com.chua.calcite.support.datasource.CalciteDataScheme;
import com.chua.calcite.support.datasource.CalciteDataSourceCreator;
import com.chua.calcite.support.datasource.CalciteDataTable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;

// 1. 创建聚合数据源（也可先 addDataSource("mydb", myDataSource) 挂真实 JDBC 库）
DataSource unified = new CalciteDataSourceCreator()
        .addScheme(new CalciteDataScheme("sales")
                .addTable(new CalciteDataTable("orders",
                        List.of("id", "amount"),
                        List.of(Integer.class, Double.class))
                        .addRow(1, 99.5)
                        .addRow(2, 128.0)))
        .create();

// 2. 通过 SQL 查询
try (Connection conn = unified.getConnection();
     ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM sales.orders")) {
    while (rs.next()) {
        System.out.println(rs.getInt("id") + " -> " + rs.getDouble("amount"));
    }
}
```

### 3.2 Engine 文件表 + SQL 查询

节选自 `EngineDataSourceFactory` 的源码 Javadoc：

```java
FileEngine engine = new FileEngine().load("user", "users.csv");
DataSource ds = EngineDataSourceFactory.from(engine, "file", User.class);
// SELECT * FROM `file`.`user` WHERE `age` > 25
```

也可以用文件引擎的快捷入口（schema 默认 `file`）：`EngineDataSourceFactory.fromFile(engine, User.class)`。

### 3.3 简单 UPDATE 路由到 Engine

经 `EngineDataSourceFactory` 得到的 `DataSource` 已被 `EngineAwareDataSource` 包装，简单 UPDATE 会自动路由到 Engine：

```java
try (Connection conn = ds.getConnection()) {
    int rows = conn.createStatement()
            .executeUpdate("UPDATE `file`.`user` SET `age` = 30 WHERE `name` = 'zhangsan'");
}
```

仅支持「等值 WHERE + 字面量赋值」的简单形态；复杂 UPDATE 不会路由（见 2.3）。

---

## 4. 配置项

**无外部配置项。**

依据：

- 模块内没有任何 Spring 自动配置文件（无 `spring.factories` / `AutoConfiguration.imports`），也没有 `@ConfigurationProperties` 之类的配置绑定类；全部能力通过编程式 API 使用。
- 唯一的资源文件 `saffron.properties` 是 Calcite 自身的默认值声明，随 jar 发布，无需使用者配置，内容为：

| 键 | 值 |
| --- | --- |
| `calcite.default.collation.name` | `UTF-8$zh_CN` |
| `calcite.default.nationalcharset` | `UTF-8` |
| `calcite.default.charset` | `UTF-8` |
| `calcite.default.debug` | `true` |

- Calcite 连接属性 `lex=MYSQL`、`fun=mysql` 在 `CalciteDataSourceCreator` 中以代码常量形式写死，当前不对外暴露修改入口。

---

## 5. SPI 扩展点

本模块**不定义新的 SPI 接口**；它实现的是上游 `utils-support-datasource-starter` 的两个 SPI，并通过 `META-INF/extensions/` 注册：

| 上游 SPI 接口 | 本模块实现 | SPI 名 | 注册文件 |
| --- | --- | --- | --- |
| `com.chua.datasource.support.datasource.DataSourceCreator` | `CalciteDataSourceCreator`（`@Spi("calcite")`，`@SpiDefault`） | `calcite` | `META-INF/extensions/com.chua.datasource.support.datasource.DataSourceCreator` |
| `com.chua.datasource.support.engine.DataSourceConversion` | `CalciteDataSourceConversion`（`@Spi("CALCITE")`） | `CALCITE` | `META-INF/extensions/com.chua.datasource.support.engine.DataSourceConversion` |

扩展方式：

- 想替换「多源聚合」的实现 → 实现上游 `DataSourceCreator` 接口并注册自己的 SPI 实现；
- 想新增「数据源转换」类型 → 实现上游 `DataSourceConversion` 接口并注册；
- 一般使用无需扩展，直接用 `CalciteDataSourceCreator` / `EngineDataSourceFactory` 的编程式 API 即可。

---

## 6. 构建与依赖

```text
utils-support-calcite-starter (4.0.0.51)
├── com.chua:utils-support-common-starter
├── com.chua:utils-support-datasource-starter   (scope: provided)
└── org.apache.calcite:calcite-core             (${calcite.version} = 1.41.0)
```

- `utils-support-datasource-starter` 为 `provided`：`DataScheme`、`DataTable`、`MutableDataTable`、`DataSourceCreator`、`DataSourceConversion`、`FileEngine` 等 API 来自它，运行期由宿主环境提供，不会被动打入。
- 编译（仓库统一闸门）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 / 说明 |
| --- | --- |
| 各数据库的驱动、方言、连接池 | 各 `*-starter`（如 mysql-starter、duckdb-starter 等）；本模块只接收现成的 `DataSource` |
| 真实数据库连接的生命周期管理 | 调用方负责；本模块每次 `getConnection()` 新建 Calcite 连接，不池化 |
| 完整 SQL 引擎/优化器能力 | 由 Apache Calcite 提供；本模块只做聚合、适配与路由 |
| SQL UPDATE 的通用支持 | Calcite `ModifiableTable` 不支持 UPDATE；本模块仅将「简单等值 WHERE」的 UPDATE 路由到 Engine，复杂 UPDATE 不支持 |
| 非等值条件、聚合函数等复杂 WHERE 的 Engine 下推 | 不支持；此类查询对只读虚拟表走全量扫描（内存表数据量需自行评估） |
| 分布式/持久化 SQL 引擎 | 本模块是进程内聚合门面，不提供跨节点查询、事务恢复等能力 |
| 图形化/管理端点 | 无；纯工具库，不含任何 Controller、端点或 UI |
