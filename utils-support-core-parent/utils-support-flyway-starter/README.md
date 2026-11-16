# utils-support-flyway-starter

> utils 体系的**数据库迁移 SPI 高优先级实现**：以 `javax.sql.DataSource` 为底座的 Flyway 兼容脚本迁移器（版本记录表 + checksum + 多类型脚本），接口与扫描/记录语义复用 `utils-support-common-starter` 的 `Flyway` / `FlywayScripts` / `FlywayHistory`。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，继承自 `utils-support-core-parent`）｜包名：`com.chua.flyway.support`

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
    <artifactId>utils-support-flyway-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `Flyway` SPI（扩展名 `flyway`）的**高优先级实现**：`@Spi(value = Flyway.SPI_NAME, order = 1000)`，在 `flyway` 扩展键下排序最前 | 不是 Flyway 官方库的封装（`pom.xml` 不依赖任何 `org.flywaydb` 坐标） |
| 底座 | 以 `javax.sql.DataSource` 为构造上下文，JDBC 直连目标库执行 | 不以 `Engine` 为底座：`Engine#flyway()` 无法供给 `DataSource` 参数，实测命中 common-starter 的 `DefaultFlyway`（语句走引擎执行链、可被拦截器增强） |
| 依赖 | 只依赖同仓 `utils-support-common-starter`（版本 `${project.version}`） | 不依赖 Spring / Spring Boot，不依赖数据库驱动，不引入三方 Flyway |
| 配置 | 纯 Java SPI 实现，零配置项 | 没有 `application.properties` / `application.yml` 键名绑定 |
| 脚本 | 规范命名 `V{版本}__{描述}.sql`（版本支持点分多段），位置支持 `classpath:` / `classpath*:` 与文件系统目录（递归） | 不识别其他命名或格式的脚本文件（不匹配命名的文件一律忽略） |
| 幂等 | 版本记录表 `sys_database_version`，复合主键 `(version, script_name)`，重复 `migrate()` 不重复执行 | 不提供脚本内容的变更检测（已应用脚本仅凭 `success=true` 跳过，见 §7） |

**判断口径**：需要以 `DataSource` 为上下文做数据库迁移时使用本模块；需要以 `Engine` 为上下文（语句走引擎执行链）时使用 common-starter 的 `DefaultFlyway`。两个实现共用同一套扫描与记录语义、同一张记录表，交替使用不会出现「同一脚本在各自记录表里被判定为未执行」的重复迁移。

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.flyway.support.DataSourceFlyway`。
- SPI 扩展声明文件 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.datasource.flyway.Flyway`。
- 契约（接口、扫描、记录、转换）全部来自 common-starter 的 `com.chua.common.support.lang.datasource.flyway` 包。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 获取迁移器 | `ServiceProvider.of(Flyway.class).getNewExtensions(Flyway.SPI_NAME, dataSource)` | 扩展键 `flyway` + `DataSource` 构造参数；返回列表**首个即 `DataSourceFlyway`** |
| 全量迁移 | `flyway.migrate()` | 执行所有未应用的迁移脚本，返回本次执行的脚本数量 |
| 仅结构脚本 | `flyway.migrateInit()` | 跳过初始化数据脚本（描述段以 `initdata_` 开头的文件）；与 `migrate()` 同一条实现路径 |
| 仅初始化数据 | `flyway.migrateInitData()` | 只执行初始化数据脚本（`FlywayScripts.isInitData` 判定） |
| 迁移信息 | `flyway.info()` | `List<MigrationInfo>`（`version` / `description` / `script` / `applied`），按版本升序 |
| 单文件执行 | `flyway.execute(Path)` | 执行单个 SQL 脚本文件，**不纳入版本记录**；脚本路径为空抛 `IllegalArgumentException` |
| 方言转换 | `flyway.protocol("h2")` | 指定目标库协议后，语句经 `ScriptConverter` SPI 做方言转换；返回 `this` |
| 语句级容错 | `flyway.continueOnError(boolean)` | 默认 `true`：单条方言差异语句失败时跳过并记录，不中断整体迁移；`false`：任一句失败即抛 `IllegalStateException` |
| 失败排查 | `getFailedStatements()` / `getFailureReasons()` | 最近一次 migrate 中被容错跳过的失败语句（截断展示）及「语句 → 异常信息」 |

