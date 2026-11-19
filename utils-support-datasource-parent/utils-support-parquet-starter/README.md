# utils-support-parquet-starter

> Parquet 列式存储 ORM：把 `Engine` 数据源引擎落到**本地真实 `.parquet` 文件**上。数据按实体推断 Avro 模式整表落盘，查询读取真实文件后按 WHERE 过滤，更新/删除以「读-改-写回」实现并返回真实行数。
>
> 版本：`4.0.0.51`（类自 `4.0.0.42` 起提供）｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.parquet.support.engine`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 存储与映射规则](#4-存储与映射规则)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)
- [9. 插件化数据迁移（parquet ↔ rdbms）](#9-插件化数据迁移parquet--rdbms)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-parquet-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `Engine` SPI 体系下的 Parquet 列式文件 ORM 引擎（SPI 键 `parquet`） | 不是 JDBC 驱动/连接池，也不是新的事务型数据库 |
| 存储 | 单机本地目录下的真实 `<表名>.parquet` 文件，文件是唯一事实来源 | 不接 HDFS/对象存储，不是分布式数据仓库 |
| 模式 | 按实体字段自动推断 Avro 模式，所有列均为 `["null", 基础类型]` 可空联合 | 不支持集合、嵌套对象、`BigDecimal` 等复杂列 |
| 读写 | `store` 整表覆盖写（临时文件 + 原子改名）；Lambda 查询/更新/删除走「读-改-写回」 | 无原生 SQL 语句执行、无事务、无增量 append |
| 依赖 | 本地 IO 复用 parquet 自带 `LocalInputFile`/`LocalOutputFile`，不引入 Hadoop 运行时行为 | 运行期不触发 Hadoop `FileSystem`/`UGI` |

**判断口径**：需要「单机、免部署、把结构化实体持久化成列式文件并用 Lambda 语法读写」时用本模块；需要 SQL 方言、JOIN 下推、物理分页、元数据 DDL 时，请用 `utils-support-datasource-parent` 下的 JDBC 系列模块。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建引擎 | `Engine.create("parquet")` | SPI 键 `parquet`；也可直接 `new ParquetEngine()` |
| 指定数据目录 | `engine.addDataSource("default", "./data/parquet")` | 目录传 `String`（或经 `EngineDataSource` 传 `File`）；不存在自动创建 |
| 整表写入 | `engine.store("user", List.of(u1, u2))` | 覆盖写 `<目录>/<表名>.parquet`；空列表抛异常 |
| 条件查询 | `engine.query(User.class).eq(...).list()` | 读真实文件后内存过滤，排序/分页由父类完成 |
| 分页查询 | `engine.query(User.class).page(1, 10)` | 内存分页（无数据库物理分页下推） |
| 条件计数 | `engine.count(engine.query(User.class).eq(...))` | 执行查询后对结果计数 |
| 条件更新 | `engine.update(User.class).set(...).eq(...).update()` | 读-改-写回，返回真实受影响行数 |
| 条件删除 | `engine.delete(User.class).eq(...).remove()` | 读-改-写回，返回真实删除行数 |
| 关闭引擎 | `engine.close()` | 终态：关闭后不可复活，需重新构造实例 |

### 2.2 `ParquetEngine` 自身方法（源码已验证）

| 方法 | 说明 |
| --- | --- |
| `addDataSource(String name, EngineDataSource<T> dataSource)` | 注册数据源；底层源支持目录路径 `String`/`CharSequence` 或 `File`，其它类型抛 `IllegalArgumentException` |
| `addDataSource(String name, String srcDir)` | 便捷重载：直接传目录路径，返回 `ParquetEngine` 便于链式调用 |
| `store(String name, List<T> data)` | 覆盖写真实文件；文件名按**实体类**推导（与 `name` 无关），同时把数据登记进内存镜像 |
| `close()` | 置关闭标记并释放父类持有的数据源与内存映射 |

### 2.3 继承自 `AbstractEngine` 的常用入口

| 方法 | 在本引擎中的行为 |
| --- | --- |
| `query(Class)` / `update(Class)` / `delete(Class)` | 返回 Lambda 包装器；终端执行分别落到 `executeNewQuery` / `executeUpdate` / `executeDelete` |
| `insert(Class, entity)` / `insertBatch(Class, list)` | 只追加进**内存镜像**，不落盘；持久化入口是 `store` |
| `executeQuery(wrapper)` / `executePage(wrapper, pn, ps)` / `count(wrapper)` | 排序与分页截断在内存完成 |
| `getExecutor()` | 恒返回 `null`（无 SQL 执行器） |
| `supportsSql()` | `false`：`querySql`/`executeSql` 抛 `UnsupportedOperationException` |
| `supportsMeta()` | `false`：本模块未提供 `MetaData` 实现 |
| `isClosed()` | 反映关闭状态；`close()` 后所有读写抛 `IllegalStateException` |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-parquet-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.parquet.support.engine.ParquetEngine;

import java.util.List;

public class ParquetQuickStart {

    /** 实体：需具备无参构造；字段类型须在可映射清单内（见 4.3） */
    public static class User {
        private Long id;
        private String name;
        private Integer age;

        public User() { }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
    }

    public static void main(String[] args) {
        // 1. SPI 创建引擎（META-INF/extensions 登记键为 "parquet"）
        ParquetEngine engine = (ParquetEngine) Engine.create("parquet");

        // 2. 指定 Parquet 文件基础目录（不存在自动创建，须可写）
        engine.addDataSource("default", "./data/parquet");

        // 3. 整表写入（覆盖落盘）：文件为 ./data/parquet/user.parquet
        User u1 = new User(); u1.setId(1L); u1.setName("张三"); u1.setAge(18);
        User u2 = new User(); u2.setId(2L); u2.setName("李四"); u2.setAge(25);
        engine.store("user", List.of(u1, u2));

        // 4. Lambda 查询：读真实文件 → WHERE 过滤 → 排序
        List<User> adults = engine.query(User.class)
                .gt(User::getAge, 17)
                .orderByDesc(User::getId)
                .list();

        // 5. 更新（读-改-写回，返回真实受影响行数）
        int updated = engine.update(User.class)
                .set(User::getName, "王五")
                .eq(User::getId, 1L)
                .update();

        // 6. 删除（读-改-写回，返回真实删除行数）
        int removed = engine.delete(User.class)
                .eq(User::getId, 2L)
                .remove();

        // 7. 关闭（终态；再次使用需重新构造 ParquetEngine）
        engine.close();
    }
}
```

