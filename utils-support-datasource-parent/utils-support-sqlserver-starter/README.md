# utils-support-sqlserver-starter

> SQL Server 数据库适配 Starter：同步/响应式双引擎、SQL Server 2000/2005 老版本兼容（jTDS）、迁移脚本方言转换（MySQL 风格 DDL → T-SQL）。
>
> 版本：`4.0.0.51`｜JDK：`25`｜包名：`com.chua.sqlserver.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 快速开始](#2-快速开始)
- [3. 数据库引擎](#3-数据库引擎)
- [4. 迁移脚本方言转换](#4-迁移脚本方言转换)
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
    <artifactId>utils-support-sqlserver-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块全称 `SQL Server Starter`，位于 `utils-support-parent-starter/utils-support-datasource-parent/` 之下，父模块为 `utils-support-datasource-parent`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎 | SQL Server 的 `Engine` / `ReactorEngine` SPI 实现（`@Spi("sqlserver")` / `@Spi("sqlserver-legacy")`），HikariCP 连接池 | 不是连接池本身，也不是 ORM / MyBatis / JPA |
| 老版本兼容 | SQL Server 2000/2005 的 jTDS 引擎（官方 `mssql-jdbc` 最低仅支持 2008 R2） | 不负责驱动安装；jTDS 为 `provided` 作用域，需宿主按需引入 |
| 迁移脚本 | `ScriptConverter` SPI 的 sqlserver 协议实现，把 MySQL 风格 DDL 改写成 T-SQL | 不是迁移执行器（`Flyway` 编排在 common/datasource 侧） |
| 响应式 | `JdbcReactorEngine` 的 SQL Server 注册路径（`boundedElastic` 伪响应式） | 不是真 R2DBC 管线（见 3.2 的驱动限制） |
| 依赖 | 只依赖 common / datasource 契约 + HikariCP + mssql-jdbc + r2dbc-mssql + reactor-core | 不依赖 Spring，不依赖任何上层业务 starter |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 连 SQL Server、执行查询 | `engine.SqlServerEngine` | `addDataSource(...)` + 继承自 `JdbcEngine` 的查询入口，HikariCP 默认最大连接 10 |
| 响应式访问 | `engine.SqlServerReactorEngine` | `boundedElastic` 调度阻塞 JDBC 调用的伪响应式实现，对外暴露 `Flux`/`Mono` |
| SQL Server 2000/2005 | `engine.SqlServerLegacyEngine` | jTDS 驱动，URL 参数经 `DataSourceOptions.jtdsUrlParams()` 传入 |
| 老版本响应式 | `engine.SqlServerLegacyReactorEngine` | jTDS 无 R2DBC 驱动，仍走 JDBC 路径；附 `queryAll`/`queryWhere`/`insert` 便捷方法 |
| 迁移脚本方言转换 | `flyway.SqlServerScriptConverter` | `INSERT IGNORE`/`ON DUPLICATE KEY UPDATE` 整条跳过、类型映射、幂等 DDL 改写为 `OBJECT_ID`/`sys.indexes` 守卫 |

---

## 2. 快速开始

### 2.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-sqlserver-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2.2 同步引擎：最短可运行示例

```java
import com.chua.sqlserver.support.engine.SqlServerEngine;

SqlServerEngine engine = new SqlServerEngine();
engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");
List<User> users = engine.query(User.class).list();
```

### 2.3 响应式引擎

```java
import com.chua.sqlserver.support.engine.SqlServerReactorEngine;

SqlServerReactorEngine engine = new SqlServerReactorEngine();
engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");
Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
```

> 响应式为伪响应式：内部经 `registerJdbcDataSource` 注册纯 JDBC 数据源，在 `boundedElastic` 上执行真实 JDBC 调用。r2dbc-mssql 驱动随本模块 runtime 引入，由 `JdbcReactorEngine` 经 SPI 发现；但 `SimpleMssqlStatement` 不支持参数绑定，执行仍走 JDBC 路径（核心引擎已内置该路由决策）。

### 2.4 老版本（2000/2005）兼容引擎

```java
import com.chua.sqlserver.support.engine.SqlServerLegacyEngine;

