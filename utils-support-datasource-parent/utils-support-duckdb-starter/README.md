# utils-support-duckdb-starter

> DuckDB 嵌入式 OLAP 数据库的 **ORM / Engine 适配器**：把 DuckDB JDBC 驱动接入 utils 体系的 `Engine` SPI，提供同步/伪响应式两套引擎入口、内存数据过滤、仅表级的对象化 DDL、MySQL 风格迁移脚本方言转换，以及数据湖日志的本地落地。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承父工程 `maven.compiler.source/target=25`，含 `--enable-preview`）｜包名：`com.chua.duckdb.support`（子包：`engine` / `meta` / `flyway` / `sink`）

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
    <artifactId>utils-support-duckdb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | DuckDB JDBC 驱动（`org.duckdb:duckdb_jdbc`）与 utils `Engine` SPI 之间的适配层 | 不是 DuckDB 数据库本体，不含数据库服务器安装 |
| 查询 | 同步 `DuckDBEngine`（JDBC + 内存双通道）与伪响应式 `DuckDBReactorEngine` | 不提供 R2DBC 驱动；响应式是 `boundedElastic` 调度阻塞 JDBC 的伪实现 |
| 元数据 | 仅表级对象化 DDL（建表 / 删表 / 重命名，`DuckdbMetaData`） | 视图 / 索引 / 触发器 / 存储过程 / 外键 / 搜索索引 / 用户 / 权限均抛 `UnsupportedOperationException` |
| 迁移 | MySQL 风格脚本 → DuckDB 方言转换（`DuckdbScriptConverter`） | 不是迁移执行框架本体（`Flyway` 契约在 common-starter） |
| 生态 | 实现 `Engine`、`ReactorEngine`、`MetaData`、`ScriptConverter`、`DataSink` 五个上层 SPI | 不定义新的 SPI 接口；Lambda 包装器、分页、方言等契约在 common-starter / datasource-starter |

**判断口径**：凡「DuckDB 怎么接进 utils 引擎体系」看本模块；凡「Lambda 包装器怎么用、SQL 怎么拼、分页怎么翻」看 `utils-support-common-starter`（契约）与 `utils-support-datasource-starter`（JDBC 基类实现）。

### 1.3 规模

- Java 源文件 **5** 个，包名根 `com.chua.duckdb.support`，全部 `@since 4.0.0.42`。
- SPI 扩展声明 **5** 个（`src/main/resources/META-INF/extensions/`）。
- GraalVM native-image 配置 **3** 个：`reflect-config.json`、`proxy-config.json`、`resource-config.json`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建引擎 | `new DuckDBEngine()` 或 `Engine.create("duckdb")` | SPI 扩展键 `duckdb` |
| 注册连接 | `engine.addDataSource("default", "jdbc:duckdb:")` | 内存库；文件库 `jdbc:duckdb:/path/to/data.duckdb` |
| 内存数据查询 | `engine.store("city", list)` → `engine.query(City.class)...` | 先 `store` 再 Lambda 过滤，不落库 |
| Lambda 查询 | `engine.query(User.class).eq(User::getName, "张三").list()` | 能力继承自 `Engine` 契约 |
| Lambda 更新 / 删除 | `engine.update(User.class).set(...).eq(...).update()` / `engine.delete(User.class).eq(...).remove()` | 真实 JDBC 执行 |
| 插入 | `engine.insert(User.class, user)` / `engine.insertBatch(User.class, list)` | 有 JDBC 执行器时渲染 INSERT 落库 |
| 原生 SQL | `engine.query("select * from user where age > ?", 18)` / `engine.execute(...)` | 查询返回 `List<Map<String, Object>>` |
| 对象化建表 / 删表 | `engine.meta().table("t")` | `DuckdbMetaData`，仅表级 |
| 迁移脚本转换 | SPI 键 `duckdb` 的 `ScriptConverter` | MySQL 风格 → DuckDB 方言 |
| 响应式查询 | `new DuckDBReactorEngine()` → `query(User.class)...list()` | 返回 Reactor `Flux` |
| 日志落地 | `DataSink` SPI 键 `duckdb-log` | `DuckDbLogSink` 写 `datalake_log` 表 |

### 2.2 DuckDBEngine（同步引擎，`@Spi("duckdb")`）

继承 `JdbcEngine`，是本模块的核心入口。

