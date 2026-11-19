# utils-support-oracle-starter

> utils 体系中 **Oracle 数据库** 的方言实现模块：提供同步/响应式数据源引擎（ORM 查询入口）、CDC 变更数据分发器、Flyway 迁移脚本方言转换、用户管理与索引管理，全部以 SPI 扩展名 `oracle` 注册。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.oracle.support`｜类 `@since 4.0.0.42`

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
    <artifactId>utils-support-oracle-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

POM 描述：`Oracle数据库ORM+CDC+R2DBC驱动`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎 | `OracleEngine`（HikariCP 连接池，默认最大连接 10，支持隧道穿透）与 `OracleReactorEngine`（响应式入口） | 不实现 ORM/查询 DSL 本身——`query()` 等能力来自 `utils-support-datasource-starter` 的 `JdbcEngine` / `JdbcReactorEngine` |
| CDC | 进程内模拟 Oracle LogMiner 变更事件流（Reactor Sinks 发布订阅） | 不是真正的 LogMiner 挖掘器，也不是 Debezium 类采集连接器 |
| 迁移 | Flyway `ScriptConverter` 的 Oracle 方言实现（类型/函数/标识符改写） | 不执行迁移，也不管理 Flyway 本身 |
| 账号 | `CREATE/ALTER/DROP USER`、`GRANT/REVOKE`、`dba_users` 查询 | 不做权限体系、审计或密码策略管理 |
| 装配 | 纯 SPI 提供者，按扩展名 `oracle` 被发现 | 无 Spring Boot 自动装配、无配置项、不依赖 Spring |

### 1.3 规模

- Java 源文件 **11** 个，分布在 `engine` / `dispatcher` / `flyway` / `user` / `index` 五个子包。
- SPI 注册文件 **6** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`、`resource-config.json`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 建同步引擎 | `new OracleEngine()` → `addDataSource(name, host, port, serviceName, username, password)` | HikariCP，`maximumPoolSize=10`；JDBC URL 形如 `jdbc:oracle:thin:@//host:port/serviceName` |
| 按 SPI 取引擎 | `ServiceProvider.of(Engine.class).getExtension("oracle")` | 扩展名 `oracle`；响应式侧另有 `ReactorEngine` 同名实现 |
| 响应式查询 | `new OracleReactorEngine().addDataSource(...)` → `query(User.class).eq(...).list()` 返回 `Flux` | 当前为伪响应式（`boundedElastic` 调度阻塞 JDBC）；真非阻塞需走 R2DBC |
| 数据源高级配置 | `OracleEngine.addDataSource(DataSourceOptions)` | 支持隧道 `Tunnel`（自动改走 `127.0.0.1:隧道端口`） |
| CDC 事件流 | `OracleDispatcherProvider.publish / subscribe / unsubscribe / close` | 主题 `oracle.cdc.<schema>.<table>`，消息体为 JSON |
| 迁移脚本转 Oracle | `OracleScriptConverter.convert(statement, "oracle")` | 类型/函数映射；无法表达的冲突语句整条跳过 |
| 用户管理 | `OracleUserManager.createUser / alterUser / dropUser / listUsers` | 用户名白名单校验；删用户带 `CASCADE` |
| 索引管理 | `OracleIndexManager.listIndexes / createIndex / dropIndex` | 形态 `NORMAL / UNIQUE / BITMAP`；`DROP INDEX` 不带表名 |

### 2.2 类地图

| 包 | 类 | 说明 |
| --- | --- | --- |
| `engine` | `OracleEngine` | 同步 JDBC 引擎，`@Spi("oracle")`，继承 `JdbcEngine`；`@since 4.0.0.42` |
| `engine` | `OracleReactorEngine` | 响应式引擎，`@Spi("oracle")`，继承 `JdbcReactorEngine`，内部持有同步 `OracleEngine` |
| `dispatcher` | `OracleDispatcherProvider` | CDC 分发器，`@Spi("oracle")`，继承 `AbstractDispatcherProvider`；无参构造供 SPI 自动实例化，也可传 `DispatcherConfig` |
| `flyway` | `OracleScriptConverter` | 迁移脚本方言转换，`@Spi(value="oracle", order=100)`，继承 `DefaultScriptConverter`；常量 `PROTOCOL="oracle"` |
| `user` | `OracleUserManager` | 用户管理 SPI 实现，`type()="oracle"` |
| `user` | `OracleCreateUserStep` | 创建用户链式步骤 |
| `user` | `OracleAlterUserStep` | 修改用户链式步骤 |
| `user` | `OracleSqlNames` | 账号名白名单工具：`checkUserName(String)`，规则为字母开头、其后字母/数字/`$`/`_`/`#`，最长 64 |
| `index` | `OracleIndexManager` | 索引管理 SPI 实现，`type()="oracle"` |
| `index` | `OracleCreateIndexStep` | 创建索引链式步骤；内含枚举 `OracleIndexKind { NORMAL, UNIQUE, BITMAP }` |
| `index` | `OracleDropIndexStep` | 删除索引链式步骤 |