SqlServerLegacyEngine engine = new SqlServerLegacyEngine();
engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");

// NTLM 域认证：经 DataSourceOptions.jtdsUrlParams() 传 domain
DataSourceOptions opts = new DataSourceOptions("default", "localhost", 1433, "master",
        "DOMAIN\\sa", "password", null, Map.of("domain", "MYDOMAIN"));
engine.addDataSource(opts);
```

> jTDS 为 `provided` 作用域：使用 legacy 引擎前，宿主需自行引入 `net.sourceforge.jtds:jtds:1.3.1`。

---

## 3. 数据库引擎

### 3.1 SqlServerEngine（SPI 键 `sqlserver`，4.0.0.42 起）

- 继承 `com.chua.datasource.support.engine.JdbcEngine`。
- 连接池为 HikariCP，`setMaximumPoolSize(10)` 写死默认值。
- JDBC URL 固定模板：`jdbc:sqlserver://host:port;databaseName=db;encrypt=false;trustServerCertificate=true`（关闭加密以兼容默认证书配置）。
- 方言为 `Dialect.require("sqlserver")`；`setSource` 运行期不支持（抛 `UnsupportedOperationException`，需重新 `addDataSource`）。
- 注册前先经 `JdbcUrl.checkHost` / `checkPort` / `checkDatabase` 校验，避免 `null` 或分隔符静默改写连接串结构。
- 支持隧道穿透：`DataSourceOptions.tunnel()` 非空时先 `tunnel.open()`，连接落到 `127.0.0.1:隧道端口`，`tunnelPort()` 回填到 `EngineDataSource`。

### 3.2 SqlServerReactorEngine（SPI 键 `sqlserver`，4.0.0.42 起）

- 继承 `com.chua.datasource.support.engine.JdbcReactorEngine`，构造时内部持有 `new SqlServerEngine()`。
- `addDataSource(...)` 先校验 host/port/database，再委托同步引擎注册，最后 `registerJdbcDataSource(name, "jdbc:sqlserver://host:port;databaseName=db;encrypt=false;trustServerCertificate=true", username, password)` 挂到响应式 JDBC 路径。
- 已知限制：r2dbc-mssql 的 `SimpleMssqlStatement` 不支持参数绑定，即使经父类注册 R2DBC 连接工厂，执行时仍走 JDBC 路径。

### 3.3 SqlServerLegacyEngine（SPI 键 `sqlserver-legacy`，4.0.0.43 起）

- 面向 SQL Server 2000/2005：官方 `mssql-jdbc` 最低仅支持 2008 R2，本引擎改用 jTDS 1.3.x（社区维护最活跃的老版本兼容驱动）。
- JDBC URL：`jdbc:jtds:sqlserver://host:port/db;selectMethod=cursor`，再按 `DataSourceOptions.jtdsUrlParams()` 追加：
  - `selectMethod` 缺省 `cursor`（游标模式读取结果集）；
  - `domain` 非空时追加 `;domain=...`（NTLM 域认证）；
  - 其余键值对原样追加为 `;key=value`。
- 方言为 `Dialect.require("sqlserver2008")`；连接池、入参校验、隧道穿透、`setSource` 限制与主引擎一致（默认最大连接 10）。

### 3.4 SqlServerLegacyReactorEngine（SPI 键 `sqlserver-legacy`，4.0.0.43 起）

- 继承 `JdbcReactorEngine`，内部持有 `new SqlServerLegacyEngine()`；jTDS 无 R2DBC 驱动，伪响应式走 JDBC 路径。
- `addDataSource(...)` 委托 legacy 引擎注册，再把同一数据源经 `registerJdbcDataSource` 挂到响应式表。
- 额外便捷方法（表名/列名先过 `SqlName.isWord` 白名单校验）：
  - `queryAll(table)` → `SELECT * FROM table`；
  - `queryWhere(table, where, params...)` → `SELECT * FROM table WHERE ...`；
  - `insert(table, cols, vals...)` → `INSERT INTO table (cols...) VALUES (?...)`。

---

## 4. 迁移脚本方言转换