### 3.3 使用要点

- **先 `addDataSource` 再读写**：未初始化目录或 `close()` 之后调用读写会直接抛 `IllegalStateException`。
- **表名跟随实体类**：文件名取 `@TableName` 注解值，未标注时取类名驼峰转下划线（`User` → `user`）；`store` 的 `name` 参数只作为内存镜像键，不决定文件名。
- **`store` 是覆盖写**：每次写入都会用新数据整体替换文件内容；没有增量 append 语义。

---

## 4. 存储与映射规则

### 4.1 文件布局与写入原子性

- 数据文件：`<baseDir>/<表名>.parquet`。
- 写入先落临时文件 `<表名>-<序号>.writing.parquet`，写完后 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 原子改名；平台不支持原子移动时退化为覆盖移动。中途失败会尽力删除临时文件，不破坏既有数据。
- 同一张表的并发写入由**表级 `ReentrantLock`**（按目标文件路径）串行化，临时文件名带序号避免互相踩踏；锁只在进程内有效，跨进程无互斥。
- 读取时若文件存在但长度为 0，抛 `IllegalStateException`（视为写入中断残留）。

### 4.2 表名与路径安全

- 合法表名白名单：首字符为字母/下划线/汉字，其余为字母/数字/下划线/汉字，可含点号（兼容 `@TableName("schema.table")` 写法），不得以点号结尾，长度上限 128。
- 白名单不含 `/`、`\`，`../` 等路径穿越写法一律被拒绝；解析出的目标文件规范化路径必须落在基础目录之内，否则抛 `IllegalArgumentException`。
- 目录校验：目录不存在时自动 `mkdirs`；不是目录或不可写时抛 `IllegalStateException`。

### 4.3 类型映射

可进入文件模式的实体字段类型（含父类字段，跳过 `static`/合成字段）：

| 实体类型 | Parquet/Avro 列类型 | 说明 |
| --- | --- | --- |
| `String` | `string` | — |
| 枚举 | `string` | 按常量名落盘，读回自动还原 |
| `boolean`/`Boolean` | `boolean` | — |
| `int`/`Integer`、`long`/`Long`、`short`/`Short`、`byte`/`Byte` | `long` | 统一按整型落盘 |
| `float`/`Float` | `float` | — |
| `double`/`Double` | `double` | — |
| `java.util.Date` | `long`（timestamp-millis 逻辑类型） | 写入降为毫秒数，读回还原为 `Date` |

- 每列均包装为 `["null", 基础类型]` 可空联合，实体字段允许为 `null`。
- 集合、嵌套对象、`BigDecimal` 等类型**不进入文件模式**；实体没有任何可映射列时抛 `IllegalStateException`。
- 列名 = 字段名驼峰转下划线后做 Avro 合法化（非法字符替换为 `_`、数字开头补前导 `_`）；重名列追加 `_2`、`_3` 序号。
- 实体必须有**无参构造**（读回时反射实例化），读写均走字段反射。

### 4.4 查询、更新与删除语义

- **查询**：读取整表真实文件 → 映射为实体 → `MemoryWhereParser` 按 WHERE 子句（`=、!=、<>、>、>=、<、<=、LIKE、IN、IS [NOT] NULL、BETWEEN、AND/OR、括号分组、? 占位符`）内存过滤；排序与 limit/offset 截断由父类完成。文件不存在返回空表；文件中存在但实体已移除的列被跳过（模式演进后旧文件仍可读）；实体新增而文件尚无的列保持字段默认值。
- **更新**：读出整表 → 按 SET 赋值命中行 → 整表写回 → 返回真实受影响行数。SET 仅 `?` 占位符消费参数，字面量支持 `'字符串'`（`''` 转义）、`NULL`、整数/小数；无法求值的表达式直接抛 `IllegalArgumentException`。未命中任何行时不回写文件。
- **删除**：读出整表 → 剔除命中行 → 整表写回 → 返回真实删除行数；未命中时不回写。

---

## 5. 配置项

无。

依据：本模块源码与 `pom.xml` 中不存在任何 `@ConfigurationProperties`、系统属性读取或配置文件；运行期唯一外部输入是 `addDataSource` 传入的基础目录。写入侧的临时文件后缀（`.writing.parquet`）、表名白名单、类型映射等均为源码内固定常量，不开放配置。

---

## 6. SPI 扩展点

本模块**只注册** `Engine` 这一个 SPI 扩展，不定义新的 SPI 接口：

| 接口 | key | 实现 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `parquet` | `com.chua.parquet.support.engine.ParquetEngine` |

登记文件：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.datasource.engine.Engine`