### 2.2 `DataSourceFlyway` 公开方法

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `DataSourceFlyway(DataSource dataSource)` | — | 构造迁移器；内部创建 `JdbcRunner`，用 `PreparedStatement` 读写历史表（`dataSource` 不允许为 `null`，在 `JdbcRunner` 规范构造器 `requireNonNull` 快速失败） |
| `location(String location)` | `Flyway` | 添加脚本位置：文件系统路径（递归）或 `classpath:` / `classpath*:` 前缀（枚举全部类路径根，含 jar 内资源）；空白位置忽略 |
| `separator(String separator)` | `Flyway` | 设置版本与描述分隔符，默认 `__`（`FlywayScripts.DEFAULT_SEPARATOR`）；空白忽略 |
| `protocol(String protocol)` | `DataSourceFlyway` | 设置目标数据库协议名（如 `h2` / `postgresql` / `oracle` / `sqlite` / `sqlserver`），启用 `ScriptConverter` SPI 方言转换；`null` 表示不做转换（脚本按目标库原生方言编写） |
| `info()` | `List<MigrationInfo>` | 扫描到的全部脚本（按版本升序），标注是否已应用（`success=true` 判定） |
| `migrate()` | `int` | 执行所有未应用脚本，返回本次执行的脚本数量 |
| `migrateInit()` | `int` | 仅执行结构脚本（建表 / 索引 / 兼容 ALTER），不执行初始化数据脚本 |
| `migrateInitData()` | `int` | 仅执行初始化数据脚本（INSERT / 初始化 UPDATE） |
| `execute(Path script)` | `int` | 执行单个 SQL 脚本文件（不纳入版本记录），返回执行语句数量 |
| `continueOnError(boolean value)` | `DataSourceFlyway` | 语句级容错开关，默认 `true`（对齐 `DataSourceScriptProperties.continueOnError` 默认 true 语义） |
| `getFailedStatements()` | `List<String>` | 最近一次 migrate 中被容错跳过的失败语句（不可变、截断展示），无失败时为空列表 |
| `getFailureReasons()` | `Map<String, String>` | 最近一次 migrate 中「失败语句 → 异常信息」，无失败时为空 Map |

### 2.3 迁移语义（关键事实）

- **脚本命名**：`V{版本}__{描述}.sql`，版本支持点分多段（`V1`、`V1.0`、`V1.0.0.2`）；排序按段数值比较（`1.2 < 1.10`），数值全等时段数少的在前；不匹配命名的文件一律忽略。文件名以描述段 `initdata_` 开头判定为初始化数据脚本（`FlywayScripts.isInitData`）。
- **位置语义**：`classpath:` 与 `classpath*:` 等价处理，都枚举类路径上**全部**同名根（目录与 jar 内资源都扫描）；位置中已含通配符时按原样使用，否则自动追加 `/**/*.sql` 递归匹配子目录；不带前缀的位置视为文件系统目录，递归扫描。
- **版本记录表**：`sys_database_version`，复合主键 `(version, script_name)`；列名统一 `sys_database_version_` 前缀（与内置 Spring 侧记录表默认值一致，避免记录表分裂）。已应用脚本在后续 migrate 中跳过（幂等）；失败脚本写入 `success=false`（checksum 占位 `FAILED`），可被单独重试。
- **建表兼容**：先按 `CREATE TABLE IF NOT EXISTS` 建表（MySQL / H2 / PostgreSQL / SQLite 写法）；失败则探测表是否已存在、存在即放过；不存在则用不带 `IF NOT EXISTS` 的建表语句再试（覆盖真 SQL Server）。写入记录先按复合主键 UPDATE、无记录时 INSERT，并发撞主键时降级 UPDATE。
- **语句拆分**：按分号拆分（忽略字符串外的 `--` 行注释、单引号字符串内的分号、`\'` / `\\` 转义），末尾非空白内容补为最后一条；空语句由执行方跳过。
- **语句级容错执行**：同一 `Connection` 上逐条执行；`continueOnError=true` 时单条失败记录失败语句并继续（典型场景：MySQL 专属 `PREPARE/EXECUTE` 段在 H2 下报错），否则任一句失败即中断并抛 `IllegalStateException`。
- **目标表判存跳过**：`INSERT INTO` / `INSERT IGNORE INTO` / `UPDATE` / `DELETE FROM` / `DELETE t1 FROM` / `MERGE INTO` / `TRUNCATE TABLE` 语句先取第一处目标表，用 JDBC 元数据判存在（先按原样查、再大小写不敏感兜底；元数据不可用时按「存在」处理）；表不存在则跳过该语句，**不算失败**（`ScriptResult.failed == 0` 即整体成功）。
- **校验和**：脚本内容 MD5 十六进制小写（`FlywayScripts.checksum`），随执行记录写入历史表；`info()` 中的版本号取脚本版本首段（`FlywayScripts.majorVersion`）。
- **空位置告警**：配置了位置却扫不到脚本时打印 `[flyway] 位置 {} 下未扫描到任何 {} 命名的迁移脚本` 告警，避免「迁移静默无操作」被误判为成功。
- **日志截断**：失败语句日志展示长度 120 字符（`LOG_STATEMENT_LENGTH`），异常消息中的语句长度 200 字符（`ERROR_STATEMENT_LENGTH`）。

