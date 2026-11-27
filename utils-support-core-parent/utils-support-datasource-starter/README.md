# utils-support-datasource-starter

数据源核心引擎：`AbstractEngine`、`JdbcEngine`、`MemoryWhereParser`、Lambda 包装器。

> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source=25` / `maven.compiler.target=25`，全仓编译开启 `--enable-preview`）｜包名：`com.chua.datasource.support`

## 目录

- [是什么 / 不是什么](#是什么--不是什么)
- [核心 API 速查](#核心-api-速查)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界](#边界)

---

## 是什么 / 不是什么

| 是什么 | 不是什么 |
|---|---|
| 数据源核心引擎抽象：`AbstractEngine` 只要求子类实现 `executeNewQuery` 即可获得完整 ORM 能力，更新/删除默认基于内存数据存储执行，子类可覆写 `executeUpdate` / `executeDelete` 落库 | 不是 Spring Boot Starter：pom 无任何 Spring 依赖，不提供 `@Configuration` / Bean 装配 |
| 内置 `memory`（`InMemoryEngine`，SPI 键 `"memory"`）与 `file`（`FileEngine`，SPI 键 `"file"`，支持 csv/tsv/excel/xls/xlsx/json/json5/dbf）两个引擎实现 | 不含任何具体数据库驱动（MySQL / PostgreSQL / Oracle 等驱动由各数据库 starter 提供），pom 仅声明 `r2dbc-spi 0.9.1.RELEASE`（编译期 SPI）与 `jsqlparser 5.4`（provided） |
| Lambda 链式查询/更新/删除包装器：`EngineQueryWrapper` / `EngineUpdateWrapper` / `EngineDeleteWrapper`（`query/update/delete` 入口，`eq/ne/gt/ge/lt/le/like/in/...` 条件 + `list()/one()/page(pn, ps)/count()/update()/remove()` 终端方法） | 不含连接池管理、JTA/事务抽象与分布式事务（引擎内未提供事务 API，事务边界需调用方自行管理） |
| `JdbcEngine` 将 SELECT 列、JOIN、WHERE、GROUP BY、HAVING、ORDER BY、LIMIT/OFFSET 全量下推到数据库；`executeCount` 包装 `SELECT COUNT(*) FROM (...query...) t_count`，`supportsNativePaging` 判定物理分页 | 不提供具体数据库的用户/索引/权限实现：`UserManager` / `IndexManager` / `PermissionManager` / `ProcessManager` / `SlowQueryManager` 只是本模块定义的 SPI 接口，实现按方言协议由外部 starter 注册，取不到实现即该内核没有该能力 |
| `MemoryWhereParser` 将 WHERE 子句解析为 `Predicate`（支持 `=` `!=` `<>` `> >= < <=`、`BETWEEN ? AND ?`、`LIKE ?`、`IS [NOT] NULL`、`IN/NOT IN (...)`，AND/OR 括号分组且 AND 优先级高于 OR）；`MemorySqlParser` 提供内存 SQL 解析执行（SELECT 聚合 COUNT/MAX/MIN/SUM/AVG、INSERT/UPDATE/DELETE 计划） | 不负责 SQL 防注入之外的连接安全：`JdbcUrl` 只做连接串片段校验，`createTable` 的默认值仅放行数字字面量、受限关键字与引号配平的字符串字面量（见 `JdbcEngine.escapeDefaultValue`） |
| 方言体系：`AbstractDialect` 从 `META-INF/dialect-env/{protocol}.env` 资源加载厂商语法（引用符/分页/自增/引擎/DDL 片段/upsert 模板），SPI 注册 25 个方言实现（mysql/postgresql/oracle/sqlserver/sqlite/h2/mariadb/clickhouse/hive/derby/duckdb 各版本族，`Mysql57Dialect` 等旧版族共用新版 `.env`） | 不含数据库文档生成器本体：swagger/stripe/screw/aurora 仅提供文档模板（`DocumentTemplate`）与解析器（`DocumentParser`）SPI 注册 |
| 响应式引擎 SPI：`ReactorEngine`（`query/update/delete` 返回 `Flux`/`Mono`，静态工厂 `ReactorEngine.create(type)`），注册 `memory` / `file` / `jdbc` 三个实现；`JdbcReactorEngine` 支持单/多数据源与 R2DBC 真非阻塞、JDBC 降级联邦 | 不含 Flyway 迁移执行引擎：仅提供 `ScriptConverter` SPI（derby/hive 两个实现） |
| GraalVM 原生镜像支持：`META-INF/native-image/` 下提供 reflect/resource 配置 | 不含数据库中间件本身：`middleware` 包仅是连接探测/查询相关的接口与模型 |

---

## 核心 API 速查

| 能力 | 入口 | 说明 |
|---|---|---|
| 引擎创建 | `Engine.create("memory")` / `Engine.create("file")`（SPI）；响应式 `ReactorEngine.create("jdbc" \| "memory" \| "file")` | 通过 `ServiceProvider` 按扩展键实例化；`Dialect.getExtension("mysql")` 获取方言 |
| 链式查询 | `engine.query(T.class).eq(...).like(...).orderByDesc(...)` → `list()` / `one()` / `page(pn, ps)` / `count()` | `EngineQueryWrapper` 返回自身类型，条件全链式；分页在 JDBC 引擎走 COUNT + 分页 SQL 下推 |
| 游标分页 | `page.getNextCursor()` → `query(T.class).orderByAsc(...).cursor(values).page(1, ps)` | 实验特性，默认关闭；开启方式见[快速开始 §5](#5-大数据量分页keyset游标模式实验特性默认关闭) |
| 链式更新/删除 | `engine.update(T.class).set(T::getName, "新值").eq(T::getId, 1).update()`；`engine.delete(T.class).eq(...).remove()` | 返回受影响行数；内存/文件引擎直接作用于行引用 |
| 数据源管理 | `addDataSource(name, EngineDataSource)` / `store(name, List)` / `getDataSource(n)` / `getDataSource()` / `removeDataSource(name)` / `dataSourceNames()` / `setDefaultDataSourceName(name)` | 首个注册的数据源自动成为默认；`removeDataSource` 摘除并关闭，默认数据源被摘除时同时置空默认名 |
| 原生 SQL | 内存引擎：`querySql(sql, params)` / `querySql(sql, Class, params)`（`MemorySqlParser` 求值）/ `executeSql(sql, params)`；JDBC 引擎：`getExecutor()` → `JdbcSqlExecutor` 的 `query` / `queryPage` / `execute` / `batch` | `InMemoryEngine.supportsSql()` 恒为 true |
| DDL 与库管理 | `ddl()`（`DslManager` SPI，默认 `DefaultDslManager`）；`JdbcEngine.createDatabase` / `dropDatabase` / `createTable` / `databaseExists` / `listDatabases` | 建库语句优先由 `DdlProvider` SPI 生成（`DefaultDdlProvider` 仅占位、`supports()` 恒 false，无匹配实现时 `JdbcEngine` 用内置 SQL 兜底） |
| 元数据 | `meta()` / `supportsMeta()`；`JdbcEngine.getTriggers(schema)` / `getTrigger(...)` / `getProcedures(schema)` / `getProcedure(...)` | `meta()` 按方言协议 SPI 查找，回退 `JdbcMetaData`；基类默认 `DefaultMetaData` 各方法抛异常因而 `supportsMeta()` 为 false |
| 能力型 SPI 入口 | `user()`（`UserManager`）/ `index()`（`IndexManager`）/ `permission()`（`PermissionManager`）/ `interceptors()`（`EngineInterceptor`） | `JdbcEngine` 按当前方言协议解析，注入 JDBC `DataSource`；无实现时返回 null |
| 生命周期 | `close()`（幂等，`AtomicBoolean` 防重入，失败只记日志）/ `isClosed()` | 释放全部已注册数据源并清空内存数据 |

终端方法一览（`EngineQueryWrapper`）：`list()` / `one()` / `page(int pn, int ps)` / `count()`；`EngineUpdateWrapper`：`update()`；`EngineDeleteWrapper`：`remove()`。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasource-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 内存引擎 + Lambda 链式查询

```java
import com.chua.common.support.lang.datasource.engine.Engine;