| 方法 | 说明 |
| --- | --- |
| `<T> DuckDBEngine store(String name, List<T> data)` | 按名称（表名）存入内存数据；首次 `store` 会把该名称设为默认数据源名 |
| `<T> DuckDBEngine store(List<T> data)` | 存入默认名 `default` |
| `Engine addDataSource(String name, String jdbcUrl)` | 注册 DuckDB 数据源（无用户名密码，适用于嵌入式内存库） |
| `Engine addDataSource(String name, String jdbcUrl, String username, String password)` | 带凭据注册 |
| `void close()` | 幂等关闭引擎，释放各数据源唯一的共享连接 |

行为要点（均来自源码注释）：

- **双数据来源**：`store(...)` 的内存数据（走 `MemoryWhereParser` 本地过滤）与 `jdbc:duckdb:` 连接的真实 DuckDB SQL；查询时内存有数据走内存链路，否则下推 SQL，有 JOIN 时强制走 JDBC。
- **单共享连接**：每个数据源内部只维护一条底层连接，连接句柄经动态代理屏蔽 `close()`——try-with-resources 释放句柄时只丢引用、不真正关库，避免内存库表结构丢失。
- **关闭语义**：`close()`（经 `Engine.close()` → `EngineDataSource.close()` 触发）先置标记再释放，只关一次；关闭后取连接或继续使用旧句柄都会抛出带明确原因的 `IllegalStateException`，不会静默重建连接。
- **并发限制**：共享连接内的语句需由调用方按线程串行执行，DuckDB 的单个连接不支撑并发查询。
- 建连失败时 `SQLException` 原样抛出；共享连接关闭失败会以 slf4j `warn` 记录，不静默丢弃。

### 2.3 DuckDBReactorEngine（响应式入口，`@Spi("duckdb")`）

继承 `JdbcReactorEngine`，构造时内部持有同步 `DuckDBEngine`。

- `addDataSource(String name, String jdbcUrl)`：委托同步引擎注册数据源，并注册纯 JDBC 数据源（duckdb 无 R2DBC 驱动，执行/查询走 JDBC 路径）。
- 当前为**伪响应式**实现：`boundedElastic` 调度阻塞 JDBC 调用。
- 用法（源码 javadoc 原例）：

```java
DuckDBReactorEngine engine = new DuckDBReactorEngine();
engine.addDataSource("default", "jdbc:duckdb:");
Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
```

### 2.4 DuckdbMetaData（元数据，SPI 键 `duckdb`）

继承 `AbstractMetaData`；`engine.meta()` 会按默认数据源协议 `duckdb` 经 SPI 自动加载本实现（协议未命中时回退 `JdbcMetaData`）。

| 方法 | 行为 |
| --- | --- |
| `table()` / `table(String tableName)` | 返回 `JdbcMetaTable`，提供对象化 DDL（建表 / 删表 / 重命名） |
| `view()` / `view(String)` | 抛 `UnsupportedOperationException`（DuckDB 暂不支持视图元数据操作） |
| `index()` / `index(String)` | 抛 `UnsupportedOperationException`（索引入口） |
| `trigger()` / `trigger(String)` | 抛 `UnsupportedOperationException`（触发器） |
| `procedure()` / `procedure(String)` | 抛 `UnsupportedOperationException`（存储过程） |
| `fk()` / `fk(String)` | 抛 `UnsupportedOperationException`（外键） |
| `search()` / `search(String)` | 抛 `UnsupportedOperationException`（搜索引擎索引） |
| `user()` / `permission()` | 抛 `UnsupportedOperationException`（用户 / 权限） |

### 2.5 DuckdbScriptConverter（迁移脚本方言转换，SPI 键 `duckdb`）

`@Spi(value = "duckdb", order = 100)`，继承 `DefaultScriptConverter`；`supports("duckdb")`。本项目脚本统一以 MySQL 风格书写，本转换器把它改写成 DuckDB 可执行的语句。**不返回 `null` 跳过整句**，避免把本可执行的语句静默丢弃。

