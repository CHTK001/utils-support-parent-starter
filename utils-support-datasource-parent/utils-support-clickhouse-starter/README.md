# utils-support-clickhouse-starter

> utils 数据源体系的 **ClickHouse 适配模块**：把 `Engine` / `ReactorEngine` / `ScriptConverter` 三个 SPI 扩展点接到 ClickHouse，提供 JDBC（HikariCP 连接池）与 R2DBC 两条访问路径，以及 MySQL 风格迁移脚本到 ClickHouse 语法的转换。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.clickhouse.support`（子包 `engine`、`flyway`）

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
    <artifactId>utils-support-clickhouse-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | ClickHouse 的 JDBC / R2DBC 驱动适配：同步侧 `ClickHouseEngine`、响应式侧 `ClickHouseReactorEngine`、迁移脚本侧 `ClickHouseScriptConverter` | 不是 ClickHouse 服务端，也不是 ClickHouse 管理控制台 |
| 形态 | 纯 SPI 扩展模块：3 个实现类 + 3 个 `META-INF/extensions` 登记文件，统一按扩展键 `clickhouse` 装配 | 不是 Spring Boot 自动装配 starter：无 `spring.factories`、无任何配置属性键 |
| 依赖 | 只携带 HikariCP、reactor-core 与 ClickHouse 官方 `clickhouse-jdbc` / `clickhouse-r2dbc` 驱动 | 不定义 ClickHouse 方言（`Dialect` 由 `utils-support-datasource-starter` 提供，本模块经 `Dialect.require("clickhouse")` 消费） |

### 1.3 模块组成（全部源码，共 3 个类）

| 角色 | 全限定名 | 说明 |
| --- | --- | --- |
| 同步引擎 | `com.chua.clickhouse.support.engine.ClickHouseEngine` | `@Spi("clickhouse")`，继承 `JdbcEngine`；HikariCP 连接池；`@since 4.0.0.42` |
| 响应式引擎 | `com.chua.clickhouse.support.engine.ClickHouseReactorEngine` | `@Spi("clickhouse")`，继承 `JdbcReactorEngine`；`@since 4.0.0.42` |
| 脚本转换器 | `com.chua.clickhouse.support.flyway.ClickHouseScriptConverter` | `@Spi(value = "clickhouse", order = 100)`，继承 `DefaultScriptConverter`；`@since 4.0.0.42` |

---

## 2. 核心 API 与速查表

### 2.1 ClickHouseEngine（同步）

```java
// 五要素快捷注册：内部转成 DataSourceOptions 后走完整注册流程
Engine addDataSource(String name, String host, int port, String database,
                     String username, String password)

// 完整选项注册：支持隧道穿透（options.tunnel() 非 null 时改走 127.0.0.1:隧道端口）
Engine addDataSource(DataSourceOptions options)
```

行为要点（与源码一致）：

- 注册前经 `JdbcUrl.checkHost / checkPort / checkDatabase` 校验；
- 生成 JDBC URL：`jdbc:clickhouse://host:port/database`（ClickHouse HTTP 端口，通常为 8123，见源码注释）；
- 连接池为 `HikariDataSource`，`setMaximumPoolSize(10)`（固定值，未开放配置）；
- 方言固定取 `Dialect.require("clickhouse")`；
- 数据源封装的 `setSource(...)` 在运行期抛 `UnsupportedOperationException`（更换数据源需重新 `addDataSource`）。

### 2.2 ClickHouseReactorEngine（响应式）

```java
public ClickHouseReactorEngine()                       // 内部持有同步 ClickHouseEngine
ClickHouseReactorEngine addDataSource(String name, String host, int port,
                     String database, String username, String password)
```

- 当前为**伪响应式**实现：`boundedElastic` 调度上执行阻塞 JDBC 调用，与同步引擎共用同一套数据源配置；
- `addDataSource(...)` 先委托同步引擎注册，再经 `registerJdbcDataSource` 走 `JdbcReactorEngine.addDataSource(name, jdbcUrl, user, pwd)`：`jdbc:` URL 自动转 `r2dbc:`，类路径存在 `clickhouse-r2dbc` 驱动时建立 R2DBC 连接工厂，失败则降级为纯 JDBC 路径；
- 需要真正非阻塞路径时，直接调用父类 `addDataSource(name, "jdbc:clickhouse://...", user, pwd)`，由驱动自动转换为 R2DBC 连接（见源码类注释）。

### 2.3 ClickHouseScriptConverter（迁移脚本方言转换）

`supports("clickhouse")` 精确匹配协议名；`convert(statement, protocol)` 的处理规则：

