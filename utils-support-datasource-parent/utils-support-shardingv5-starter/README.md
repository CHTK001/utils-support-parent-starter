# utils-support-shardingv5-starter

> utils 数据源体系中的 **ShardingSphere V5 分库分表适配器**：把若干个物理 `javax.sql.DataSource`，按链式注册的表/库分片规则，转换为一个 ShardingSphere JDBC 逻辑数据源。
>
> 版本：`4.0.0.51`（随父 POM `utils-support-datasource-parent`）｜JDK：`25`（仓库 `java.version=25`）｜包名：`com.chua.shardingv5.support.conversion`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-shardingv5-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | ShardingSphere JDBC（`shardingsphere-jdbc:5.5.2`）的轻量规则装配器：把「表前缀 + 分片列 + 分片数 + 算法」翻译成 `ShardingRuleConfiguration`，产出逻辑 `DataSource` | 不是连接池，不创建、不持有、不关闭物理数据源 |
| 接入方式 | `DataSourceConversion` SPI 的实现（key=`SHARDINGV5`），也可以直接 `new ShardingV5Conversion()` 链式使用 | 不是 Spring Boot starter：无自动装配类，无配置键绑定 |
| 能力范围 | 分表、分库+分表、按时间范围挂载分表、物理表自动发现与 TTL 缓存、内置/自定义分片算法注册 | 不包含读写分离、加密、脱敏、影子库等 ShardingSphere 其它规则；SQL 路由与改写交给 ShardingSphere |

### 1.3 规模

- Java 源文件 **7** 个，全部位于唯一包 `com.chua.shardingv5.support.conversion`：
  `ShardingV5Conversion`、`AlgorithmHolder`、`CacheEntry`、`DbConfig`、`TableConfig`、`TimeRangeConfig`、`TableCache`。
- SPI 声明文件 **1** 个（`META-INF/extensions/com.chua.datasource.support.engine.DataSourceConversion`）。
- 内置 GraalVM `native-image` 描述文件 2 个：`reflect-config.json`（全量反射注册 `ShardingV5Conversion`）、`resource-config.json`（包含 `META-INF/extensions/.*`）。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 多个物理库合成一个分片数据源 | `new ShardingV5Conversion().convert(List.of(ds0, ds1), env)` | 返回 ShardingSphere 逻辑 `DataSource` |
| 注册分片表（默认 MOD 取模） | `.table("t_order", "order_id", 16)` | 逻辑表 `t_order` → 物理表按前缀自动发现 |
| 指定算法注册分片表 | `.table("User", "user_id", 16, "myMod")` | 算法名需已注册或为内置算法 |
| 注册内置算法 | `.algo("mod", "MOD")` / `.algo("hm", "HASH_MOD")` / `.algo("tm", "INTERVAL", props)` | 内置名见 2.4 |
| 注册自定义算法类 | `.algo("myMod", MyModAlgorithm.class)` | CLASS_BASED，类需有无参构造器 |
| 分库 + 分表 | `.db("User", "tenant_id", 2, "MOD")` | 与同名前缀的 `table()` 组合生效 |
| 按时间范围挂载已有分表 | `.tableTimeRange("create_time", "2026-01-01", "2026-12-31", List.of("User_202601", "User_202602"))` | 显式列出真实表 |
| 表发现结果缓存 | `.cache(60)` | TTL 秒；`-1` 表示不缓存（默认） |
| 关闭物理表自动发现 | `.autoDiscover(false)` | 默认 `true` |
| 按 SPI 名称取用 | `META-INF/extensions` 中 key=`SHARDINGV5` | 可经 common-starter 的 `ServiceProvider` 按名获取实现 |

### 2.2 ShardingV5Conversion 方法一览

| 方法 | 说明 |
| --- | --- |
| `autoDiscover(boolean auto)` | 是否自动扫描物理表，默认 `true`；链式返回 |
| `cache(int seconds)` | 设置表发现缓存 TTL（秒），默认 `-1` 不缓存；链式返回 |
| `algo(String name, Class<? extends ShardingAlgorithm> implClass)` | 注册自定义算法实现类（CLASS_BASED，属性 `class-name`、`strategy=STANDARD`）；类缺少无参构造器时告警 |
| `algo(String name, String type)` | 注册内置算法（如 `MOD` / `HASH_MOD` / `INTERVAL`） |
| `algo(String name, String type, Map<String, String> props)` | 注册内置算法并附加参数 |
| `db(String prefix, String shardingColumn, int dbCount, String algorithm)` | 配置分库策略（库数量、库分片列与算法） |
| `table(String prefix, String shardingColumn, int shardCount, String algorithm)` | 注册分片表（表前缀、分片列、表数量、算法） |
| `table(String prefix, String shardingColumn, int shardCount)` | 同上，算法默认 `MOD` |
| `tableTimeRange(String shardingColumn, String start, String end, List<String> realTables)` | 注册时间范围分片：起止时间 + 显式真实表清单 |
| `convert(List<DataSource> dataSources, DataSourceEnvironment env)` | 执行转换，返回 ShardingSphere 逻辑数据源（`DataSourceConversion` 接口方法） |
| `type()` | 返回 `"SHARDINGV5"`（`DataSourceConversion` 接口方法） |