---

## 3. 快速开始

前置：目标库 `DataSource`（如连接池）。

```java
import com.chua.common.support.lang.datasource.flyway.Flyway;
import com.chua.common.support.spi.ServiceProvider;

import javax.sql.DataSource;

DataSource dataSource = /* 目标库数据源 */;

// SPI 命中：扩展键 flyway + DataSource 构造参数，返回列表首个即 DataSourceFlyway
Flyway flyway = ServiceProvider.of(Flyway.class)
        .getNewExtensions(Flyway.SPI_NAME, dataSource)
        .get(0);

// 全量迁移（结构脚本 + 非初始化数据脚本）
int executed = flyway
        .location("/path/to/migrations")   // 或 "classpath:db/migration"，可多次调用
        .protocol("h2")                    // 可选：目标库协议名，启用方言转换
        .migrate();
System.out.println("本次执行脚本数: " + executed);

// 只执行初始化数据脚本（描述段以 initdata_ 开头）
flyway.migrateInitData();

// 查看已应用/待应用状态
flyway.info().forEach(m -> System.out.println(
        m.version() + "__" + m.description() + " (" + m.script() + ") applied=" + m.applied()));

// 语句级容错关闭 + 失败排查
flyway.continueOnError(false);
flyway.getFailedStatements();   // List<String>
flyway.getFailureReasons();     // Map<String, String>
```

脚本目录示例（命名即契约）：

```
/path/to/migrations/
├── V1__create_user.sql          # 建表
├── V2__add_index.sql            # 加索引
├── V2.1__init_all_order.sql     # 结构类脚本（init_all_ 前缀，migrate/migrateInit 会执行）
└── V2.1__initdata_all_user.sql  # 初始化数据脚本（initdata_ 前缀，仅 migrateInitData 执行）
```

执行链（与接口 javadoc 一致）：

```
原始脚本 --拆分--> 语句列表 --ScriptConverter.convert(protocol)--> 转换后语句列表 --JDBC--> 目标库
```

---

## 4. 配置项

**无。**

依据：`pom.xml` 仅依赖 `utils-support-common-starter`，不依赖 Spring / Spring Boot；`DataSourceFlyway` 是纯 Java SPI 实现类，类体内没有任何配置读取、属性绑定或键名常量（仅有日志截断长度两个私有常量）。全部行为通过链式方法（`location` / `separator` / `protocol` / `continueOnError`）在编码期配置，无外部配置文件。

---

## 5. SPI 扩展点

### 5.1 本模块作为扩展（实现方）

- 扩展键：`flyway`（`Flyway.SPI_NAME`）。
- 声明：`@Spi(value = Flyway.SPI_NAME, order = 1000)`，登记在 `META-INF/extensions/com.chua.common.support.lang.datasource.flyway.Flyway`：

```
flyway=com.chua.flyway.support.DataSourceFlyway
```

- 命中方式：`ServiceProvider.of(Flyway.class).getNewExtensions(Flyway.SPI_NAME, dataSource)` 返回列表首个即本类（`order = 1000` 保证在 `flyway` 扩展键下排序最前，优先于 common-starter 的 `DefaultFlyway`（`order = 0`））。注意 `Engine#flyway()` 无法供给 `DataSource` 参数，命中 `DefaultFlyway`。

### 5.2 本模块消费的 SPI（扩展方）

| 扩展点 | 接口 | 扩展键 | 说明 |
| --- | --- | --- | --- |
| 方言转换器 | `ScriptConverter`（`com.chua.common.support.lang.datasource.flyway`） | **按协议名**（如 `h2`、`postgresql`、`oracle`、`sqlite`、`sqlserver`），而非 `ScriptConverter.SPI_NAME`（`script-converter`） | `protocol(String)` 指定后，语句经 `ScriptConverter.getExtension(protocol)` 查找并调用 `convertAll(statements, protocol)`；SPI 未注册实现时退化为原样执行（仅 `continueOnError` 容错） |

