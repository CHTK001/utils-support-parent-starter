# utils-support-debezium-starter

Debezium CDC 集成模块：基于 Debezium Engine 把数据库变更（insert/update/delete）适配为本仓库通用的目录轮询 SPI（`PolledDirectory` / `PolledListener`）。隶属于 `com.chua:utils-support-parent-starter`（当前版本 `4.0.0.51`，JDK 25），父模块为 `utils-support-datasource-parent`；代码位于三个包：`com.chua.debezium.support.environment`、`com.chua.debezium.support.directory`、`com.chua.datasource.support.config.debezium`。集成的 Debezium 版本为 `3.6.3.Final`。

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界（这些能力不在本模块）](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

| 维度 | 说明 |
|------|------|
| ✅ 是什么 | 基于 `DebeziumEngine`（JSON 格式）的 CDC 监听实现 `DebeziumPolledDirectory`，实现 `com.chua.common.support.lang.directory.PolledDirectory` |
| ✅ 是什么 | 通过 `META-INF/extensions/com.chua.common.support.lang.directory.PolledDirectory` 以 SPI 名 `debezium` 注册，可经 `ServiceProvider` 按名创建 |
| ✅ 是什么 | 环境配置构造器 `DebeziumEnvironment`（Builder 模式，内置 mysql/postgres/oracle/sqlserver/mongodb/mariadb 快捷入口与必填项校验） |
| ✅ 是什么 | 事件映射：Debezium 的 `"op":"c"/"u"/"d"` 分别回调 `PolledListener#onCreate / onModify / onDelete`，原始 JSON 通过 `EventObserver#source` 透传 |
| ✅ 是什么 | 运行前置检查与连接器配置的两个 SPI 扩展点：`DebeziumEnvironmentSetup`、`DebeziumConnectorConfig` |
| ❌ 不是什么 | 不是 Spring Boot 自动装配模块：资源目录下无自动配置类、无 `@ConfigurationProperties` 配置前缀 |
| ❌ 不是什么 | 不包含具体数据库连接器实现：仅声明 `debezium-api` / `debezium-embedded` 依赖，各数据库连接器（如 `io.debezium.connector.mysql.MySqlConnector` 所在模块）需自行引入 |
| ❌ 不是什么 | 不做事件数据加工/转换：仅从 JSON 中提取 `op` 与 `table` 两个字段用于分发，数据本体原样透传 |
| ❌ 不是什么 | 不管理 offset 存储：固定使用文件型 `org.apache.kafka.connect.storage.FileOffsetBackingStore` |

---

## 核心 API 与速查表

| 入口 | 说明 |
|------|------|
| `DebeziumEnvironment.mysql(name)` / `.postgres(name)` / `.oracle(name)` / `.sqlserver(name)` / `.mongodb(name)` / `.mariadb(name)` | 按连接器类型创建 Builder 的快捷入口 |
| `DebeziumEnvironment.builder()` | 通用 Builder（需自行 `connectorType(...)`） |
| `Builder.host / port / username / password / database` | 关系型连接参数 |
| `Builder.slotName / pluginName` | PostgreSQL 复制槽名 / 解码插件名 |
| `Builder.autoSetup(boolean)` / `Builder.property(key, value)` | 自动初始化开关 / 任意自定义属性 |
| `Builder.validate()` / `check()` / `build()` | 返回缺失必填键列表 / 校验失败抛 `IllegalArgumentException` / 构建并注入属性 |
| `ServiceProvider.of(PolledDirectory.class).getNewExtension("debezium", listenPath, env)` | 按 SPI 名创建 `DebeziumPolledDirectory`（构造参数：逻辑监听路径 + 环境） |
| `directory.addListener(PolledListener)` → `start(env)` → `close()` | 注册 c/u/d 回调 → 启动（事件驱动）→ 关闭引擎并释放线程 |

> 也可直接 `new DebeziumPolledDirectory(listenPath, environment)`，构造器为 public。

---

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-debezium-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最小可运行示例

```java
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.debezium.support.environment.DebeziumEnvironment;

// 1. 构建环境：build() 内部先 check()，缺必填项会抛 IllegalArgumentException
DirectoryPollerEnvironment env = DebeziumEnvironment.mysql("cdc-1")
        .host("localhost").port(3306).username("root").password("pass").database("mydb")
        .build();

// 2. 按 SPI 名 "debezium" 创建轮询器；listenPath 为逻辑监听路径（数据库名或表名）
PolledDirectory directory = ServiceProvider.of(PolledDirectory.class)
        .getNewExtension("debezium", "mydb", env);

// 3. 注册监听器：op=c/u/d 分别映射 CREATE/MODIFY/DELETE
directory.addListener(new PolledListener() {
    @Override
    public void onCreate(WatcherEvent event, EventObserver observer) {
        System.out.println("insert -> " + observer.getSource()); // 原始变更 JSON
    }

    @Override
    public void onModify(WatcherEvent event, EventObserver observer) {
        System.out.println("update -> " + observer.getSource());
    }

    @Override
    public void onDelete(WatcherEvent event, EventObserver observer) {
        System.out.println("delete -> " + observer.getSource());
    }
});

// 4. 启动：DebeziumPolledDirectory 为事件驱动（isDelegatedOperatingSystem()=true），
//    引擎运行在名为 "debezium-<listenPath>-%d" 的单条守护线程上
directory.start(env);

// 5. 停止：关闭引擎、中断执行线程并清空监听器
directory.close();
```

`EventObserver` 中与本模块相关的取值：`currentPath` = listenPath，`triggerFile` = 从 JSON 提取的表名（提取不到时回落为 listenPath），`source` = Debezium 原始 JSON 字符串。

---

## 配置项

### 环境属性键（写入 `DirectoryPollerEnvironment`）

| 键 | Builder 方法 | 说明 |
|----|--------------|------|
| `debezium.connector.name` | `connectorName(name)` | 连接器名（必填） |
| `debezium.connector.type` | `connectorType(type)` | 连接器类型（必填；同时是两个 SPI 的查找名） |
| `db.host` | `host(host)` | 数据库主机（非 MongoDB 必填） |
| `db.port` | `port(int)` / `port(String)` | 数据库端口 |
| `db.username` | `username(username)` | 数据库用户名（非 MongoDB 必填） |
| `db.password` | `password(password)` | 数据库密码 |
| `db.name` | `database(database)` | 数据库名 |
| `slot.name` | `slotName(slotName)` | PostgreSQL 复制槽名 |
| `plugin.name` | `pluginName(pluginName)` | PostgreSQL 解码插件名 |
| `debezium.auto.setup` | `autoSetup(boolean)` | 启动时是否经 `DebeziumEnvironmentSetup` SPI 自动初始化环境（默认 `false`） |
| 任意键 | `property(key, value)` | 自定义属性 |

`validate()` 的必填规则：`debezium.connector.type` 必填；`debezium.connector.name` 必填；类型非 `mongodb` 时 `db.host`、`db.username` 必填。

### Debezium Engine 属性（`DebeziumPolledDirectory#start` 内部固定/推导）

| 属性 | 取值 |
|------|------|
| `name` | `debezium-<listenPath>` |
| `offset.storage` | `org.apache.kafka.connect.storage.FileOffsetBackingStore` |
| `offset.storage.file.filename` | 环境键 `offset.storage.file.filename`，默认 `debezium-offset.dat` |
| `offset.flush.interval.ms` | `1000` |
| `database.server.name` | listenPath |
| `topic.prefix` | listenPath |
| `connector.class` | 优先取环境键 `connector.class`；否则按 `debezium.connector.type` 经 `DebeziumConnectorConfig` SPI 获取；都无则回落 `io.debezium.connector.mysql.MySqlConnector` |

未命中 SPI 且未指定 `connector.class` 时，按以下默认映射补齐 MySQL 属性：`database.hostname`←`db.host`(默认 `localhost`)、`database.port`←`db.port`(默认 `3306`)、`database.user`←`db.username`(默认 `root`)、`database.password`←`db.password`(默认空串)、`database.include.list`←`db.name`(默认 listenPath)。

### 属性透传前缀

环境中以下前缀开头的属性会原样写入 Debezium 属性：`database.`、`snapshot.`、`tombstones.`、`key.`、`offset.`、`heartbeat.`。

### Spring Boot 配置项

**无。** 依据：本模块 `src/main/resources` 下仅有 `META-INF/extensions/...` 与 `META-INF/native-image/...` 文件，无 `spring.factories`、`AutoConfiguration.imports` 或 `@ConfigurationProperties` 元数据；pom 亦未依赖 Spring Boot。

---

## SPI 扩展点

本模块对外提供两个 SPI 接口，均通过 `ServiceProvider.of(...).getExtension(connectorType)` 按 `debezium.connector.type` 的值查找实现：

| SPI 接口 | 方法 | 作用 |
|----------|------|------|
| `com.chua.datasource.support.config.debezium.DebeziumEnvironmentSetup` | `isReady(env)` / `setup(env)` | 启动时判断运行前置条件是否就绪；未就绪时执行自动初始化（仅当 `debezium.auto.setup=true` 时被调用） |
| `com.chua.datasource.support.config.debezium.DebeziumConnectorConfig` | `connectorClass()` / `configure(props, env)` | 返回连接器实现类全限定名，并把环境属性映射为 Debezium 属性 |

同时，本模块自身作为 SPI 提供方注册到 `PolledDirectory` 体系：

- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.directory.PolledDirectory`
- 注册内容：`debezium=com.chua.debezium.support.directory.DebeziumPolledDirectory`

> 注意：本模块**未提供** `DebeziumEnvironmentSetup` 与 `DebeziumConnectorConfig` 的任何实现类（源码中只有接口定义），实现由上层模块或使用方按需注册；未注册时启动流程会跳过环境自动配置与 SPI 连接器映射（并可能走 MySQL 默认回落）。

---

## 构建与依赖

```
utils-support-debezium-starter
├── com.chua:utils-support-common-starter        # PolledDirectory/PolledListener/EventObserver/SPI 等契约
├── com.chua:utils-support-datasource-starter    # provided，仅编译期
├── io.debezium:debezium-api        3.6.3.Final
└── io.debezium:debezium-embedded   3.6.3.Final  # DebeziumEngine 运行时
```

编译（与本仓库其他模块一致）：

```bash
mvn -o -q compile -DskipTests
```

模块已附带 GraalVM native-image 配置：`META-INF/native-image/com.chua/utils-support-debezium-starter/` 下的 `reflect-config.json`（注册 `DebeziumPolledDirectory` 全量反射）与 `resource-config.json`（打包 `META-INF/extensions/.*` 资源）。

---

## 边界（这些能力不在本模块）

- **Spring Boot 自动装配**：无自动配置类、无配置前缀，属纯 SPI/工具模块。
- **具体数据库连接器**：mysql/postgres/oracle/sqlserver/mongodb 等连接器 jar 不在本模块依赖中，需使用方按目标数据库自行引入对应 Debezium 连接器模块。
- **环境初始化与连接器映射的内置实现**：`DebeziumEnvironmentSetup`、`DebeziumConnectorConfig` 仅有接口，无内置实现。
- **事件数据加工**：不做 JSON 解析成实体、不做字段过滤/脱敏/转换，仅透传原始 JSON。
- **offset 的非文件存储**：offset 固定走 `FileOffsetBackingStore`，不提供 Kafka/数据库 offset 存储切换。
- **多表路由、重试、幂等、死信**等 CDC 消费治理能力：源码未提供。