| 差异点 | 处理 |
| --- | --- |
| `USING BTREE` / `USING HASH` | 剥离（DuckDB 无索引组织方式概念；只匹配具名方式，不误伤 JOIN 的 `USING (a, b)` 列表） |
| `ON UPDATE CURRENT_TIMESTAMP` | 全语句兜底剥离（父类只处理 `CREATE TABLE` 分支） |
| `AUTO_INCREMENT` | 只剥离关键字、不改写列类型；DuckDB 自增请在脚本中直接写 `SERIAL` / `IDENTITY`（剥离后该列不再自动编号） |
| 类型映射 | `MEDIUMTEXT`/`LONGTEXT`/`TINYTEXT`/`TEXT`/`JSON` → `TEXT`；`DATETIME` → `TIMESTAMP`；`DOUBLE`、`TINYINT` DuckDB 原生支持，保留 |
| 函数映射 | `NOW()` → `CURRENT_TIMESTAMP`；`IFNULL(` → `COALESCE(`；`RAND()` → `RANDOM()`；`CONCAT(a, b)` 不改写 |
| 表尾物理属性与行内索引 | `ENGINE=`、`DEFAULT CHARSET=`、`COLLATE=`、`ROW_FORMAT=`、表级 `COMMENT=`、`AUTO_INCREMENT=n`、行内 `KEY`/`UNIQUE KEY`/`INDEX`、列级 `COMMENT` 由父类 `DefaultScriptConverter` 统一剥离 |

**前提**：`CREATE TABLE` 必须按每列一行的对齐格式书写（与 `SHOW CREATE TABLE` 导出风格一致）；父类的行内索引与列内联注释剥离是逐行正则，单行紧凑写法不会被识别。

**注册键说明**：扩展键必须等于协议名 `duckdb`。若改用 `ScriptConverter.SPI_NAME`（`script-converter`）会与兜底默认实现（`DefaultScriptConverter`，同键 `order=0`）同键竞争，导致其它协议的转换被整体遮蔽。

### 2.6 DuckDbLogSink（数据湖日志落地，SPI 键 `duckdb-log`）

实现 datalake 的 `DataSink`，把日志消息按「键/值」形式写入本地 duckdb 表。

| 方法 | 说明 |
| --- | --- |
| `type()` | 返回 `"duckdb-log"` |
| `setJdbcUrl(String)` | 设置 JDBC 连接串，默认 `jdbc:duckdb:`；文件库写作 `jdbc:duckdb:/路径/文件.db` |
| `start()` | 建连并执行 `CREATE TABLE IF NOT EXISTS datalake_log`；失败抛 `RuntimeException` |
| `write(DataEnvelope envelope, Map<String, Object> config)` | 把 envelope 解析出的键值逐条批量 INSERT；topic 取 envelope 第一个 topic，缺省 `"default"`；未连接返回 `false`，空数据返回 `true` |
| `stop()` | 关闭连接 |
| `getDataSource()` | 返回 `null`（本 sink 不对外提供引擎数据源） |

`datalake_log` 表结构（`start()` 时自动创建）：

| 列 | 类型 |
| --- | --- |
| `id` | `BIGSERIAL` |
| `trace_id` | `VARCHAR` |
| `pipeline_id` | `VARCHAR` |
| `topic` | `VARCHAR` |
| `log_key` | `VARCHAR` |
| `log_value` | `VARCHAR` |
| `log_timestamp` | `BIGINT` |
| `created_at` | `TIMESTAMP DEFAULT now()` |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-duckdb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例（同步引擎）

```java
import com.chua.duckdb.support.engine.DuckDBEngine;

import java.util.List;
import java.util.Map;

public class DuckDbDemo {

    public static void main(String[] args) {
        // Engine extends AutoCloseable：try-with-resources 退出时自动 close()
        try (DuckDBEngine engine = new DuckDBEngine()) {

            // 1) 连接嵌入式内存 DuckDB；文件库写作 jdbc:duckdb:/path/to/data.duckdb
            engine.addDataSource("default", "jdbc:duckdb:");

            // 2) 建表并写入（原生 SQL，? 占位符绑定）
            engine.execute("CREATE TABLE user (id BIGINT, name VARCHAR, age INT)");
            engine.execute("INSERT INTO user VALUES (?, ?), (?, ?)", 1L, "张三", 2L, "李四");

            // 3) 原生查询：返回 List<Map<String, Object>>
            List<Map<String, Object>> rows =
                    engine.query("SELECT * FROM user WHERE name = ?", "张三");
            System.out.println(rows);

            // 4) 纯内存数据过滤：不建表，直接 store（首次 store 设为默认数据源名）
            engine.store("city", List.of("北京", "上海"));
        } // 用完关闭，释放该数据源唯一的共享连接（幂等）
    }
}
```

Lambda 链式查询（能力继承自 `Engine` 契约，表名取实体类简单名的小写形式）：

```java
List<User> users = engine.query(User.class)
        .eq(User::getName, "张三")
        .gt(User::getAge, 18)
        .list();
```

