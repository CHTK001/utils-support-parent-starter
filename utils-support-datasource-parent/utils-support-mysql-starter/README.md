# utils-support-mysql-starter

> MySQL 数据库适配 Starter：阻塞/响应式双引擎、用户与权限管理、元数据操作、DDL 生成、迁移脚本方言转换、慢查询/会话/索引运维、向量存储（MySQL 8.0+）。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.mysql.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 数据库引擎](#4-数据库引擎)
- [5. 用户与权限管理](#5-用户与权限管理)
- [6. 元数据操作](#6-元数据操作)
- [7. DDL 生成与迁移脚本转换](#7-ddl-生成与迁移脚本转换)
- [8. 运维能力：慢查询、会话、索引](#8-运维能力慢查询会话索引)
- [9. 向量存储](#9-向量存储)
- [10. 配置项](#10-配置项)
- [11. SPI 扩展点](#11-spi-扩展点)
- [12. 构建与依赖](#12-构建与依赖)
- [13. 边界：这些能力不在本模块](#13-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mysql-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块全称 `MySQL Starter`，位于 `utils-support-parent-starter/utils-support-datasource-parent/` 之下，父模块为 `utils-support-datasource-parent`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎 | MySQL 的 `Engine` / `ReactorEngine` SPI 实现（`@Spi("mysql")`），HikariCP 连接池 | 不是连接池本身，也不是 ORM / MyBatis / JPA |
| 运维 | 用户、权限、索引、会话、慢查询的 MySQL 管理入口（`*Manager` SPI 实现） | 不做监控告警、不存储指标 |
| 元数据 | `information_schema` 读取 + 建删改 DSL（表/视图/索引/外键/过程/触发器/用户/权限/全文索引） | 不缓存元数据，不跨库聚合 |
| DDL | 建库/建表语句生成、幂等 DDL 方言改写 | 不是迁移执行器（`Flyway` 编排在 common/datasource 侧） |
| 向量 | 基于 MySQL 原生 `VECTOR` 类型的 `VectorStorageProvider` | 不是向量检索引擎，不内置 embedding 模型 |
| 依赖 | 只依赖 common / datasource 契约 + HikariCP + mysql-connector-j + r2dbc-mysql + reactor-core | 不依赖 Spring，不依赖任何上层业务 starter |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 连 MySQL、执行查询 | `engine.MysqlEngine` | `addDataSource(...)` + 继承自 `JdbcEngine` 的查询入口，HikariCP 默认最大连接 10 |
| 响应式访问 | `engine.MysqlReactorEngine` | `boundedElastic` 调度阻塞 JDBC 调用的伪响应式实现，对外暴露 `Flux`/`Mono` |
| 建用户/改口令/授权回收 | `user.MysqlUserManager`、`user.MysqlPermissionManager` | 链式步骤；账号名/主机/口令/权限先过白名单再转义下发 |
| 表/列/索引/外键/过程/触发器元数据 | `meta.MysqlMetaData` | `table()` / `view()` / `index()` / `trigger()` / `procedure()` / `fk()` / `user()` / `permission()` / `search()` |
| 建库/建表 DDL 生成 | `ddl.MysqlDdlProvider` | 固定 `utf8mb4` + `utf8mb4_unicode_ci`、`ENGINE=InnoDB`，反引号转义 |
| 迁移脚本方言转换 | `flyway.MysqlScriptConverter` | 把 `ADD COLUMN IF NOT EXISTS` 等 MySQL 不支持的幂等 DDL 展开为 information_schema 守卫的动态 SQL |
| 慢查询/会话/索引运维 | `slowquery.MysqlSlowQueryManager`、`process.MysqlProcessManager`、`index.MysqlIndexManager` | 慢查询三级回退、`SHOW FULL PROCESSLIST`/`KILL`、`SHOW INDEX` |
| 向量存储 | `vector.MysqlVectorStorageProvider`、`vector.MysqlVectorStorage` | 原生 `VECTOR` 类型优先，不支持时退化 JSON 列 + JVM 精算 |

---

## 2. 核心 API 与速查表

### 2.1 引擎

| 类 | 关键方法 | 说明 |
| --- | --- | --- |
| `MysqlEngine` | `addDataSource(name, host, port, database, username, password)` | 六参便捷重载，内部转 `DataSourceOptions` |
| `MysqlEngine` | `addDataSource(DataSourceOptions)` | 支持隧道穿透（`options.tunnel()` 非空时经 `tunnel.open()` 改连 `127.0.0.1:隧道端口`） |
| `MysqlReactorEngine` | `addDataSource(name, host, port, database, username, password)` | 委托同步引擎注册，并经 `registerJdbcDataSource` 挂到响应式 JDBC 路径 |

### 2.2 用户与权限

| 类 | 关键方法 | 说明 |
| --- | --- | --- |
| `MysqlUserManager` | `listUsers()` | 读 `mysql.user`（含 `authentication_string`，无权限时跳过该列） |
| `MysqlUserManager` | `createUser(username)` → `withPassword` / `withHost` / `execute` | `CREATE USER IF NOT EXISTS 'user'@'host' IDENTIFIED BY '...'` |
| `MysqlUserManager` | `dropUser(username)` | `DROP USER IF EXISTS 'user'@'%'`，仅删 `%` 主机账号 |
| `MysqlUserManager` | `alterUser(username)` → `withPassword` / `withHost` / `withGrant` / `withRevoke` / `execute` | 按「改口令 → 授权 → 回收」顺序逐条执行 |
| `MysqlPermissionManager` | `listPermissions()` / `listPermissions(username)` | `information_schema.USER_PRIVILEGES` + 逐账号 `SHOW GRANTS` |
| `MysqlPermissionManager` | `grant(privileges)` → `toUser` / `onDatabase` / `execute` | 库名自动展开为 `库.*`，未指定目标时为 `*.*` |
| `MysqlPermissionManager` | `revoke(privileges)` → `fromUser` / `execute` | 仅支持全局回收（`ON *.*`） |

### 2.3 元数据

| 类 | 关键方法 | 说明 |
| --- | --- | --- |
| `MysqlMetaData` | `table()` / `table(name)`、`view()`、`index()`、`trigger()`、`procedure()`、`fk()`、`user()`、`permission()`、`search()` | 元数据族统一入口 |
| `MysqlMetaTable` | `list()` / `get()` / `create(name)` / `alter()` / `drop()` / `rename(newName)` | `list()` 只返回表级属性；`get()` 含列、主键、索引 |
| `MysqlMetaProcedure` | `list()` / `call(...)` 等 | 同时返回 `PROCEDURE` 与 `FUNCTION`；`call()` 只收集首个结果集 |
| `MysqlSearchEngineImpl` | `SETTING_TABLE` / `SETTING_PARSER`、`matchAgainstSql(...)` | 全文索引 = 表上的 `FULLTEXT` 索引，中文分词用 `parser=ngram` |

### 2.4 运维与向量

| 类 | 关键方法 | 说明 |
| --- | --- | --- |
| `MysqlSlowQueryManager` | `listSlowQueries(minAvgMs, limit)` | 三级数据源回退，见第 8 节 |
| `MysqlProcessManager` | `listProcesses()` / `killProcess(processId)` | `SHOW FULL PROCESSLIST` / `KILL <id>` |
| `MysqlIndexManager` | `listIndexes(table)` / `createIndex(name)` / `dropIndex(name)` | `SHOW INDEX FROM`；创建步骤支持 `onTable` / `onColumn` / `using` |
| `MysqlVectorStorage` | `add(id, vector)` / `search(query, topK)` / `size()` / `remove(id)` / `removeByIdPrefix(prefix)` / `isNativeVector()` | 继承 `AbstractVectorStorage` 的公开能力 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mysql-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 阻塞引擎：最短可运行示例

```java
import com.chua.mysql.support.engine.MysqlEngine;

MysqlEngine engine = new MysqlEngine();
engine.addDataSource("default", "localhost", 3306, "mydb", "root", "password");
List<User> users = engine.query(User.class).list();
```

### 3.3 响应式引擎

```java
import com.chua.mysql.support.engine.MysqlReactorEngine;

MysqlReactorEngine engine = new MysqlReactorEngine();
engine.addDataSource("default", "localhost", 3306, "mydb", "root", "password");
Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
```

> 响应式为伪响应式：内部经 `registerJdbcDataSource` 注册纯 JDBC 数据源，在 `boundedElastic` 上执行真实 JDBC 调用。r2dbc-mysql 驱动随本模块 runtime 引入，由 `JdbcReactorEngine` 经 SPI 发现。

### 3.4 用户管理与授权

```java
import com.chua.datasource.support.user.UserManager;
import com.chua.common.support.spi.ServiceProvider;

UserManager users = ServiceProvider.of(UserManager.class).getExtension("mysql");
users.setDataSource(dataSource);                       // 复用引擎的 HikariDataSource
users.createUser("app_user").withPassword("P@ssw0rd").withHost("%").execute();
users.alterUser("app_user")
        .withGrant("SELECT, INSERT", "mydb")           // 自动展开为 mydb.*
        .execute();
```

### 3.5 元数据读取

```java
MysqlMetaData meta = new MysqlMetaData(engine);
TableDef table = meta.table("t_order").get();          // 表属性 + 列 + 主键 + 索引
List<TableDef> tables = meta.table().list();           // 当前库全部基表（不含列/索引明细）
```

### 3.6 向量存储

```java
import com.chua.mysql.support.vector.MysqlVectorStorage;
import com.chua.common.support.vector.VectorCompareAlgorithm;

VectorStorage storage = new MysqlVectorStorage(dataSource, 128, VectorCompareAlgorithm.cosine());
storage.add("id1", new float[]{...});
List<Vector> results = storage.search(query, 10);
```

---

## 4. 数据库引擎

### 4.1 MysqlEngine（SPI 键 `mysql`）

- 继承 `com.chua.datasource.support.engine.JdbcEngine`，`@Spi("mysql")`，`4.0.0.42` 起。
- 连接池为 HikariCP，`setMaximumPoolSize(10)` 写死默认值。
- JDBC URL 固定模板：`jdbc:mysql://host:port/db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`。
- 方言为 `Dialect.require("mysql")`；`setSource` 运行期不支持（抛 `UnsupportedOperationException`，需重新 `addDataSource`）。
- 支持 SSH 等隧道穿透：`DataSourceOptions.tunnel()` 非空时先 `tunnel.open()`，连接落到 `127.0.0.1:隧道端口`，`tunnelPort()` 回填到 `EngineDataSource`。

### 4.2 MysqlReactorEngine（SPI 键 `mysql`）

- 继承 `com.chua.datasource.support.engine.JdbcReactorEngine`，`@Spi("mysql")`，`4.0.0.42` 起。
- 构造时内部持有 `new MysqlEngine()`；`addDataSource(...)` 先校验 host/port/database，再委托同步引擎注册，最后 `registerJdbcDataSource(name, "jdbc:mysql://host:port/db?useSSL=false&allowPublicKeyRetrieval=true", username, password)`。

---

## 5. 用户与权限管理

### 5.1 注入安全（本模块统一口径）

- 账号名白名单：`[A-Za-z0-9.$_ -]{1,64}`（`MysqlSqlNames.checkUserName`，集中一份供所有拼接位点使用）。
- 主机白名单：`[A-Za-z0-9._:%-]+`（允许 `%` 通配、主机名、IPv4、IPv6 网段所需的 `:`）。
- 口令白名单：除控制字符与单引号外任意字符，最长 128；失败时不回显口令内容。
- 权限白名单：`权限名(可选列清单)` 或 `ALL [PRIVILEGES]`；`*` 不是合法权限名，改写会被整体拒绝。
- 授权目标白名单：`*`、`库名`、`库.*`、`库.表`。
- 所有拼接进 DDL 的账号名先过白名单，再经 `quoteLiteral`（反斜杠加倍、单引号成对）转义为字面量；在默认模式与 `NO_BACKSLASH_ESCAPES` 下都安全。

### 5.2 MysqlUserManager

- `listUsers()`：`SELECT user, host, authentication_string FROM mysql.user`；无权限读取口令列时 debug 记录并跳过。
- `createUser(username)`：链式 `withPassword`（必填，缺失时执行失败）、`withHost`（默认 `%`）、`execute()`。
- `dropUser(username)`：仅删除 `'用户名'@'%'`；主机为 `%`。
- `alterUser(username)`：`withPassword` / `withHost` / `withGrant(privilege, database)` / `withRevoke(privilege, database)`；`execute()` 按「改口令 → 授权 → 回收」逐条下发。MySQL DDL 不可回滚，中途失败时 warn 已执行条数，异常消息带失败语句序号与身份（不含口令）。

### 5.3 MysqlPermissionManager

- `listPermissions()`：全局权限取 `information_schema.USER_PRIVILEGES`；再对 `mysql.user` 每个账号执行 `SHOW GRANTS FOR 'user'@'host'`，解析出权限列表与库级范围。
- `grant(privileges)`：`GrantStep.toUser(...)` → `onDatabase(...)`（仅库名自动展开 `库.*`）→ `execute()` 生成 `GRANT ... ON ... TO 'user'@'%'`。
- `revoke(privileges)`：`RevokeStep.fromUser(...)` → `execute()` 生成 `REVOKE ... ON *.* FROM 'user'@'%'`。
- 已知限制（显式告警而非报错）：表级 `onTable`、列级 `onColumn`、`withGrantOption`、按库/表/列回收均未实现，入参被忽略并 warn 实际生效范围。

---

## 6. 元数据操作

`MysqlMetaData`（`@Spi("mysql")`，继承 `JdbcMetaData`）是元数据族门面，同时提供包内公共 JDBC 设施：连接获取、`PreparedStatement` 绑定（`null` 以 `Types.VARCHAR` 下发）、反引号引用（`SqlName.escape(name, '`', ...)`）、异常显式抛出（带上下文与 SQL，不伪装成「没有元数据」）。

**库名收敛规则**：所有 `information_schema` 查询的 schema 都以绑定参数下发，`COALESCE(?, DATABASE())` —— 调用方用 `getSchema()` / `getCatalog()` 指定库（MySQL 使用者常把库名写在 catalog 上），未指定时只读当前会话默认库。

| 入口 | 实现 | 要点 |
| --- | --- | --- |
| `table()` / `table(name)` | `MysqlMetaTable` | `list()` 表级属性（引擎/字符集/排序规则/注释/行数/创建更新时间）；`get()` 含列、主键、索引；`TABLE_ROWS` 为 InnoDB 估算值；`type` 透传 `BASE TABLE`/`VIEW`/`SYSTEM VIEW`；`create`/`alter` DSL 构建器；`drop()`=`DROP TABLE IF EXISTS`；`rename()`=`RENAME TABLE` |
| `view()` / `view(name)` | `MysqlMetaView` | `INFORMATION_SCHEMA.VIEWS` 联 `TABLES`；MySQL 无视图注释子句，`comment(String)` 显式抛 `UnsupportedOperationException` |
| `index()` / `index(name)` | `MysqlMetaIndex` | 明细来自 `INFORMATION_SCHEMA.STATISTICS`，多列索引按 `SEQ_IN_INDEX` 归并；读取路径 `invisible` 恒为 `false`（不伪造），写入路径支持 `INVISIBLE`（MySQL 8.0+） |
| `fk()` / `fk(name)` | `MysqlMetaForeignKey` | `KEY_COLUMN_USAGE` 联 `REFERENTIAL_CONSTRAINTS`，只保留真外键；联合外键按列展开成多条定义 |
| `procedure()` / `procedure(name)` | `MysqlMetaProcedure` | `ROUTINES` + `PARAMETERS` 批量读取；`orReplace()` 实现为先 `DROP IF EXISTS` 再 `CREATE`；非 SQL 语言显式拒绝；`call()` 只收集首个结果集，OUT/INOUT 不回填 |
| `trigger()` / `trigger(name)` | `MysqlMetaTrigger` | `INFORMATION_SCHEMA.TRIGGERS`；恒为 `FOR EACH ROW`；`enable`/`disable`/`comment` 显式抛 `UnsupportedOperationException`；`status` 恒为 `null` |
| `user()` | `MysqlMetaUser` | 账号/主机白名单 + 口令转义，异常与日志不回显口令 |
| `permission()` | `MysqlMetaPermission` | 授权/回收白名单校验 + 账号字面量转义 |
| `search()` | `MysqlMetaSearch` → `MysqlSearchEngineImpl` | 索引即表上 `FULLTEXT` 索引；`settings` 必填 `table`，可选 `parser`（如 `ngram`）；检索生成 `MATCH ... AGAINST` |

---

## 7. DDL 生成与迁移脚本转换

### 7.1 MysqlDdlProvider（`DdlProvider` SPI）

- `@Spi(DdlProvider.SPI_NAME)`，`4.0.0.43` 起；`supports()` 覆盖 `mysql` / `mariadb` / `mysql57`。
- `createDatabase(dbName)`：`CREATE DATABASE IF NOT EXISTS ... DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`。
- `dropDatabase(dbName)`：`DROP DATABASE IF EXISTS ...`。
- `createTable(tableName, columns)`：`CREATE TABLE IF NOT EXISTS ... ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`，支持 `AUTO_INCREMENT`、列 `COMMENT`（单引号加倍）、联合主键；列类型缺省 `VARCHAR(255)`。
- 标识符反引号包裹，内部反引号双写转义。

### 7.2 MysqlScriptConverter（`ScriptConverter` SPI 键 `mysql`）

- `@Spi(value = "mysql", order = 100)`，继承 `DefaultScriptConverter`，`4.0.0.42` 起；`supports()` 覆盖 MySQL 系协议 `mysql` / `mariadb` / `tidb` / `oceanbase`。
- 覆写 `convertAll(statements, protocol)`（单条语句可展开为多条，超出 `convert` 的一进一出契约）。
- 展开规则（守卫式动态 SQL，保持幂等且真实可执行，而非跳过）：
  - `ALTER TABLE t ADD COLUMN IF NOT EXISTS c ...` → `SET @ddl_col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS ...)`、`SET @ddl_col_sql := IF(@ddl_col_exists = 0, 'ALTER TABLE ...', 'DO 0')`、`PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE`；
  - `CREATE [UNIQUE] INDEX IF NOT EXISTS idx ON tbl (cols)`、`DROP INDEX IF EXISTS idx ON tbl` 同样按 `information_schema.STATISTICS` 守卫展开；
  - 表名/列名/索引名先经 `SqlName.checkSimple` 白名单校验，再 `SqlName.quoteMysql` 引用；
  - 其余语句原样走 `super.convert`（MySQL 是本项目脚本的源方言）。

---

## 8. 运维能力：慢查询、会话、索引

### 8.1 慢查询 MysqlSlowQueryManager（`4.0.0.44` 起）

`listSlowQueries(minAvgMs, limit)`：`limit <= 0` 时取默认 100，上限 500；按耗时倒序。三个数据来源按优先级回退（仅「来源不可用」才降级，查询成功但为空不算降级）：

1. `performance_schema.events_statements_history_long`：逐条慢事件，联 `threads` 取客户端主机；8.0 与 8.4 的 schema 列名差异（`SCHEMA_NAME` / `CURRENT_SCHEMA`）先探测再拼 SQL；消费者 `events_statements_history_long` 未开启时主动抛错触发降级。
2. `mysql.slow_log`：`log_output` 含 `TABLE` 时可用；从 `user_host` 的最后一对方括号解析客户端地址。
3. `performance_schema.events_statements_summary_by_digest`：按摘要聚合，有次数/平均/最大耗时，无主机维度（`host` 为 `null`），兜底。

`performance_schema` 的 `*_TIMER_WAIT` 单位是皮秒，对外换算毫秒除以 `1e9`；系统库（`performance_schema`/`information_schema`/`mysql`/`sys`）不参与排名；摘要文本压成单行并截断到 200 字符。

### 8.2 会话 MysqlProcessManager（`4.0.0.44` 起）

- `listProcesses()`：`SHOW FULL PROCESSLIST`；`Time` 为 `NULL` 时记 `-1`（表示「不适用」）。
- `killProcess(processId)`：先按 `long` 校验再拼 `KILL <id>`（`KILL` 不支持占位符）。
- 权限说明：`SHOW FULL PROCESSLIST` 需要 `PROCESS` 权限，否则只返回自己的会话；`KILL` 需要 `CONNECTION_ADMIN`（或 `SUPER`）。

### 8.3 索引 MysqlIndexManager

- `listIndexes(table)`：`SHOW INDEX FROM table`，返回 `Key_name` 列表。
- `createIndex(indexName)`：`MysqlCreateIndexStep`，链式 `onTable` / `onColumn`（`field` 等价）/ `type`（`using` 等价）→ `execute()` 生成 `CREATE INDEX idx ON tbl USING <algorithm> (col)`。
- `dropIndex(indexName)`：`MysqlDropIndexStep`，`onTable` → `execute()` 生成 `DROP INDEX idx ON tbl`。

---

## 9. 向量存储

### 9.1 MysqlVectorStorageProvider（`VectorStorageProvider` SPI 键 `mysql`，order 50）

- `descriptor()`：`requiresDataSource=true` —— 向量直接存入业务库，连接信息由宿主注入 `DataSource`，表单只暴露表名与列名，不暴露任何连接信息；需 MySQL 8.0.31+。
- 配置键（`VectorStorageField.text`）：

| 键 | 说明 | 默认值 |
| --- | --- | --- |
| `tableName` | 向量表名 | `vector_store` |
| `idColumn` | 标识列名（与向量一一对应的唯一标识列） | `id` |
| `vectorColumn` | 向量列名 | `vec` |

- `toProperties(config)` → `MysqlVectorStorageProperties`（record，三组件非空）。
- `create(dimension, algorithm, properties, dataSource)` → `MysqlVectorStorage`；不带数据源的重载要求 properties 为 `MysqlVectorStorageProps`（包装宿主 `DataSource` 与属性对象的 record）。

### 9.2 MysqlVectorStorage

- 继承 `AbstractVectorStorage`；能力探测以实际执行 `STRING_TO_VECTOR` / `VECTOR_TO_STRING` 为准：
  - **原生路径**：向量列 `VECTOR(dim)`，写入 `STRING_TO_VECTOR(?)`，检索 `DISTANCE(vec, STRING_TO_VECTOR(?), 'cosine'|'euclidean')` 交由数据库排序；
  - **退化路径**：不支持原生类型（含 MySQL 8.0 与 HeatWave 之外的老版本）时向量列退化为 `JSON`，全量读取后在 JVM 内按 `VectorCompareAlgorithm` 精算排序；两条路径都落库，不会退化成内存存储。
- 点积与自定义算法无法翻译为 MySQL 内置距离函数，一律走 JVM 精算路径，因此第三方 `VectorCompareAlgorithm` 可直接使用。
- 公开能力：`add(id, vector)`（`INSERT IGNORE`）、`search(query, topK)`、`size()`、`remove(id)`、`removeByIdPrefix(prefix)`、`isNativeVector()`。

---

## 10. 配置项

- **Spring 配置键：无。** 本模块不依赖 Spring，源码未提供任何 `application.properties` / `application.yml` 配置键或自动装配；数据源由调用方 `new MysqlEngine().addDataSource(...)` 显式注册，或由宿主向 `*Manager` 注入 `DataSource`（`DataSourceAware.setDataSource`）。
- **向量存储配置键**：见第 9.1 节（`tableName` / `idColumn` / `vectorColumn`），属 `VectorStorageProvider` 的表单配置，不是 properties 文件键。
- **慢查询参数**：无配置文件项；`listSlowQueries(minAvgMs, limit)` 的 `limit <= 0` 取默认 100，硬上限 500（源码常量）。
- **连接池**：无暴露的池配置项；`MysqlEngine` 固定 `setMaximumPoolSize(10)`。

---

## 11. SPI 扩展点

本模块通过 `src/main/resources/META-INF/extensions/<接口全限定名>` 登记实现，扩展键统一为 `mysql`：

| SPI 接口 | 注册的实现 |
| --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `MysqlEngine` |
| `com.chua.datasource.support.engine.ReactorEngine` | `MysqlReactorEngine` |
| `com.chua.common.support.lang.datasource.meta.MetaData` | `MysqlMetaData` |
| `com.chua.common.support.lang.datasource.meta.SearchEngine` | `MysqlSearchEngineImpl` |
| `com.chua.common.support.lang.datasource.engine.ddl.DdlProvider` | `MysqlDdlProvider` |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | `MysqlScriptConverter` |
| `com.chua.common.support.vector.VectorStorageProvider` | `MysqlVectorStorageProvider` |
| `com.chua.datasource.support.user.UserManager` | `MysqlUserManager` |
| `com.chua.datasource.support.permission.PermissionManager` | `MysqlPermissionManager` |
| `com.chua.datasource.support.index.IndexManager` | `MysqlIndexManager` |
| `com.chua.datasource.support.process.ProcessManager` | `MysqlProcessManager` |
| `com.chua.datasource.support.slowquery.SlowQueryManager` | `MysqlSlowQueryManager` |

扩展方式与其它 starter 一致：实现对应 SPI 接口，标注 `@Spi("mysql")`（或 `@Extension("mysql")`），并在 `META-INF/extensions/<接口全限定名>` 文件中写 `mysql=<实现类全名>`。使用侧经 `ServiceProvider.of(接口.class).getExtension("mysql")` 获取。

另：`src/main/resources/META-INF/native-image/com.chua/utils-support-mysql-starter/` 内置 GraalVM `native-image` 的 `reflect-config.json` 与 `resource-config.json`。

---

## 12. 构建与依赖

```text
utils-support-mysql-starter (com.chua, 4.0.0.51)
├── utils-support-common-starter        （compile，${project.version}，SPI/工具/向量契约）
├── utils-support-datasource-starter    （provided，JdbcEngine/JdbcMetaData/*Manager 契约）
├── reactor-core                        （响应式引擎编译期需要；随核心 starter 传递给宿主应用）
├── HikariCP                            （连接池）
├── mysql-connector-j                   （runtime，JDBC 驱动）
└── r2dbc-mysql 1.4.3                   （runtime，R2DBC 驱动，运行时经 SPI 被 JdbcReactorEngine 发现）
```

- 构建：在 `utils-support-parent-starter` 下执行 `mvn -o -q compile -DskipTests`（JDK 25）。
- 本模块无 `src/test` 源码目录；`maven.test.skip=true` 为仓库级默认。

---

## 13. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 其它数据库的引擎/适配（PostgreSQL、SQLite、Oracle、SQL Server、ClickHouse、DuckDB、Elasticsearch 等） | 同父模块下各自的 `utils-support-*-starter` |
| `Engine` / `MetaData` / `*Manager` / `ScriptConverter` / `DdlProvider` 等 SPI 接口定义 | `utils-support-common-starter`（lang.datasource / vector 契约）与 `utils-support-datasource-starter` |
| 迁移执行器（`DataSourceFlyway` / `DefaultFlyway` 等编排） | common / datasource 侧；本模块只提供 MySQL 方言的 `ScriptConverter` |
| 连接池的池化策略配置、Spring 数据源自动装配 | 宿主应用；本模块仅内置 HikariCP 默认 10 连接 |
| 向量模型 / Embedding / 相似度算法实现 | `utils-support-common-starter` 的 `vector` 契约及各向量 starter（milvus、jvector、zvec 等） |
| R2DBC 连接建立与响应式 SQL 执行器 | `utils-support-datasource-starter` 的 `JdbcReactorEngine`；本模块只提供驱动与 `mysql` 注册路径 |