查找顺序（`ScriptConverter.getExtension`，结果与 `collect()` 迭代顺序无关）：协议名注册的扩展 → 其余扩展按扩展名字典序遍历首个 `supports(protocol)` 命中者 → 兜底默认实现（common-starter 的 `DefaultScriptConverter`，`@SpiDefault` 支持全部协议）。

内置默认转换能力（common-starter 提供，无需额外实现）：剥离 `SET NAMES` / `SET FOREIGN_KEY_CHECKS`、`CREATE TABLE` 尾部 `ENGINE=InnoDB` / `CHARSET=utf8mb4` / `ROW_FORMAT` / `AUTO_INCREMENT` 等、内联 `COMMENT 'xxx'`、`USING BTREE` / `KEY idx ON` 行内索引声明；函数方言映射（`NOW()→CURRENT_TIMESTAMP` 等）；MySQL 动态 SQL 段（`PREPARE/EXECUTE/DEALLOCATE`、`SET @var`）目标库不支持时整条跳过。

**注册约定**（接口 javadoc 原文）：扩展键按协议名区开（如 `sqlite=<实现全限定名>`），不要用 `SPI_NAME` 作为协议实现的扩展键——与兜底默认实现同键时只会保留最高优先级的一个，其他协议的转换会被遮蔽。同仓示例：`utils-support-mysql-starter` 的 `com.chua.mysql.support.flyway.MysqlScriptConverter`。

---

## 6. 构建与依赖

### 6.1 依赖关系

```
utils-support-flyway-starter （com.chua:utils-support-flyway-starter:4.0.0.51）
└── com.chua:utils-support-common-starter:${project.version}
```

`pom.xml` 中仅有上述一个依赖；编译期注解处理走 core-parent 的 `lombok` 注解处理器路径。

### 6.2 构建

- 模块父 POM：`utils-support-core-parent`；版本 `4.0.0.51`（继承自 `utils-support-parent-starter`）。
- JDK：`release 25`（core-parent `maven-compiler-plugin` 配置 `<release>25</release>`，附带 `-Xlint:all`）。
- 构建命令：在 `utils-support-parent-starter` 仓库根执行 `mvn clean install`（连同 common-starter 一并构建）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 状态 | 依据 |
| --- | --- | --- |
| 以 `Engine` 为底座的迁移（语句走引擎执行链、可被拦截器增强） | 不在 | 命中 common-starter 的 `DefaultFlyway`（类 javadoc 实测结论） |
| 迁移回滚 / down 脚本 / 降级脚本 | 源码未提供 | `Flyway` 接口与 `DataSourceFlyway` 均无任何回滚相关方法 |
| 已应用脚本的内容变更检测（checksum 比对后告警/报错） | 不在 | migrate 判定已应用仅看 `success=true`，checksum 只写入历史表不参与比对 |
| 配置文件 / 键名绑定 | 无 | 不依赖 Spring / Spring Boot；见 §4 |
| 三方 Flyway 库的 API 兼容（`org.flywaydb` 坐标、`Flyway.configure()` 等） | 不在 | pom 无相关依赖；本模块是自有 `Flyway` SPI 的实现 |
| 事务性迁移（整脚本原子提交 / 回滚） | 不在 | 同一 `Connection` 上逐条 `Statement.execute`，无显式事务管理 |
| 存储过程 / 函数等块级定界符脚本 | 不在 | `splitStatements` 仅按字符串外分号拆分，无 `BEGIN...END` 块处理 |
| 连接池预热、连接缓存 | 不在 | `JdbcRunner` 每次读写都从 `DataSource` 取连接、用完即关（不吞异常） |
| 除 `.sql` 之外的脚本格式（如 CSV 种子、多文件打包） | 不在 | 扫描只认 `*.sql` |
| 迁移历史的可视化 / 管理界面 | 不在 | 仅提供 `info()` 编程接口 |

---

## 附：契约来源

本模块只承载实现；接口与全部语义工具位于：

- `com.chua.common.support.lang.datasource.flyway.Flyway`（迁移接口，`SPI_NAME = "flyway"`）
- `com.chua.common.support.lang.datasource.flyway.FlywayScripts`（命名解析 / 位置扫描 / 语句拆分 / checksum）
- `com.chua.common.support.lang.datasource.flyway.FlywayHistory`（`sys_database_version` 记录表读写）
- `com.chua.common.support.lang.datasource.flyway.MigrationInfo`（迁移信息 record）
- `com.chua.common.support.lang.datasource.flyway.ScriptConverter`（方言转换 SPI）
- `com.chua.common.support.lang.datasource.flyway.DefaultFlyway`（Engine 底座默认实现，`order = 0`）