`SqlServerScriptConverter`（`@Spi(value = "sqlserver", order = 100)`，继承 `DefaultScriptConverter`，4.0.0.42 起）补齐了 SQL Server 此前落到兜底转换器时缺失的硬性差异。规则分两层：

**父类（`DefaultScriptConverter`）已处理**：反引号统一改写为双引号（SQL Server 开启 `QUOTED_IDENTIFIER` 后双引号即定界符）、剥离表尾 MySQL 属性 / 行内 `KEY`/`UNIQUE KEY` / 列内联 `COMMENT` / 列级 `CHARSET`/`COLLATE`、`TEXT`/`LONGTEXT`/`MEDIUMTEXT`/`TINYTEXT` → `VARCHAR(MAX)`、`DATETIME` → `DATETIME2`、`NOW()` → `GETDATE()`、`IFNULL(a,b)` → `ISNULL(a,b)`、列级自增 → `IDENTITY(1,1)`。

**本类追加的改写**（依据 SQL Server 官方文档，并经 SQL Server 2022 与 H2 `MODE=MSSQLServer` 兼容模式实测校准）：

| 来源写法 | 改写结果 | 原因 |
| --- | --- | --- |
| `INSERT IGNORE INTO ...` | 整条跳过（返回 `null`） | SQL Server 无「忽略冲突」修饰；等价写法需 `MERGE` 或先查后插（要表结构元数据） |
| `... ON DUPLICATE KEY UPDATE ...` | 整条跳过（返回 `null`） | 等价 upsert 需 `MERGE`，需表结构元数据 |
| `TINYINT` / `MEDIUMINT` | `INT` | SQL Server 无此数值类型（`TINYINT` 是 `BIT` 的同义词，存 0/1） |
| `BOOLEAN` / `BOOL` | `BIT` | SQL Server 布尔类型为 `BIT` |
| `YEAR` | `SMALLINT` | SQL Server 无 `YEAR` 类型 |
| `BLOB` / `VARBINARY(n)` / `BINARY(n)` | `VARBINARY(MAX)` | 统一二进制类型 |
| `FROM t alias`（表别名无 AS） | `FROM t AS alias` | 不补 AS 会被解析成 schema 名（实测 `Schema "X" not found`）；别名排除 WHERE/ON/GROUP 等关键字 |
| `CREATE TABLE IF NOT EXISTS t (...)` | `IF OBJECT_ID(N't', N'U') IS NULL EXEC(N'CREATE TABLE t (...)')` | 真 SQL Server 不支持该语法（H2 兼容模式才接受，实测 SQL Server 2022 报「关键字 'IF' 附近有语法错误」）；DDL 整条塞进 `EXEC`，规避 T-SQL `IF` 只管辖单条语句、表体 `NOT NULL` 被当语句延续的二义 |
| 索引列 `col(191)` | `col` | 前缀索引是 MySQL 专属语法；去掉长度后建整列索引，语义更严格、不放宽约束 |
| `CREATE [UNIQUE] INDEX IF NOT EXISTS ix ON t (...)` | `IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name=... AND object_id=OBJECT_ID(...)) EXEC(N'CREATE INDEX ...')` | 真 SQL Server 不支持（实测报「关键字 'NOT' 附近有语法错误」） |
| `DROP INDEX IF EXISTS ix ON t` | `IF EXISTS (SELECT 1 FROM sys.indexes ...) DROP INDEX ix ON t` | SQL Server 的 `DROP INDEX` 语法是 `DROP INDEX ix ON t`，无 `IF EXISTS` 修饰（2016+ 也没有） |
| `JSON`（列类型位） | `NVARCHAR(MAX)` | SQL Server 无 `JSON` 类型（只有 JSON 函数）；先兜底剥离 `COMMENT` 再做 lookahead 匹配，防漏 |
| `TIMESTAMP`（列类型位） | `DATETIME2` | SQL Server 的 `TIMESTAMP` 是 `ROWVERSION` 同义词（8 字节二进制版本号、不能带 DEFAULT、全表限一个） |
| `DOUBLE` / `DOUBLE PRECISION`（列类型位） | `FLOAT` | SQL Server 无 `DOUBLE`；被当列名时误报「关键字 'NOT' 附近有语法错误」，极具迷惑性 |
| `TRUE` / `FALSE` 字面量 | `1` / `0` | `BIT` 列只接受 0/1；负向断言避开字符串字面量 `'TRUE'` |