```text
parquet=com.chua.parquet.support.engine.ParquetEngine
```

调用方统一通过 `Engine.create("parquet")` 获取实例；也可以直接 `new ParquetEngine()`。若要接入自己的存储引擎，请在自己的模块里实现 `Engine`/`AbstractEngine` 并登记各自的 extensions 文件，不要修改本模块。

GraalVM native-image 配置随模块内置：`META-INF/native-image/com.chua/utils-support-parquet-starter/reflect-config.json`（注册 `ParquetEngine` 全部构造器/方法/字段）与 `resource-config.json`（保留 `META-INF/extensions/.*`）。

---

## 7. 构建与依赖

### 7.1 编译要求

- JDK 25（仓库根 POM `maven.compiler.source/target=25`）。
- 在 `utils-support-parent-starter` 仓库根目录构建：

```bash
mvn -pl utils-support-datasource-parent/utils-support-parquet-starter -am clean package
```

- Surefire 已配置 `forkedProcessExitTimeoutInSeconds=15`、`surefire.timeout=120`：客户端库存在非守护线程，测试完成后强制回收 fork 进程。

### 7.2 依赖清单（来自本模块 `pom.xml`）

| 依赖 | 版本 | 范围 | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM | compile | `Engine`/SPI/工具契约 |
| `com.chua:utils-support-datasource-starter` | 随父 POM | provided | `AbstractEngine` 基类与内存 WHERE 解析 |
| `org.apache.parquet:parquet-avro` | `1.18.1`（根 POM `parquet.avro.version` 托管） | compile | Avro 模式读写 |
| `org.apache.parquet:parquet-hadoop` | `1.18.1`（同上托管） | compile | Parquet 文件读写核心 |
| `org.apache.parquet:parquet-common` | `1.18.1`（显式对齐） | compile | 本地 `InputFile`/`OutputFile` |
| `org.apache.parquet:parquet-column` | `1.18.1`（显式对齐） | compile | 列式编码 |
| `org.apache.hadoop:hadoop-client-api` | `3.5.0` | compile | 仅编译期需要：`AvroParquetWriter.builder` 存在 hadoop `Path` 重载，缺失会导致重载解析失败 |
| `org.apache.hadoop:hadoop-client-runtime` | `3.5.0` | compile | 同上 |
| `org.springframework:spring-context` / `spring-jdbc` | 随父 POM | compile | 迁移能力：容器装配与 `JdbcTemplate` 访问 |
| `org.springframework.boot:spring-boot-autoconfigure` / `spring-boot-jdbc` | 随父 POM | compile | 迁移能力自动装配 |
| `javax.inject:javax.inject` | `1`（根 POM 托管） | compile | JSR-330 标准注入注解（`@Inject`） |
| `jakarta.annotation:jakarta.annotation-api` | `3.0.0`（根 POM 托管） | compile | `@PostConstruct`/`@PreDestroy` 生命周期 |
| `org.junit.jupiter:junit-jupiter` / `org.mockito:mockito-core` | 随父 POM | test | 迁移能力单元测试 |