### 2.3 链式步骤 API

| 步骤 | 方法 | 说明 |
| --- | --- | --- |
| `OracleCreateUserStep` | `withPassword(String)` | 必填；口令白名单：不含控制字符与双引号，最长 128 |
| | `withHost(String)` | Oracle 无主机概念，忽略 |
| | `execute()` | `CREATE USER x IDENTIFIED BY "pwd"` + `GRANT CONNECT TO x`，逐条下发 |
| `OracleAlterUserStep` | `withPassword(String)` | `null` 表示不改密码 |
| | `withHost(String)` | 忽略 |
| | `withGrant(String privilege, String object)` | 追加 `GRANT ... [ON object] TO user`；`object` 为空表示系统权限/角色 |
| | `withRevoke(String privilege, String object)` | 追加 `REVOKE ... [ON object] FROM user` |
| | `execute()` | 按改密 → 授权 → 回收顺序逐条执行 |
| `OracleCreateIndexStep` | `onTable(String)` | 表名，可带一段 `schema.` 前缀 |
| | `field(String)` / `onColumn(String)` | 列名，支持逗号分隔多列，覆盖此前设置 |
| | `type(String)` / `using(String)` | 形态名白名单 `NORMAL / UNIQUE / BITMAP`，空白取默认；未知取值直接拒绝 |
| | `kind(OracleIndexKind)` / `bitmap()` | 枚举形态 / 显式索取位图索引 |
| | `execute()` | 生成 `CREATE [UNIQUE] [BITMAP] INDEX "idx" ON "table" ("col", ...)` 并执行 |
| `OracleDropIndexStep` | `onTable(String)` | Oracle 删索引不挂表名；仅为链式 API 一致而保留，只做白名单校验 |
| | `execute()` | `DROP INDEX "idx"`；`ORA-01418`（索引不存在）与 `ORA-02282`（被唯一/主键约束维护）有专门错误消息 |

错误与事务行为约定（各实现一致）：

- 标识符（用户名/表名/列名/索引名/权限名/授权目标）全部先过白名单，非法值抛 `IllegalArgumentException`；校验发生在链式设值时，非法名字在 `execute()` 之前就会被拒绝，不会下发任何语句；
- JDBC 失败统一抛 `IllegalStateException` 并链入原始 `SQLException`；
- DDL 不可回滚：多语句步骤（建用户、改用户）中途失败时，用 warn 日志记录已执行条数，异常消息带失败语句序号与身份，口令不回显；
- 权限名允许标识符词组（如 `CREATE SESSION`，逗号分隔多项），但拒绝 `TO/FROM/WITH/ADMIN/IDENTIFIED/GRANT/REVOKE/USER/ON/BY/CASCADE/IF/EXISTS/AND/OR/NOT` 等子句关键字，防止改写 `GRANT`/`REVOKE` 语义；授权目标仅允许裸标识符（Oracle 无 `库.*` 概念）。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-oracle-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- 本模块对它是 provided scope：JdbcEngine / UserManager / IndexManager 等 SPI 契约由宿主自行引入 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasource-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 同步引擎（ORM 查询）

```java
import com.chua.oracle.support.engine.OracleEngine;
import com.chua.common.support.lang.datasource.engine.DataSourceOptions;

OracleEngine engine = new OracleEngine();
// 便捷重载：name, host, port, serviceName, username, password
engine.addDataSource("default", "localhost", 1521, "orcl", "system", "password");
// 或完整选项（末位可携带隧道 Tunnel）：
// engine.addDataSource(new DataSourceOptions("default", "localhost", 1521, "orcl", "system", "password", null));

List<User> users = engine.query(User.class).list();   // 查询能力来自父类 JdbcEngine
```

> 数据源方言默认 `oracle`（可经 `setDialect` 覆盖）；运行期不支持替换已注册的数据源对象（`setSource` 抛 `UnsupportedOperationException`），需要换库时重新调用 `addDataSource` 注册新数据源。存在隧道时，连接自动改走 `127.0.0.1:隧道端口`。

### 3.3 响应式引擎

```java
import com.chua.oracle.support.engine.OracleReactorEngine;
import reactor.core.publisher.Flux;

OracleReactorEngine engine = new OracleReactorEngine();
engine.addDataSource("default", "localhost", 1521, "orcl", "system", "password");

Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
```