### 2.3 辅助类型

| 类 | 说明 |
| --- | --- |
| `TableConfig` | 分表配置：表前缀、分片列、表数量、算法 |
| `DbConfig` | 分库配置：表前缀、分片列、库数量、算法 |
| `TimeRangeConfig` | 时间范围分片配置：分片列、起止时间、真实表清单 |
| `AlgorithmHolder` | 算法配置持有者：算法类型 + 属性表 |
| `TableCache` | 表结构缓存：按前缀扫描并按 TTL 缓存，`getTables(prefix)` 取表清单（数字后缀降序，新表在前），`clear()` 清空 |
| `CacheEntry` | 缓存条目：表名清单 + 过期时间戳 |

### 2.4 convert() 行为细节（均与源码一致）

- **数据源命名**：`env.getPoolName()` 非空时物理数据源命名为 `<poolName>_<下标>`，否则 `ds_<下标>`；逻辑数据源名为 `sharding_<8位随机串>`。
- **表发现**：通过 JDBC `DatabaseMetaData.getTables` 扫描，类型为 `TABLE` 与 `VIEW`；未参与分片的表注册为单表规则（原样透传）。
- **分片表匹配**：物理表名以前缀 `startsWith` 匹配，按表名数字后缀降序（新表在前）取前 `shardCount` 个；匹配不到时告警跳过。
- **分库 + 分表**：`db()` 与 `table()` 使用相同前缀时，同时设置库分片策略与表分片策略；仅 `table()` 时只设表策略。
- **时间范围分片**：第 `i` 个配置生成逻辑表名 `tr_<i>_<分片列>`、算法名 `tr_algo_<i>`（类型 `INTERVAL`，参数 `datetime-lower`/`datetime-upper`/`datetime-partition-pattern=yyyy-MM-dd`）。
- **算法兜底**：`table()`/`db()` 引用了未注册的算法名时自动补注册；不是内置算法名的按 `MOD` 处理并告警。内置算法名清单：`MOD`、`HASH_MOD`、`INTERVAL`、`CLASS_BASED`、`STANDARD`、`INLINE`、`COMPLEX_INLINE`、`HINT_INLINE`、`COSID`、`SNOWFLAKE`、`UUID`、`NANOID`。
- **sharding-count**：自动补注册的 `MOD`/`HASH_MOD` 使用对应表/库分片数；显式 `.algo(name, "MOD")` 未给 `sharding-count` 时按 `2` 处理。
- **缓存模式**（`cache(seconds) > 0`）：分片表匹配走 `TableCache`（TTL 内复用扫描结果）；`convert()` 时仍做一次全量扫描用于注册非分片表。
- **autoDiscover(false)**：跳过物理表发现；此时 `table()` 注册的分片表匹配不到物理表（告警跳过），仅 `tableTimeRange()` 的显式真实表清单仍会注册。
- **重复前缀**：多次注册相同表前缀会打印告警（日志提示后者将覆盖前者）。
- **INTERVAL 参数**：该算法需要 `datetime-lower`/`datetime-upper`/`datetime-partition-pattern`，缺失时告警。
- **异常**：物理数据源列表为空 → `IllegalArgumentException("至少需要一个实际数据源")`；ShardingSphere 创建失败 → `RuntimeException("创建 ShardingSphere 数据源失败", e)`。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-shardingv5-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.datasource.support.engine.DataSourceEnvironment;
import com.chua.shardingv5.support.conversion.ShardingV5Conversion;

// 1) 物理数据源由宿主自行准备（连接池等），此处省略
DataSource ds0 = ...;
DataSource ds1 = ...;

// 2) 链式注册分片规则并转换：t_order0..t_order15，按 order_id 取模（默认 MOD）
DataSource logic = new ShardingV5Conversion()
        .table("t_order", "order_id", 16)
        .convert(List.of(ds0, ds1), new DataSourceEnvironment());