> 运行期本引擎走 `LocalInputFile`/`LocalOutputFile` 本地文件通道，不会触发 Hadoop `FileSystem`/`UGI`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| 原生 SQL 语句（`querySql`/`executeSql`/`queryPage(String, ...)` 等） | 不支持：`getExecutor()` 恒为 `null`，`supportsSql()` 为 `false`，调用抛 `UnsupportedOperationException` |
| 元数据/DDL（建表、索引、`meta()`/`ddl()` 的真实实现） | 不支持：本模块未提供 `MetaData`/`DslManager` 实现 |
| 事务与回滚 | 无：每次写入是「临时文件 + 原子改名」的整表覆盖，不存在部分提交 |
| 增量写入 | 无：持久化入口只有 `store` 整表覆盖；`insert`/`insertBatch` 只写内存镜像，不落盘 |
| 物理分页下推 | 无：查询全表读入内存后过滤、排序、分页 |
| 复杂列类型 | 不支持：集合、嵌套对象、`BigDecimal` 等不进入文件模式 |
| HDFS/对象存储 | 不支持：仅本地文件系统 IO |
| 跨进程文件锁 | 无：表级写锁仅进程内有效，多进程同时写同一目录需自行协调 |
| 引擎复活 | 不支持：`close()` 是终态，关闭后需重新构造 `ParquetEngine` 实例 |

---

## 9. 插件化数据迁移（parquet ↔ rdbms）

> 自 `4.0.0.51` 起提供，包名 `com.chua.parquet.support.migration`。
> 在已注册的存储引擎之间按批搬迁数据，不落业务库；内置 Parquet ↔ RDBMS 双向插件，可自定义插件扩展更多方向。

### 9.1 架构与扩展点