不想 `new` 也可以走 SPI 工厂（扩展键未注册时抛可定位异常）：

```java
Engine engine = Engine.create("duckdb", e -> e.addDataSource("default", "jdbc:duckdb:"));
```

---

## 4. 配置项

**无。** 依据：

- 本模块 pom 只声明依赖，没有 Spring Boot 自动配置、没有 `@ConfigurationProperties`；
- `src/main/resources` 下只有 `META-INF/extensions/` SPI 声明与 `META-INF/native-image/` 配置，没有任何 `application*.yml/properties` 或配置元数据文件；
- 连接信息只能以代码方式传入：`DuckDBEngine.addDataSource(name, jdbcUrl[, username, password])`、`DuckDbLogSink.setJdbcUrl(String)`。

这是一个**零配置模块**：引入依赖、注册连接串即可使用。

---

## 5. SPI 扩展点

本模块**不定义**待扩展的 SPI 接口；它把 5 个实现注册到上层契约（common-starter / datasource-starter / datalake-sink-starter）的 `META-INF/extensions/` 文件中：

| SPI 契约（extensions 文件名） | 扩展键 | 本模块实现 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `duckdb` | `DuckDBEngine` |
| `com.chua.datasource.support.engine.ReactorEngine` | `duckdb` | `DuckDBReactorEngine` |
| `com.chua.common.support.lang.datasource.meta.MetaData` | `duckdb` | `DuckdbMetaData` |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | `duckdb` | `DuckdbScriptConverter` |
| `com.chua.datalake.support.spi.sink.DataSink` | `duckdb-log` | `DuckDbLogSink` |

获取方式：`Engine.create("duckdb")`，或 `ServiceProvider.of(契约.class).getExtension("duckdb")`。

GraalVM native-image 配置（`src/main/resources/META-INF/native-image/com.chua/utils-support-duckdb-starter/`）：

| 文件 | 内容 |
| --- | --- |
| `reflect-config.json` | `DuckDBEngine` 全量反射注册（构造器/方法/字段） |
| `proxy-config.json` | 动态代理接口：`java.sql.Connection`、`com.chua.common.support.lang.datasource.engine.Engine` |
| `resource-config.json` | 打包 `META-INF/extensions/.*` 资源 |

---

## 6. 构建与依赖

依赖树（来自本模块 `pom.xml`）：

```
utils-support-duckdb-starter (com.chua:4.0.0.51)
├── com.chua:utils-support-common-starter          compile   Engine/MetaData/ScriptConverter 等契约与工具
├── org.duckdb:duckdb_jdbc:1.5.6.0                 compile   DuckDB 官方 JDBC 驱动
├── com.chua:utils-support-datasource-starter      provided  JdbcEngine / JdbcReactorEngine 基类
└── com.chua:utils-support-datalake-sink-starter   provided  DataSink 契约（仅 sink 能力用到）
```

> `provided` 依赖**不传递**：宿主若需要 Engine SPI 装配或数据湖 sink 能力，请自行引入 `utils-support-datasource-starter` / `utils-support-datalake-sink-starter`。

构建（仓库 `utils-support-parent-starter` 根目录，JDK 25）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| Lambda 查询/更新/删除包装器、分页 `Page`、`Dialect` 方言、`Flyway` 迁移契约 | `utils-support-common-starter`（契约定义） |
| `JdbcEngine` / `JdbcReactorEngine` / `AbstractMetaData` 等 JDBC 基类实现 | `utils-support-datasource-starter` |
| MySQL / Neo4j / zvec 等其它数据库适配 | 同级模块（如 `utils-support-mysql-starter`） |
| 数据湖 `DataSink` 的采集与编排 | `utils-support-datalake-*` 模块 |
| DuckDB 数据库本体、原生库与服务器进程 | `org.duckdb:duckdb_jdbc` 驱动自带 |
| 连接池 | 本模块是「每数据源一条共享连接」模型；需要池化时用继承自 `Engine` 的 `addDataSource(String, EngineDataSource)` 包装自己的 `DataSource` |
| 视图 / 索引 / 触发器 / 存储过程 / 外键 / 搜索索引 / 用户 / 权限的元数据操作 | DuckDB 侧不支持，`DuckdbMetaData` 对应方法抛 `UnsupportedOperationException` |
| 真·响应式（R2DBC） | DuckDB 无 R2DBC 驱动，`DuckDBReactorEngine` 为伪响应式实现 |