> 当前实现为伪响应式（在 `boundedElastic` 上执行阻塞 JDBC），与同步引擎共用同一个库。如需真正非阻塞路径，走父类 `JdbcReactorEngine` 的 JDBC URL 形式 `addDataSource`，由 runtime 依赖 `oracle-r2dbc` 驱动自动承接。

### 3.4 用户管理

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasource.support.user.UserManager;

UserManager users = ServiceProvider.of(UserManager.class).getExtension("oracle");

users.createUser("app_user")
     .withPassword("changeMe-1")
     .execute();                                  // CREATE USER + GRANT CONNECT

users.alterUser("app_user")
     .withGrant("CREATE SESSION", null)           // 系统权限：对象名留空
     .execute();

users.listUsers();                                // SELECT username FROM dba_users，需 DBA 权限

users.dropUser("app_user")
     .execute();                                  // DROP USER app_user CASCADE
```

### 3.5 索引管理

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasource.support.index.IndexManager;

IndexManager indexes = ServiceProvider.of(IndexManager.class).getExtension("oracle");

indexes.listIndexes("T_ORDER");                   // 来自 USER_INDEXES，原值与全大写都匹配；表不存在返回空列表

indexes.createIndex("IDX_ORDER_NO")
       .onTable("t_order")
       .onColumn("order_no")
       .execute();                                // 缺省普通 B 树索引

indexes.createIndex("IDX_ORDER_STATUS")
       .onTable("t_order")
       .onColumn("status")
       .bitmap()                                  // 位图索引必须显式索取，缺省永不生成
       .execute();

indexes.dropIndex("IDX_ORDER_NO")
       .execute();                                // DROP INDEX，无需表名
```

### 3.6 CDC 事件分发

```java
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.spi.ServiceProvider;

DispatcherProvider cdc = ServiceProvider.of(DispatcherProvider.class).getExtension("oracle");

cdc.subscribe(definition);                        // 订阅主题须为 oracle.cdc.<schema>.<table>
cdc.publish("oracle.cdc.app.t_order", eventJson); // 发布变更事件（JSON 字符串），无订阅者时静默丢弃
cdc.unsubscribe(definition);                      // 最后一个订阅者退订后回收该主题的 Sink
cdc.close();                                      // 完成所有 Sink 并清空注册状态
```

### 3.7 迁移脚本方言转换

```java
import com.chua.common.support.lang.datasource.flyway.ScriptConverter;
import com.chua.common.support.spi.ServiceProvider;

ScriptConverter converter = ServiceProvider.of(ScriptConverter.class).getExtension("oracle");
String oracleDdl = converter.convert(mysqlDdl, "oracle");
```

改写规则（`OracleScriptConverter` 自身 + 父类 `DefaultScriptConverter` 的基础映射）：

| 输入 | 输出 |
| --- | --- |
| `VARCHAR` | `VARCHAR2` |
| `TINYINT` / `MEDIUMINT` / `INT` / `INTEGER` / `BIGINT` | `NUMBER`（`SMALLINT` 保留，Oracle 中是 `NUMBER(5)` 同义词） |
| `BOOLEAN` / `BOOL` | `NUMBER(1)` |
| `YEAR` | `NUMBER(4)` |
| `DOUBLE` / `FLOAT` | `BINARY_DOUBLE` |
| `BLOB` / `VARBINARY(n)` / `BINARY(n)` | `BLOB` |
| `DATETIME` | `TIMESTAMP`（父类） |
| `NOW()` / `IFNULL(a,b)` | `SYSDATE` / `NVL(a,b)`（父类） |
| 反引号标识符 | 双引号（父类：Oracle 只认双引号） |
| 列级自增 | `GENERATED BY DEFAULT AS IDENTITY`（父类，Oracle 12c+ 原生支持） |
| `INSERT IGNORE INTO ...` | 整条跳过（返回 `null`） |
| `... ON DUPLICATE KEY UPDATE ...` | 整条跳过（返回 `null`；Oracle 等价写法 `MERGE INTO` 需要表结构元数据，纯文本转换拿不到） |

---

## 4. 配置项

**无。**

依据：本模块 `src/main/resources` 下只有 `META-INF/extensions/`（SPI 注册文件）与 `META-INF/native-image/`（GraalVM 配置），没有 `application.properties`、`@ConfigurationProperties` 等任何配置读取入口；连接池参数在 `OracleEngine` 中硬编码（HikariCP `maximumPoolSize=10`），JDBC URL 由 `addDataSource` 入参拼装。行为调整只能通过代码入参完成：`DataSourceOptions`（可携带 `Tunnel`）、链式步骤方法、`OracleCreateIndexStep.kind(OracleIndexKind)` 等。

---

## 5. SPI 扩展点