public class Demo {
    // 实体：表名由 @TableName 指定，未标注时按实体类简单名驼峰转下划线
    @com.chua.datasource.support.annotation.TableName("city")
    public static class City {
        private String name;
        // getter / setter 省略
    }

    public static void main(String[] args) {
        // SPI 创建内存引擎并灌入数据
        Engine engine = Engine.create("memory", e -> e.store("city", List.of(
                new City("许都"), new City("隆中"), new City("成都"))));

        // Lambda 链式查询：eq + 终端方法
        List<City> match = engine.query(City.class)
                .eq(City::getName, "隆中")
                .list();                    // → [City("隆中")]

        long total = engine.query(City.class).count();   // → 3
        var page   = engine.query(City.class).page(1, 2);// → 第 1 页 2 条
    }
}
```

说明：`query(Class)` 返回 `EngineQueryWrapper`，条件方法（`eq/ne/gt/ge/lt/le/like/likeLeft/likeRight/in/notIn/isNull/isNotNull/between/orderByAsc/orderByDesc/groupBy/and/or`）全部返回自身以支持链式；`store(name, List)` 写入内存数据存储并登记为默认数据源。

### 3. 文件引擎（表格文件当表查）

```java
Engine engine = Engine.create("file");
((FileEngine) engine).load("D:/data/user.csv");        // 自动识别类型并加载到默认数据源
List<User> users = engine.query(User.class)
        .ge(User::getAge, 18)
        .list();