> `TIMESTAMP`/`DOUBLE`/`JSON` 只匹配列定义位置（后面跟 `NULL`/`NOT NULL`/`DEFAULT`/`,`/`)`），避免误伤 `CURRENT_TIMESTAMP` 等函数名。`EXEC` 内的单引号按 T-SQL 规则翻倍转义。

---

## 5. 配置项

- **Spring 配置键：无。** 本模块不依赖 Spring，源码未提供任何 `application.properties` / `application.yml` 配置键或自动装配；数据源由调用方 `new SqlServerEngine().addDataSource(...)` 显式注册。
- **连接池**：无暴露的池配置项；两个同步引擎均固定 `setMaximumPoolSize(10)`。
- **jTDS URL 参数**：经 `DataSourceOptions.jtdsUrlParams()` 传入（`selectMethod`、`domain` 及其它自由键值对），不是配置文件键。

---

## 6. SPI 扩展点

本模块通过 `src/main/resources/META-INF/extensions/<接口全限定名>` 登记实现：

| SPI 接口 | 扩展键 | 注册的实现 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `sqlserver` | `SqlServerEngine` |
| `com.chua.common.support.lang.datasource.engine.Engine` | `sqlserver-legacy` | `SqlServerLegacyEngine` |
| `com.chua.datasource.support.engine.ReactorEngine` | `sqlserver` | `SqlServerReactorEngine` |
| `com.chua.datasource.support.engine.ReactorEngine` | `sqlserver-legacy` | `SqlServerLegacyReactorEngine` |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | `sqlserver` | `SqlServerScriptConverter` |

使用侧经 `ServiceProvider.of(Engine.class).getExtension("sqlserver")`（或 `"sqlserver-legacy"`）获取。扩展键必须与协议名一致，否则会与兜底默认实现同键互相遮蔽。

---

## 7. 构建与依赖

```text
utils-support-sqlserver-starter (com.chua, 4.0.0.51)
├── utils-support-datasource-starter    （provided，JdbcEngine/JdbcReactorEngine 契约）
├── HikariCP                            （连接池）
├── reactor-core                        （响应式引擎编译期需要；随核心 starter 传递给宿主应用）
├── mssql-jdbc 12.6.1.jre11             （runtime，官方 JDBC 驱动，支持 SQL Server 2008 R2+；版本可经 mssql-jdbc.version 属性覆盖）
├── r2dbc-mssql 1.0.7.RELEASE           （runtime，R2DBC 驱动，运行时经 SPI 被 JdbcReactorEngine 发现）
└── jtds 1.3.1                          （provided，2000/2005 老版本兼容驱动，默认不引入，按需启用）
```

- 构建：在 `utils-support-parent-starter` 下执行 `mvn -o -q compile -DskipTests`（JDK 25）。
- 本模块无 `src/test` 源码目录；`maven.test.skip=true` 为仓库级默认。

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 其它数据库的引擎/适配（MySQL、PostgreSQL、Oracle、SQLite、ClickHouse、DuckDB、Elasticsearch 等） | 同父模块下各自的 `utils-support-*-starter` |
| `Engine` / `ReactorEngine` / `ScriptConverter` 等 SPI 接口定义 | `utils-support-common-starter`（lang.datasource 契约）与 `utils-support-datasource-starter` |
| 元数据操作、用户/权限管理、DDL 生成、慢查询/会话/索引运维、向量存储 | 本模块未提供；MySQL 等模块有对应实现，SQL Server 侧按需另建 |
| 迁移执行器（`DataSourceFlyway` / `DefaultFlyway` 等编排） | common / datasource 侧；本模块只提供 sqlserver 方言的 `ScriptConverter` |
| 连接池的池化策略配置、Spring 数据源自动装配 | 宿主应用；本模块仅内置 HikariCP 默认 10 连接 |
| R2DBC 连接建立与响应式 SQL 执行器 | `utils-support-datasource-starter` 的 `JdbcReactorEngine`；本模块只提供驱动与 `sqlserver` 注册路径 |