| 角色 | 说明 |
| --- | --- |
| `@MigrationPlugin(name, order)` | 插件标注：`name` 为路由键（约定 `源类型->目标类型`），`order` 决定注册顺序 |
| `DataMigrator` | 插件 SPI：`read(source, request, offset, batchSize)` / `write(target, request, batch)` / `close()` / `getName()` / `getOrder()` |
| `AbstractPluginMigrator` | 插件基类：注解解析、迁移会话（`ThreadLocal`）、失败回滚钩子、Parquet 本地 IO 与 Avro 模式推断工具 |
| `MigrationService` | 统一入口：按插件名路由 → 逐批 `read → write` → 失败回滚 → 会话清理 |
| `MigrateRequest` / `MigrateResult` | 迁移请求（builder）与结果（成功/失败、读/写行数、批数、耗时、消息） |

内置插件（自动装配，需容器中存在 `JdbcTemplate`）：

| 插件名 | 方向 | 读 | 写 |
| --- | --- | --- | --- |
| `parquet->rdbms` | Parquet 文件 → RDBMS 表 | 读 `<baseDir>/<表名>.parquet` 按 offset 分批 | `INSERT INTO ... VALUES (...)` 批量落库 |
| `rdbms->parquet` | RDBMS 表 → Parquet 文件 | `SELECT *` 内存分批 | 追加语义：首次写前快照旧文件，每批「快照 + 已累积新增 + 本批」原子覆写 |

### 9.2 快速使用

```java
MigrateResult result = migrationService.migrate(MigrateRequest.builder()
        .sourceType("parquet").targetType("rdbms")   // 决定插件路由：parquet->rdbms
        .sourceEngine("parquet")                     // 非 rdbms 侧的 Engine Bean 名，缺省取 sourceType
        .sourceTable("user").targetTable("t_user")
        .batchSize(500)                              // 默认 500，必须 > 0
        .build());                                   // migratorName 可显式指定插件，缺省按 sourceType->targetType 推导

if (result.isSuccess()) {
    // totalRead / totalWritten / batchCount / costMs
}
```

### 9.3 失败回滚与自动装配

- **失败语义**：任一批 `read`/`write` 抛异常即终止，返回失败结果（不向上抛出），并回滚目标端已写入数据：
  - `parquet->rdbms`：对本次已插入的行按**全列匹配**逐行 `DELETE`（NULL 列用 `IS NULL` 判定），只删除本插件写入的数据；
  - `rdbms->parquet`：恢复迁移前快照——迁移前有文件则重写回快照内容，否则删除迁移产生的新文件。
- **自动装配**：`MigrationAutoConfiguration` 注册 `MigrationService`（`@ConditionalOnMissingBean`，可自行声明 Bean 覆盖）；`AdditionalPluginsConfiguration` 在容器存在 `JdbcTemplate` 时注册两个内置插件。
- **自定义插件**：声明自己的 `DataMigrator` Bean（类上标注 `@MigrationPlugin`）即可被自动收集；插件名为空或重复会在启动期失败。
- **JSR-330**：插件以 `@Inject` 标注注入点（`ParquetToRdbmsMigrator` 构造注入、`RdbmsToParquetMigrator` 字段注入）。Spring Framework 7 起不再运行期处理 `javax.inject`，实际装配由自动装配的 `@Bean` 方法参数/setter 完成，`@Inject` 保留为标准元数据，兼容其他 JSR-330 容器。

### 9.4 边界

| 项 | 说明 |
| --- | --- |
| 事务 | 迁移**不是分布式事务**：RDBMS 侧回滚是补偿性 DELETE，目标表存在触发器/约束冲突时回滚可能不完整，结果消息会注明 |
| 中间存储 | 迁移过程不写任何业务库，源/目标之外不产生持久化中间态（仅目标端临时 `.parquet` 文件） |
| 读取方式 | Parquet 侧整文件读入内存后按 offset 切片；RDBMS 侧 `SELECT *` 全量载入内存后切片，**大表请按主键区间拆成多次 `migrate`** |
| 幂等 | 追加语义不判重；重复迁移会产生重复数据，需业务侧保证幂等或先清空目标 |

---

## 许可证

Apache License 2.0