// 3) 像普通 DataSource 一样使用，路由/改写/归并由 ShardingSphere JDBC 完成
try (Connection conn = logic.getConnection();
     PreparedStatement ps = conn.prepareStatement("SELECT * FROM t_order WHERE order_id = ?")) {
    ps.setLong(1, 1001L);
    try (ResultSet rs = ps.executeQuery()) {
        // ...
    }
}
```

### 3.3 常用变体

```java
// 自定义算法 + 分库 + 表缓存
DataSource logic = new ShardingV5Conversion()
        .algo("myMod", MyModAlgorithm.class)      // 自定义算法，需无参构造器
        .table("User", "user_id", 16, "myMod")
        .db("User", "tenant_id", 2, "MOD")        // 可选：按 tenant_id 分 2 库
        .cache(60)                                // 可选：表发现缓存 60 秒
        .convert(List.of(ds0, ds1), env);

// 内置算法 + 自定义参数（时间分片）
new ShardingV5Conversion()
        .algo("tm", "INTERVAL", Map.of("datetime-pattern", "yyyyMM", "datetime-lower", "2024-01-01"))
        .table("Order", "create_time", 12, "tm")
        .convert(List.of(ds), env);

// 显式时间范围挂载已有分表
new ShardingV5Conversion()
        .tableTimeRange("create_time", "2026-01-01", "2026-12-31",
                List.of("User_202601", "User_202602"))
        .convert(List.of(ds), env);
```

`DataSourceEnvironment`（来自 `utils-support-datasource-starter`）为 Lombok `@Data`，可设 `poolName`/`databaseType`/`schema`/`properties`；`poolName` 影响物理数据源命名（`<poolName>_0`、`<poolName>_1`…），不设置则为 `ds_0`、`ds_1`。

---

## 4. 配置项

**无。** 依据：本模块没有任何配置键绑定——源码中无 `@ConfigurationProperties` 等配置注解，`src/main/resources` 下仅存 SPI 声明文件与 GraalVM `native-image` 描述文件。所有行为参数均通过第 2 节的链式 API 在代码中给定，默认值为：`autoDiscover=true`、`cache=-1`（不缓存）、`table(...)` 缺省算法 `MOD`。

---

## 5. SPI 扩展点

本模块**不定义新的 SPI 接口**；它自身是数据源转换器 SPI 的一个实现：

- SPI 接口：`com.chua.datasource.support.engine.DataSourceConversion`（来自 `utils-support-datasource-starter`，`provided` 依赖）
- 注册文件：`src/main/resources/META-INF/extensions/com.chua.datasource.support.engine.DataSourceConversion`
- 注册内容：`SHARDINGV5=com.chua.shardingv5.support.conversion.ShardingV5Conversion`

两条扩展路径（均在调用方侧完成，不需改本模块）：

1. **自定义分片算法**：实现 `org.apache.shardingsphere.sharding.spi.ShardingAlgorithm`，通过 `.algo(name, MyAlgorithm.class)` 注册（CLASS_BASED 方式，实现类需提供无参构造器）。
2. **新增数据源转换器**：仿照本模块，实现 `DataSourceConversion` 接口，并在自己模块的 `src/main/resources/META-INF/extensions/com.chua.datasource.support.engine.DataSourceConversion` 中登记 `<key>=<实现类全限定名>`（同目录的 `utils-support-calcite-starter` 即 Calcite 联邦查询的另一个实现）。

---

## 6. 构建与依赖

### 6.1 依赖清单（pom.xml 实际声明）

| 依赖 | 范围 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | 工具与 SPI 基础设施 |
| `com.chua:utils-support-datasource-starter` | **provided** | 提供 `DataSourceConversion` / `DataSourceEnvironment` 契约，运行期由宿主提供 |
| `org.apache.shardingsphere:shardingsphere-jdbc:5.5.2` | compile | ShardingSphere JDBC 内核 |

### 6.2 构建

- 父模块：`com.chua:utils-support-datasource-parent`（聚合于 `utils-support-parent-starter`），版本 `4.0.0.51`。
- JDK：`25`（仓库根 POM `java.version=25`）。
- 编译验证命令（仓库交付口径）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 / 做法 |
| --- | --- |
| 连接池创建与管理 | 宿主自行准备物理 `DataSource` 后传入 `convert()`；本模块不建池、不管连接生命周期 |
| 物理分表 DDL | 不会自动创建 `t_order0..t_order15` 等物理表；只按前缀**发现**已存在的表 |
| Spring Boot 自动装配 / application 配置键 | 无；本模块是纯库模块，全部配置走链式 API |
| 读写分离、加密、脱敏、影子库等 ShardingSphere 规则 | 未装配；本模块只构建 `ShardingRuleConfiguration` 分片规则 |
| SQL 解析、路由、改写、结果归并 | 由 ShardingSphere JDBC 内核完成，本模块不解析 SQL |
| 跨库 JOIN / 联邦查询 | 不在本模块；同一父模块下的 `utils-support-calcite-starter` 面向 Calcite 联邦查询 |
| 分片算法本体 | 本模块只做注册与装配；算法实现来自 ShardingSphere 内置算法或调用方自定义类 |