| 输入（MySQL 风格） | 输出（ClickHouse） |
| --- | --- |
| `ALTER TABLE t ADD COLUMN [IF NOT EXISTS] c ...` | 返回 `null`，整条跳过（ClickHouse 不接受 `IF NOT EXISTS`，且无 `information_schema` 可做元数据守卫） |
| `AUTO_INCREMENT`（含表尾 `AUTO_INCREMENT=100`）、`ON UPDATE CURRENT_TIMESTAMP` | 剥离 |
| `TINYINT` / `SMALLINT` / `MEDIUMINT` / `INT`(`INTEGER`) / `BIGINT`（先剥离显示宽度如 `INT(11)`） | `Int8` / `Int16` / `Int32` / `Int32` / `Int64` |
| `DATETIME` / `TIMESTAMP`（含精度括号） | `DateTime` |
| `TINYTEXT` / `TEXT` / `MEDIUMTEXT` / `LONGTEXT` / `*BLOB` / `BINARY` / `VARBINARY` / `JSON` | `String` |
| `DOUBLE` / `FLOAT` / `REAL`（含精度括号） | `Float64` |
| `NOW()` / `IFNULL(a,b)` | `now()` / `ifNull(a,b)` |
| 反引号标识符、`INSERT IGNORE`、`CONCAT`、`CURRENT_TIMESTAMP` | 原样保留（ClickHouse 兼容） |

### 2.4 常用能力速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建同步引擎 | `new ClickHouseEngine()` 或 `Engine.create("clickhouse")` | SPI 扩展键 `clickhouse` |
| 注册数据源（快捷） | `engine.addDataSource("default", "localhost", 8123, "db", "user", "pwd")` | 自动拼 `jdbc:clickhouse://`，HikariCP 最大 10 连接 |
| 注册数据源（含隧道） | `engine.addDataSource(DataSourceOptions)` | `tunnel` 非 null 时改走 `127.0.0.1:隧道端口` |
| Lambda 查询 | `engine.query(User.class).eq(...).list()` | 继承自 `JdbcEngine` / `Engine` |
| 原生 SQL | `engine.querySql(sql, params)` / `engine.execute(sql, params)` | 另有 `queryOne` / `queryValue` / `count` / `exists` / `queryPage` / `batchSql` |
| 创建响应式引擎 | `new ClickHouseReactorEngine()` | SPI 扩展键 `clickhouse`（`ReactorEngine` 扩展点） |
| 响应式查询 / 写入 | `engine.query(sql, params)` → `Flux<Map<String,Object>>`；`engine.execute(sql, params)` → `Mono<Integer>`；`engine.batch(sql, List<Object[]>)` → `Flux<Integer>` | 继承自 `JdbcReactorEngine` |
| 迁移脚本转 ClickHouse 方言 | `ClickHouseScriptConverter` | `ScriptConverter` SPI 键 `clickhouse`，order=100 |

---

## 3. 快速开始

### 3.1 同步访问（JDBC + HikariCP）

```java
import com.chua.clickhouse.support.engine.ClickHouseEngine;
import com.chua.common.support.lang.datasource.engine.DataSourceOptions;

ClickHouseEngine engine = new ClickHouseEngine();
engine.addDataSource(new DataSourceOptions("default", "localhost", 8123, "default", "default", "", null));

List<User> users = engine.query(User.class).list();                  // Lambda 查询
List<Map<String, Object>> rows = engine.querySql("SELECT * FROM user WHERE age > ?", 18);

engine.close();
```

也可走 SPI 创建：`Engine engine = Engine.create("clickhouse");`（扩展键未注册时抛可定位异常）。

### 3.2 响应式访问（R2DBC / JDBC 兜底）

```java
ClickHouseReactorEngine engine = new ClickHouseReactorEngine();
engine.addDataSource("default", "localhost", 8123, "default", "default", "");
Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
```

### 3.3 迁移脚本方言转换

`ClickHouseScriptConverter` 由迁移机制按 `ScriptConverter` SPI（键 `clickhouse`）自动选用；也可手动调用：

```java
ScriptConverter converter = ServiceProvider.of(ScriptConverter.class).getExtension("clickhouse");
String clickhouseDdl = converter.convert(mysqlDdl, "clickhouse");   // 返回 null 表示该语句应跳过
```

> 前提（源码注释明确）：`CREATE TABLE` 需按「每列一行」的对齐格式书写，行内 `KEY` / `UNIQUE KEY` 的整行剥离依赖该格式；ClickHouse 的 `ENGINE` 子句是必须的，本转换器只剥离 MySQL 尾属性、不负责合成 `ENGINE = MergeTree`。

---

