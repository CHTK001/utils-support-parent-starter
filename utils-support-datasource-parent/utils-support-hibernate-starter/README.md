# utils-support-hibernate-starter

> utils 数据源家族的 **Hibernate 别名 DDL 管理器**：以 `hibernate` 别名注册到 `DslManager` SPI（扩展点 `dsl-manager`），完整继承 `DefaultDslManager` 的「JDBC 元数据读表结构 + 方言感知 DDL 生成」能力；自身不实现任何 Hibernate ORM 逻辑。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.hibernate.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. SPI 扩展点](#4-spi-扩展点)
- [5. 配置项](#5-配置项)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-hibernate-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `DslManager` SPI 的 `hibernate` 别名实现：`@Spi(value = "dsl-manager", order = -50)` | 不是 Hibernate ORM 的封装或使用入口 |
| 实现 | 继承 `DefaultDslManager`：JDBC `DatabaseMetaData` 读表结构 + 框架注入的 `Dialect` 生成方言感知 DDL | 不自行实现表结构读取与 DDL 生成；源码不使用任何 Hibernate ORM API |
| 兼容 | 保留 `hibernate` 历史别名，已有按该别名显式解析的调用方保持二进制兼容 | 不新增 DDL 能力；唯一覆写是 `type()` 返回 `"hibernate"` |
| 归属 | 数据源父模块 `utils-support-datasource-parent` 下的 DDL 子模块 | 不是独立 ORM 框架，自身不持有/不创建数据源 |

**判断口径**：需要 `hibernate` 别名下的 DDL 管理能力时引入本模块；DDL 的真实逻辑全部在 `utils-support-datasource-starter` 的 `DefaultDslManager` 中，本模块只承担「别名 + 优先级 + 兼容」。

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.hibernate.support.ddl.HibernateDdlManager`（`@author CH`，`@since 4.0.0.42`）。
- SPI 注册文件 **1** 个：`META-INF/extensions/com.chua.common.support.lang.datasource.engine.ddl.DslManager`。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`、`resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿 DDL 管理器（hibernate 别名） | `ServiceProvider.of(DslManager.class).getExtension("hibernate")` | 本模块注册的唯一实现 |
| 拿 DDL 管理器（引擎自动装配） | `engine.ddl()` | 先按 `Dialect.protocol()` 解析，未命中回退 `default`；解析后自动注入 `DataSource` 与 `Dialect` |
| 读单表结构 | `ddl.getTable(catalogName, schemaName, tableName)` | 走 JDBC 元数据；表不存在返回 `null` |
| 列出全部表结构 | `ddl.listTables(catalogName, schemaName)` | 仅统计 `TABLE` 类型 |
| 生成建表 DDL | `ddl.createTableDDL(catalogName, schemaName, tableName)` | 含列类型/长度、自增、NOT NULL、默认值、主键与注释；方言不支持内联注释时追加独立 `COMMENT ON` 语句 |
| 生成重命名表 DDL | `ddl.renameTable(schemaName, oldTableName, newTableName)` | 由方言渲染 RENAME 语句 |
| 生成复制表结构 DDL | `ddl.copyTableStructure(schemaName, sourceTableName, targetTableName)` | `CREATE TABLE ... AS SELECT ... WHERE 1=0` |
| 校验上下文 / 查看实现标识 | `ddl.requireContext()` / `ddl.type()` | 缺 `DataSource` 或 `Dialect` 时抛 `IllegalStateException`；`type()` 返回 `"hibernate"` |

---

## 2. 核心 API 与速查表

本模块无自有业务方法；以下 API 全部继承自 `DslManager` 接口与 `DefaultDslManager` 实现，行为与 `default` 实现完全一致。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `getTable(String catalogName, String schemaName, String tableName)` | `TableDef` | 读取表定义：列（`ColumnDef`：名称、类型、长度、可空、默认值、注释、序号、自增、主键）、主键数组、catalog/schema；表不存在返回 `null` |
| `listTables(String catalogName, String schemaName)` | `List<TableDef>` | 遍历元数据中的 `TABLE`，逐个调用 `getTable` 汇总 |
| `createTableDDL(String catalogName, String schemaName, String tableName)` | `String` | 由方言渲染 `CREATE TABLE`；表/列名经方言 `quote` 处理；列注释、表注释在方言支持时内联，否则生成 `COMMENT ON ...` 独立语句（单引号转义） |
| `renameTable(String schemaName, String oldTableName, String newTableName)` | `String` | 委托方言 `getRenameTableString` |
| `copyTableStructure(String schemaName, String sourceTableName, String targetTableName)` | `String` | 生成仅复制结构的 SQL（`WHERE 1=0`） |
| `type()` | `String` | 本模块实现返回 `"hibernate"` |
| `requireContext()` | `void` | `DataSource` 未注入抛「DDL 管理器缺少 DataSource」，`Dialect` 未注入抛「DDL 管理器缺少 Dialect」 |
| `setDataSource(DataSource)` / `setDialect(Dialect)` | `void` | `DataSourceAware` / `DialectAware` 注入入口 |
| `getDialect()` | `Dialect` | 返回已注入方言，未注入返回 `null` |

上下文注入约定（来自 `DslManager` 接口文档）：

- 由 `Engine#ddl()` 统一解析，调用方无需手工 `new` 实现类；
- 解析成功后框架自动向实现注入 JDBC `DataSource`（`DataSourceAware`）与当前 `Dialect`（`DialectAware`）；
- 实现类必须提供**无参构造**，上下文全部通过感知接口注入，不走构造参数。

---

## 3. 快速开始

### 3.1 按 `hibernate` 别名直接解析（最短路径）

```java
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.ddl.DslManager;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

// 1) 按 hibernate 别名解析（注册内容：hibernate=com.chua.hibernate.support.ddl.HibernateDdlManager）
DslManager ddl = ServiceProvider.of(DslManager.class).getExtension("hibernate");

// 2) 注入上下文（DataSourceAware / DialectAware；缺失时 requireContext() 抛 IllegalStateException）
ddl.setDataSource(dataSource);                    // javax.sql.DataSource
ddl.setDialect(Dialect.getExtension("mysql"));    // 方言决定生成语句的方言形态

// 3) 读取表结构
TableDef table = ddl.getTable(null, "public", "t_user");   // 不存在返回 null
List<TableDef> tables = ddl.listTables(null, "public");

// 4) 生成 DDL（只返回 SQL 文本，不执行）
String createSql = ddl.createTableDDL(null, "public", "t_user");
String renameSql = ddl.renameTable("public", "t_user", "t_user_bak");
String copySql   = ddl.copyTableStructure("public", "t_user", "t_user_bak");
```

### 3.2 经引擎解析（框架自动注入，免手工 set）

```java
// 已持有 Engine 时，直接取 DDL 管理器：
// 解析顺序 = 先以默认数据源的 Dialect.protocol() 作为扩展键查找，
// 未命中回退通用别名 "default"；解析成功后自动注入 DataSource 与 Dialect。
DslManager ddl = engine.ddl();
```

> 说明：`Engine#ddl()` 的自动解析链路按「方言协议名 → `default` 兜底」进行；`hibernate` 别名主要供历史上按该别名显式解析的调用方继续使用（见 `HibernateDdlManager` 类注释）。两种方式拿到的实现，注册与解析行为完全一致。

---

## 4. SPI 扩展点

### 4.1 本模块注册的实现

| 扩展点 | 扩展名 | 别名 | 实现 | order |
| --- | --- | --- | --- | --- |
| `DslManager` | `dsl-manager`（`DslManager.SPI_NAME`） | `hibernate` | `com.chua.hibernate.support.ddl.HibernateDdlManager` | `-50` |

注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.datasource.engine.ddl.DslManager`，内容一行：

```text
hibernate=com.chua.hibernate.support.ddl.HibernateDdlManager
```

同扩展点下，父类 `DefaultDslManager` 以别名 `default` 注册、`order = -100`；SPI 扩展列表按 order 降序排列。

### 4.2 消费的感知接口

| 接口 | 注入内容 | 说明 |
| --- | --- | --- |
| `com.chua.datasource.support.user.DataSourceAware` | JDBC `DataSource` | 由框架在解析后注入 |
| `com.chua.common.support.lang.datasource.engine.ddl.DialectAware` | 当前 `Dialect` | 由框架注入，免去从 JDBC URL 嗅探方言（嗅探需遍历全部方言比对 URL 前缀，慢且在 URL 模板调整后静默失配） |

### 4.3 GraalVM native-image

- `META-INF/native-image/com.chua/utils-support-hibernate-starter/reflect-config.json`：为 `HibernateDdlManager` 开放全部声明/公共构造器、方法、字段反射。
- `META-INF/native-image/com.chua/utils-support-hibernate-starter/resource-config.json`：打包 `META-INF/extensions/.*` 资源，保证 native 下 SPI 注册文件可见。

### 4.4 自定义扩展

替换或新增 DDL 管理器时，实现 `DslManager`（或继承 `DefaultDslManager`），提供无参构造，并按「别名=实现类全限定名」登记到同一扩展文件即可；注册格式示例见 `DslManager` 接口文档。

---

## 5. 配置项

**无。**

依据：

- 本模块唯一源码类 `HibernateDdlManager` 不读取任何配置键、系统属性或环境变量，也无 setter 之外的配置入口；
- `pom.xml` 未定义可配置属性；
- DDL 操作的 `catalogName` / `schemaName` / 表名均通过方法参数传入，不属于配置项。

---

## 6. 构建与依赖

### 6.1 依赖清单（`pom.xml`）

| 依赖 | 版本 | Scope | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 pom | compile | `DslManager` SPI、`Dialect`、`TableDef` 等契约 |
| `com.chua:utils-support-datasource-starter` | 随父 pom | **provided** | `DefaultDslManager` 父类与 `DataSourceAware`（由宿主提供） |
| `org.hibernate.orm:hibernate-core` | `7.4.11.Final` | compile | pom 声明的依赖；**源码未使用任何 Hibernate ORM API** |
| `net.bytebuddy:byte-buddy` | `1.18.14-jdk5` | compile | Hibernate 生态传递依赖 |
| `org.hibernate.validator:hibernate-validator` | `9.1.4.Final` | compile | Bean 校验实现 |
| `jakarta.validation:jakarta.validation-api` | `3.1.1` | compile | 校验 API |

> 父链：`utils-support-datasource-parent`（4.0.0.51）→ `utils-support-parent-starter`（4.0.0.51）。模块描述：`Hibernate Starter` / `Hibernate ORM DDL`。

### 6.2 构建

```bash
# 本模块编译（离线、跳过测试）
mvn -o -q compile -DskipTests

# 全仓库构建入口
cd utils-support-parent-starter && mvn compile -DskipTests
```

JDK 要求：Java **25**（父 pom `maven.compiler.source/target=25`，`java.version=25`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| DDL 管理器的通用实现（JDBC 元数据读结构、方言渲染 DDL、注释内联/独立语句） | `utils-support-datasource-starter` 的 `DefaultDslManager` |
| `DslManager` SPI 接口、`Dialect`、`DialectAware`、`TableDef` / `ColumnDef` | `utils-support-common-starter` |
| Hibernate ORM 本体能力（`Session` / `SessionFactory`、实体映射、HQL、懒加载、二级缓存） | 本模块**不提供**；`hibernate-core` 仅为 pom 声明依赖，源码未触碰 |
| SQL 执行、Lambda 链式查询/更新/删除、分页、插入 | `Engine` 体系（`utils-support-common-starter` 及各数据库 starter） |
| SQL 脚本版本化迁移 | `Flyway` 体系（`utils-support-flyway-starter` 等） |
| 各数据库方言的专属 `DslManager` 实现 | 对应数据库 starter（如 mysql、postgresql 等，注册格式见 `DslManager` 接口文档） |
| 数据源/连接池的创建与管理 | 宿主或 `utils-support-datasource-starter`；本模块的 `DataSource` 由框架注入 |