本模块是 **SPI 实现方**，自身不定义新的 SPI 接口；全部扩展点契约位于 `utils-support-common-starter` 与 `utils-support-datasource-starter`。

注册文件（`src/main/resources/META-INF/extensions/`，共 6 个）：

| SPI 接口 | 扩展名 | 实现类 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `oracle` | `com.chua.oracle.support.engine.OracleEngine` |
| `com.chua.datasource.support.engine.ReactorEngine` | `oracle` | `com.chua.oracle.support.engine.OracleReactorEngine` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `oracle` | `com.chua.oracle.support.dispatcher.OracleDispatcherProvider` |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | `oracle` | `com.chua.oracle.support.flyway.OracleScriptConverter` |
| `com.chua.datasource.support.user.UserManager` | `oracle` | `com.chua.oracle.support.user.OracleUserManager` |
| `com.chua.datasource.support.index.IndexManager` | `oracle` | `com.chua.oracle.support.index.OracleIndexManager` |

说明：

- `OracleEngine`、`OracleReactorEngine`、`OracleDispatcherProvider`、`OracleScriptConverter` 的类上同时标注了 `@Spi("oracle")`；
- `OracleUserManager`、`OracleIndexManager` 类上无 `@Spi` 注解，仅靠上面的注册文件被发现；
- `OracleUserManager`、`OracleIndexManager` 同时实现 `DataSourceAware`，由 SPI 工厂调用 `setDataSource(DataSource)` 完成数据源注入；
- `OracleScriptConverter` 的扩展键必须等于协议名 `oracle`，否则会与兜底默认实现 `DefaultScriptConverter` 同键互相遮蔽（源码 Javadoc 明确警示）。

---

## 6. 构建与依赖

构建链：`utils-support-parent-starter`（`4.0.0.51`）→ `utils-support-datasource-parent` → `utils-support-oracle-starter`；JDK 25。

```bash
mvn -o -q compile -DskipTests
```

依赖清单（`pom.xml` 与依赖树 `dtrees.txt`）：

| 依赖 | 版本 | Scope | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | 4.0.0.51 | compile | SPI、工具与 `Engine` / `ScriptConverter` / `DispatcherProvider` 契约 |
| `com.chua:utils-support-datasource-starter` | 4.0.0.51 | **provided** | `JdbcEngine` / `JdbcReactorEngine` / `UserManager` / `IndexManager` 等契约；随核心 starter 传递给宿主应用 |
| `com.zaxxer:HikariCP` | 7.0.2 | compile | 同步引擎连接池 |
| `io.projectreactor:reactor-core` | 3.8.6 | compile | 响应式引擎与 CDC Sink |
| `com.oracle.database.jdbc:ojdbc11` | 23.26.0.0.0 | compile | Oracle JDBC 驱动 |
| `com.oracle.database.r2dbc:oracle-r2dbc` | 1.3.0 | runtime | Oracle R2DBC 驱动，运行期经 SPI 被 `JdbcReactorEngine` 发现 |
| `org.projectlombok:lombok`、`org.jetbrains:annotations`、`com.google.code.findbugs:jsr305` | 1.18.46 / 24.1.0 / 3.0.2 | provided | 编译期辅助 |
| `org.junit.jupiter:junit-jupiter`、`org.testcontainers:testcontainers`、`org.testcontainers:oracle-xe` | 5.13.0-M3 / 1.21.3 / 1.21.3 | test | 测试期依赖 |

---

## 7. 边界：这些能力不在本模块

- **其它数据库方言**：MySQL、PostgreSQL、SQL Server、ClickHouse 等由同仓各自的 `*-starter` 提供，本模块只做 Oracle。
- **ORM / 查询 DSL 本体**：`query()`、条件构造、结果映射等来自 `utils-support-datasource-starter` 的 `JdbcEngine` / `JdbcReactorEngine`；本模块只提供 Oracle 的数据源装配与方言行为。
- **真正的 LogMiner 采集**：`OracleDispatcherProvider` 是进程内发布订阅模拟（Reactor Sinks），不连接 Oracle 日志挖掘接口。
- **Spring Boot 自动装配**：无 `AutoConfiguration`、无配置项；这是一个纯 SPI 库，按扩展名 `oracle` 装配。
- **连接池调优**：HikariCP 参数（`maximumPoolSize=10` 等）为硬编码，不暴露配置。
- **函数索引等表达式列**：`OracleCreateIndexStep` 的列白名单会拒绝 `UPPER(name)` 这类表达式，需要时请改用原生 DDL 执行入口。
- **主机（host）概念**：Oracle 用户没有 MySQL 的 `@'%'` 主机维度，`withHost()` 仅保留 API 兼容，不产生任何 SQL。