## 4. 配置项

**无。**

依据：本模块 `src/main/resources` 下仅有 3 个 SPI 扩展登记文件，没有 `spring.factories` / `AutoConfiguration.imports` / `application.yml`，源码中也没有任何 `@ConfigurationProperties`。连接参数（host / port / database / username / password / tunnel）全部通过 `addDataSource(...)` 编程式传入；HikariCP 连接池大小在源码中固定为 `10`，未开放为配置项。ClickHouse 方言参数（驱动类 `com.clickhouse.jdbc.ClickHouseDriver` 等）由 `utils-support-datasource-starter` 的 `META-INF/dialect-env/clickhouse.env` 提供，不属于本模块。

---

## 5. SPI 扩展点

### 5.1 本模块注册的扩展（被框架消费）

| 扩展点接口 | 登记文件（`META-INF/extensions/`） | 扩展键 → 实现 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | 同名文件 | `clickhouse` → `com.chua.clickhouse.support.engine.ClickHouseEngine` |
| `com.chua.datasource.support.engine.ReactorEngine` | 同名文件 | `clickhouse` → `com.chua.clickhouse.support.engine.ClickHouseReactorEngine` |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | 同名文件 | `clickhouse` → `com.chua.clickhouse.support.flyway.ClickHouseScriptConverter` |

### 5.2 本模块消费的外部 SPI

| SPI | 提供方 | 在本模块中的作用 |
| --- | --- | --- |
| `Dialect`（键 `clickhouse` / `clickhouse20`） | `utils-support-datasource-starter` | `ClickHouseEngine` 经 `Dialect.require("clickhouse")` 取方言；`ClickHouseDialect` 对应 ClickHouse 22.x+，`ClickHouse20Dialect` 对应 20.x（旧版 yandex 驱动） |
| `Flyway` / `MetaData` / `DdlProvider` / `UserManager` / `IndexManager` / `PermissionManager` / `EngineInterceptor` | 各实现模块 | 继承自 `JdbcEngine` 的能力入口，按当前方言协议 `clickhouse` 查找；本模块未为 clickhouse 注册这些扩展，未命中时走引擎默认回退（`user()` / `index()` / `permission()` 返回 `null`） |

---

## 6. 构建与依赖

### 6.1 依赖清单（见本模块 `pom.xml`）

| 依赖 | 版本 | Scope | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-datasource-starter` | 随版本链 | `provided` | `JdbcEngine` / `JdbcReactorEngine` 基类、`Dialect`、Flyway 基础设施 |
| `com.zaxxer:HikariCP` | 由父 POM 管理 | compile | 同步引擎连接池 |
| `io.projectreactor:reactor-core` | 由父 POM 管理 | compile | 响应式引擎（`Flux` / `Mono`），随核心 starter 传递给宿主应用 |
| `com.clickhouse:clickhouse-r2dbc` | `0.6.0` | runtime | R2DBC 驱动，运行时经 SPI 被 `JdbcReactorEngine` 发现 |
| `com.clickhouse:clickhouse-jdbc` | `0.6.0` | runtime | JDBC 驱动 |

### 6.2 编译验证

```bash
# 在 utils-support-parent-starter 仓库根执行
mvn -o -q compile -DskipTests

# 仅构建本模块（连同上游依赖）
mvn -o -q compile -DskipTests -pl utils-support-datasource-parent/utils-support-clickhouse-starter -am
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 实际位置 |
| --- | --- |
| ClickHouse 方言及环境配置（`ClickHouseDialect`、`ClickHouse20Dialect`、`META-INF/dialect-env/clickhouse.env`） | `utils-support-datasource-starter` |
| 引擎基类与查询体系（`JdbcEngine`、`JdbcReactorEngine`、Lambda 包装器、`SqlExecutor`、`EngineDataSource`、`DataSourceOptions`） | `utils-support-datasource-starter` / `utils-support-common-starter` |
| SPI 接口定义（`Engine`、`ReactorEngine`、`ScriptConverter`、`Dialect`） | `utils-support-common-starter` / `utils-support-datasource-starter` |
| 多数据源联邦查询（`DataSourceConversion`，如 Calcite） | `utils-support-calcite-starter` |
| Spring Boot 自动装配、application 配置键 | 无（本模块是纯 SPI 扩展，见第 4 节） |
| 连接池参数调优 | 仅 `maximumPoolSize=10` 写死在源码中，其余 Hikari 参数未开放 |
| clickhouse 专属 `MetaData` / `UserManager` / `IndexManager` / `PermissionManager` 实现 | 本模块未提供；按协议查找未命中时走默认回退或返回 `null` |