((FileEngine) engine).save("user");                    // 变更写回文件
```

`FileEngine.SUPPORTED_TYPES` 白名单：`csv / tsv / excel / xls / xlsx / json / json5 / dbf`。

### 4. JDBC 引擎（示意）

`JdbcEngine` 为抽象基类，具体注册引擎由各数据库 starter 提供；子类需要数据源里携带 `javax.sql.DataSource` 与 `Dialect`（`EngineDataSource`），之后 `query/update/delete` 的完整 SQL（SELECT 列、JOIN、WHERE、GROUP BY、HAVING、ORDER BY、分页）会下推给数据库执行，`page()` 走 `executeCount` + 物理分页。

### 5. 大数据量分页：keyset（游标）模式（实验特性，默认关闭）

深翻页时 `LIMIT ... OFFSET N` 的成本随 N 增长（数据库要跳过前 N 行）。开启游标分页后，前进位置改由 `WHERE (排序键 > 上一页末行键值)` 承载，`OFFSET` 恒为 0，成本与页码解耦。

```java
// 方式一：实例级显式开启（优先级最高）
((JdbcEngine) engine).setCursorPagingEnabled(true);
// 方式二：JVM 启动参数，对本进程所有 JDBC 引擎生效
//   -Dengine.jdbc.cursor-paging=true

// 顺序翻页（游标模式只能前进，页码恒传 1）
Page<Row> page = engine.query(Row.class).orderByAsc("id").page(1, 100);
while (page.hasNext()) {
    page = engine.query(Row.class).orderByAsc("id")
            .cursor(page.getNextCursor())     // 上一页末行的排序键值
            .page(1, 100);
}
```

| 项 | 口径 |
|---|---|
| 启用条件 | 开关开启 + 调用方给了非空游标 + 有确定 `ORDER BY` + 无 `GROUP BY`/`JOIN` + 方言支持标准 `LIMIT`（五条同时满足） |
| 不满足时 | **静默**回退 `LIMIT/OFFSET`，不抛异常、不改返回结构，且**不产出游标** |
| 能力探测 | `engine.supportsCursorPaging()` / `((JdbcEngine) engine).isCursorPagingEnabled()` |
| 默认值 | 关闭。历史调用方行为字节级不变 |
| 已知约束 | 只能顺序前进不能跳页；排序键必须唯一稳定（同值行会跨页重复/遗漏）；总数仍需 `COUNT` 单独取 |

> 为什么默认关闭：keyset 改变的是**分页语义**而非单纯提速，对既有调用方属不兼容变更，故做成实验开关。

---

## 配置项

本模块**零 Spring 配置**：引擎与方言完全由 SPI 自动装配，引入依赖即可使用。配置输入有两类：**JVM 系统属性**与**类路径资源**，没有 application 配置文件。

- **JVM 系统属性**（JDBC 引擎专属）：`-Dengine.jdbc.cursor-paging=true`（常量 `JdbcEngine.CURSOR_PAGING_PROPERTY`）开启 keyset 游标分页实验能力，缺省 `false`；实例级 `setCursorPagingEnabled(boolean)` 优先于该属性。
- **JVM 系统属性**（内存引擎容量保护，全部缺省关闭/不限制，关闭时行为零变化）：`-Dengine.memory.guard=true`（总开关，缺省 `false`）、`-Dengine.memory.max-rows=`（单表，缺省 50000）、`-Dengine.memory.max-total-rows=`（合计，缺省 200000）、`-Dengine.memory.max-tables=`（表数，缺省 64）、`-Dengine.memory.ttl-seconds=`（空闲回收，缺省 0 即不回收）、`-Dengine.memory.bypass-on-overflow=true`（超限软旁路到 `setFallbackEngine(...)`，缺省 `false` 即拒绝抛 `IllegalArgumentException`）。常量见 `InMemoryEngine.*_PROPERTY`，实例级 setter 优先于系统属性；阈值 `<=0` 表示该项不限制。
- **方言资源文件**：`META-INF/dialect-env/{protocol}.env`，例如 `MysqlDialect` → `mysql.env`、`PostgresqlDialect` → `postgresql.env`。模块内置 24 个文件：mysql、postgresql（env9/10）、oracle（11g/12c）、sqlserver（2008/2012）、sqlite（2）、h2（h2v1）、mariadb（103）、hive（2）、derby（1014）、duckdb（06）、clickhouse（20）。
- **资源加载规则**（`AbstractDialect.loadDefaultEnv`）：按类路径 `getResources` 合并同名资源——类路径靠前的 jar 逐键优先，后续 jar 只补缺失键，全部按 UTF-8 读取。
- **代码级覆盖**：`withProperties(Properties)` 传入的外部 properties 优先级高于内置 `.env`；`config(key, defaultValue)` 三级读取为「外部注入 → 内置 .env → 默认值」。
- **典型键**（见 `mysql.env`）：`protocol`、`driver`、`url`、`quote-open/close`、`auto-increment-keyword`、`engine-keyword`、`storage-engine`、`table-type`、`table-comment`、`column-comment`、`alter-column-string`、`supports-upsert`、`upsert-template`、`supports-inline-comment`、`supports-partition`、`current-timestamp-sql`。

---

## SPI 扩展点

注册文件位于 `src/main/resources/META-INF/extensions/`，共 9 个：

| 接口全名（注册文件名） | 本模块注册项 |
|---|---|
| `com.chua.common.support.lang.datasource.engine.Engine` | `file=FileEngine`、`memory=InMemoryEngine` |
| `com.chua.datasource.support.engine.ReactorEngine` | `memory=InMemoryReactorEngine`、`file=FileReactorEngine`、`jdbc=JdbcReactorEngine` |
| `com.chua.common.support.lang.datasource.dialect.Dialect` | 25 个方言实现，SPI 键即协议名（`mysql`/`mysql57`/`postgresql`/`postgresql10`/`postgresql9`/`oracle`/`oracle12c`/`oracle11g`/`sqlserver`/`sqlserver2012`/`sqlserver2008`/`sqlite`/`sqlite2`/`h2`/`h2v1`/`mariadb`/`mariadb103`/`clickhouse`/`clickhouse20`/`hive`/`hive2`/`derby`/`derby1014`/`duckdb`/`duckdb06`，其中 `SqlDialect` 与 `AbstractDialect` 为基类） |
| `com.chua.common.support.lang.datasource.engine.ddl.DslManager` | `default=DefaultDslManager`（通用 JDBC DDL 管理器，`@Spi` 别名 `DslManager.DEFAULT_ALIAS`，通过 `DatabaseMetaData` 读表结构再由注入的 `Dialect` 生成 DDL） |
| `com.chua.common.support.lang.datasource.engine.ddl.DdlProvider` | `ddl-provider=DefaultDdlProvider`（`@Spi(order = -1)` 兜底占位：`supports()` 恒 false，建库/删库/建表方法均抛 `UnsupportedOperationException`；实际 DDL 由 `JdbcEngine` 内置语法或外部方言 provider 生成） |
| `com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor` | `engine-interceptor=NoOpEngineInterceptor`（`@Spi(order = -1)` 空实现兜底，业务方注册更高 order 的拦截器在其前触发；`AbstractEngine.interceptors()` 结果按实例缓存） |
| `com.chua.common.support.lang.document.DocumentTemplate` | `default=DefaultDocumentTemplate`、`swagger=SwaggerDocumentTemplate`、`aurora=AuroraDocumentTemplate`、`stripe=StripeDocumentTemplate`、`screw=ScrewDocumentTemplate` |
| `com.chua.common.support.lang.document.DocumentParser` | `database=DatabaseDocumentParser`、`swagger=SwaggerOpenApiParser`、`openapi=SwaggerOpenApiParser` |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | `derby=DerbyScriptConverter`、`hive=HiveScriptConverter` |

**接口在本模块、实现由外部按方言协议注册**的能力点：`meta.MetaData`（`JdbcEngine.meta()` 按 `Dialect.protocol()` 查找，回退 `JdbcMetaData`）；`user.UserManager` / `index.IndexManager` / `permission.PermissionManager`（`JdbcEngine.user()/index()/permission()` 经 `resolveManager` 按协议解析，实现为 `DataSourceAware` 时注入 `DataSource`，无实现返回 null）；`process.ProcessManager` / `slowquery.SlowQueryManager`（本模块仅定义接口，引擎内未提供调用入口，由外部按协议注册后自行按 `ServiceProvider` 取用）。接口示例（`UserManager`）：`listUsers()`、`createUser(name).withPassword(pwd).withHost(host).execute()`、`alterUser(name).withGrant(priv, db).withRevoke(priv, db).execute()`、`dropUser(name)`。

---

## 构建与依赖

父模块：`com.chua:utils-support-core-parent:4.0.0.51`；自身坐标 `com.chua:utils-support-datasource-starter:4.0.0.51`。

```text
utils-support-datasource-starter
├── utils-support-common-starter              (编译依赖，${project.version})
├── io.projectreactor:reactor-core             (响应式引擎)
├── io.r2dbc:r2dbc-spi:0.9.1.RELEASE           (编译期仅 SPI，驱动由各数据库 starter 提供)
└── com.github.jsqlparser:jsqlparser:5.4       (provided)
```

仓库级属性：`project.build.sourceEncoding=UTF-8`、`java.version=25`、`maven.compiler.source/target=25`、`--enable-preview`（surefire fork JVM 以 `enable.preview.jvm.args` 保持一致）。

验收：`src/acceptance/` 提供可复跑的多阶段验收 harness（首个程序 `EngineAcceptance` 对 24 个 SPI 注册引擎做契约面断言），该目录不是 Maven 标准源码根，不参与构建；另外 `META-INF/native-image/` 提供 GraalVM reflect/resource 配置。

---

## 边界

- 不含具体数据库 JDBC/R2DBC 驱动、连接池、事务与分布式事务管理（pom 中仅有 SPI 级依赖，见上）。
- `UserManager` / `IndexManager` / `PermissionManager` / `ProcessManager` / `SlowQueryManager` / 方言专属 `MetaData` 的实现不在此模块，需依赖各数据库 starter 按协议注册；`JdbcEngine.user()/index()/permission()` 无实现时返回 null。
- 方言不支持标准 LIMIT 时（SQL Server / Oracle 族），分页走 `Dialect.processSql(sql, Pagination)` 的页码模型，`offset` 必须为 `limit` 的整数倍，否则 `wrapPagination` 显式抛出 `UnsupportedOperationException`。
- 引擎关闭后任何写操作抛 `IllegalStateException`（`ensureOpen`）；`getDataSource(name)` 对不存在/未设置返回 null。
- 内存引擎数据仅存于进程内 `ConcurrentHashMap`，进程退出即丢失；文件引擎仅白名单类型（`SUPPORTED_TYPES`）可加载。
- 内存引擎容量保护默认关闭；开启后超限只有**拒绝**（默认）或**软旁路到回落引擎**（`bypass-on-overflow=true` + `setFallbackEngine(...)`）两种口径，**不做静默截断**。旁路是单向的（`spill`/TTL 回收/`close` 推出），回内存必须 `clearBypass(table)` + 重新 `store`；`restore()` 是显式的 append-only 回写（按表分批 `INSERT`，不清内存副本、不动旁路标记），不是容量超限的自动补偿。
- 具体数据库连接串组装时的安全校验由 `url.JdbcUrl` 负责，限定主机名/IPv4/方括号 IPv6 与单段库名（允许 `_ $ -` 与一层 schema 限定），拒绝 `? & ; =` 等分隔符注入驱动属性。