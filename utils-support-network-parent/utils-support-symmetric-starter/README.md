# utils-support-symmetric-starter

> utils 体系的 **SymmetricDS 数据库同步集成**：把 SymmetricDS 的触发器式 CDC 引擎适配为 common-starter 的 `PolledDirectory` 轮询目录（SPI 名 `symmetric`），并提供类型安全的 `SymmetricEnvironment` 环境配置 Builder。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.symmetric.support`

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

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | SymmetricDS 3.17.9 的集成适配层：CDC 引擎 → `PolledDirectory` | 不是数据同步框架本身（那是 SymmetricDS 的事） |
| API | `SymmetricEnvironment` Builder 生成 `DirectoryPollerEnvironment` | 不提供 Spring Boot 自动装配，没有任何 Spring 配置键 |
| 实现 | `SymmetricPolledDirectory`（SPI 名 `symmetric`），内部基于 `ClientSymmetricEngine` | 不含各数据库的 `SymmetricConnectorConfig` / `SymmetricEnvironmentSetup` 实现（运行期按 dbType 走 SPI 查找） |
| 依赖 | 只依赖 `utils-support-common-starter` + `utils-support-datasource-starter`（provided）+ `symmetric-core` / `symmetric-client` | 不依赖任何上层业务 starter |

工作方式（均来自源码）：`start()` 时若 `symmetric.auto.setup=true`，先按 `symmetric.db.type` 通过 SPI 调 `SymmetricEnvironmentSetup` 做环境配置；然后组装 SymmetricDS `Properties`（数据库连接按 dbType 走 SPI 连接器 `SymmetricConnectorConfig`，未命中时走通用 JDBC 拼接），创建 `ClientSymmetricEngine` 并在后台线程启动；按 `symmetric.table.include.list` 注册 TriggerRouter；随后每 5 秒轮询 outgoing batch，把状态为 `OK` 的 batch 以 `MODIFY` 事件分发给已注册的 `PolledListener`（`triggerFile` 取 channel id，无则用 `batch-<batchId>`，`source` 为该 `OutgoingBatch`）。

### 支持的数据库类型（SymmetricEnvironment 预置工厂）

| 数据库 | 工厂方法 | symmetric.db.type | 支持版本（源码注释） |
| --- | --- | --- | --- |
| MySQL | `SymmetricEnvironment.mysql(engineName)` | `mysql` | 5.x/8.x/9.x |
| MariaDB | `SymmetricEnvironment.mariadb(engineName)` | `mariadb` | 10.x/11.x |
| PostgreSQL | `SymmetricEnvironment.postgres(engineName)` | `postgres` | 9.5+ |
| Oracle | `SymmetricEnvironment.oracle(engineName)` | `oracle` | 11g/12c/19c/21c |
| SQL Server | `SymmetricEnvironment.sqlserver(engineName)` | `sqlserver` | 2005/2008/2012/2014/2016/2017/2019/2022 |
| DB2 | `SymmetricEnvironment.db2(engineName)` | `db2` | 10.x/11.x |
| Informix | `SymmetricEnvironment.informix(engineName)` | `informix` | 12/14 |
| H2 | `SymmetricEnvironment.h2(engineName)` | `h2` | 1.4.x/2.x |
| Derby | `SymmetricEnvironment.derby(engineName)` | `derby` | 10.x |
| Firebird | `SymmetricEnvironment.firebird(engineName)` | `firebird` | 3.x/4.x |
| Sybase ASE | `SymmetricEnvironment.ase(engineName)` | `ase` | 15.x/16.x |
| SQL Anywhere | `SymmetricEnvironment.sqlanywhere(engineName)` | `sqlanywhere` | 16.x/17.x |
| Vertica | `SymmetricEnvironment.vertica(engineName)` | `vertica` | 10.x/11.x |
| ClickHouse | `SymmetricEnvironment.clickhouse(engineName)` | `clickhouse` | 22+ |

> 以上版本号为源码 javadoc 中标注的「SymmetricDS 官方支持的数据库类型」，非本模块实测结论。

---

## 2. 核心 API 与速查表

本模块只有两个公共类：`com.chua.symmetric.support.environment.SymmetricEnvironment` 与 `com.chua.symmetric.support.directory.SymmetricPolledDirectory`。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 构建 MySQL 同步环境 | `SymmetricEnvironment.mysql("engine-name").host(...).username(...).password(...).database(...).build()` | 返回 `DirectoryPollerEnvironment` |
| 其它数据库环境 | `SymmetricEnvironment.postgres/oracle/sqlserver/mariadb/db2/informix/h2/derby/firebird/ase/sqlanywhere/vertica/clickhouse(engineName)` | 同一 Builder，仅 dbType 不同 |
| 通用 Builder | `SymmetricEnvironment.builder()` | 从零开始配 `engineName(...)` / `dbType(...)` |
| 获取 CDC 轮询器 | `ServiceProvider.of(PolledDirectory.class).getExtension("symmetric")` | SPI 名 `symmetric` |
| 注册变更监听 | `directory.addListener(PolledListener)` | 用 `SimplePolledListener` 按需覆写回调 |
| 启动 / 停止 | `directory.start(env)` / `directory.close()` | 实现了 `AutoCloseable` |
| 透传自定义属性 | `Builder.property(key, value)` | 写入环境，供 SPI 连接器实现读取 |
| 调整轮询间隔与订阅事件 | `Builder.pollingInterval(long, TimeUnit)` / `Builder.events(WatcherEvent...)` | 默认 10 秒；默认订阅 CREATE/MODIFY/DELETE |

### 2.1 SymmetricEnvironment.Builder 方法

| 方法 | 写入的键 / 行为 |
| --- | --- |
| `engineName(String)` | `symmetric.engine.name`（必填） |
| `dbType(String)` | `symmetric.db.type` |
| `groupId(String)` | `symmetric.group.id` |
| `externalId(String)` | `symmetric.external.id` |
| `registrationUrl(String)` | `symmetric.registration.url` |
| `syncUrl(String)` | `symmetric.sync.url` |
| `host(String)` | `db.host` |
| `port(String)` / `port(int)` | `db.port` |
| `username(String)` | `db.username` |
| `password(String)` | `db.password` |
| `database(String)` | `db.name` |
| `tableIncludeList(String)` | `symmetric.table.include.list`（逗号分隔） |
| `tableExcludeList(String)` | `symmetric.table.exclude.list` |
| `autoCreateTables(boolean)` | `symmetric.auto.create.tables` |
| `initialLoad(boolean)` | `symmetric.initial.load` |
| `autoRegister(boolean)` | `symmetric.auto.register` |
| `autoSetup(boolean)` | `symmetric.auto.setup` |
| `property(String, String)` | 任意自定义键值 |
| `pollingInterval(long, TimeUnit)` | 轮询间隔，默认 `10 SECONDS` |
| `events(WatcherEvent...)` | 订阅事件，默认 `CREATE, MODIFY, DELETE` |
| `build()` | 生成 `DirectoryPollerEnvironment`（属性全部写入其中） |

### 2.2 SymmetricPolledDirectory 生命周期

| 方法 | 行为 |
| --- | --- |
| `new SymmetricPolledDirectory(listenPath, environment)` | `listenPath` 为逻辑路径，仅用于事件 `currentPath` 与日志 |
| `addListener(PolledListener)` | 追加监听器（CopyOnWriteArrayList） |
| `start(env, executor)` | 防重复启动；`executor` 参数不使用（引擎自带后台线程） |
| `start(env)` | 接口 default 方法：因 `isDelegatedOperatingSystem()=true`，等价于 `start(env, null)` |
| `isDelegatedOperatingSystem()` | 恒返回 `true`（引擎内部自调度，不走通用轮询执行器） |
| `upgrade()` | 空实现（SymmetricDS 引擎内部自动处理） |
| `close()` | 停止引擎、关闭线程池、清空监听器 |

---

## 3. 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-symmetric-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- SymmetricPolledDirectory 按编译期约定使用以下 provided 依赖，
     宿主需自行引入 utils-support-datasource-starter 与数据库驱动 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasource-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> 注意：本模块的 pom 对 `utils-support-datasource-starter` 声明为 `<scope>provided</scope>`，即由宿主提供。构建本模块自身时也会用到该依赖。

### 2. 最小可运行示例（MySQL → 监听表变更）

```java
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.SimplePolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.symmetric.support.environment.SymmetricEnvironment;

public class SymmetricDemo {

    public static void main(String[] args) {
        // 1. 构建环境：engine 名 + 节点身份 + 数据库连接 + 要同步的表
        DirectoryPollerEnvironment env = SymmetricEnvironment.mysql("sym-node-1")
                .groupId("store")
                .externalId("node-1")
                .host("localhost").port(3306)
                .username("root").password("pass")
                .database("mydb")
                .tableIncludeList("mydb.orders,mydb.customers")
                .autoSetup(true)          // 启动前按 dbType 走 SPI 自动配置数据库环境
                .build();

        // 2. 通过 SPI 拿到 SymmetricDS 轮询目录实现
        PolledDirectory directory = ServiceProvider.of(PolledDirectory.class).getExtension("symmetric");

        // 3. 注册监听器：数据变更以 MODIFY 事件到达
        directory.addListener(new SimplePolledListener() {
            @Override
            public void onModify(WatcherEvent event, EventObserver observer) {
                System.out.println("数据变更: path=" + observer.getCurrentPath()
                        + ", trigger=" + observer.getTriggerFile()
                        + ", source=" + observer.getSource());   // source 为 SymmetricDS OutgoingBatch
            }
        });

        // 4. 启动（后台线程拉起 SymmetricDS 引擎）；停止时 close()
        directory.start(env);
        Runtime.getRuntime().addShutdownHook(new Thread(directory::close));
    }
}
```

要点：

- `symmetric.engine.name` 必填，缺失时启动抛 `IllegalArgumentException`；
- 通用连接路径下 `db.username` / `db.password` 必填；
- 不配 `symmetric.table.include.list` 时不会监控任何表；
- `symmetric.initial.load=true` 时启动后执行一次初始全量加载。

---

## 4. 配置项

本模块**没有** Spring Boot 自动装配，也没有 `application.yml` 键；所有配置都是程序化的：通过 `SymmetricEnvironment.Builder` 写入 `DirectoryPollerEnvironment` 属性，再由 `SymmetricPolledDirectory` 读取。键名与默认值如下（全部来自源码）：

| 键 | Builder 方法 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `symmetric.engine.name` | `engineName` | 无（必填） | SymmetricDS 引擎名，映射为 `engine.name`；`external.id` 未设置时也用它充当 |
| `symmetric.db.type` | `dbType` / 各数据库工厂 | 空串 | SPI 查找连接器与环境配置器的键；为空或未命中时走通用 JDBC 拼接 |
| `symmetric.group.id` | `groupId` | `default` | 映射为 `group.id`，同时用于 TriggerRouter 的 NodeGroupLink |
| `symmetric.external.id` | `externalId` | 取 engine.name | 映射为 `external.id` |
| `symmetric.registration.url` | `registrationUrl` | 不设置 | 非空才写入 `registration.url` |
| `symmetric.sync.url` | `syncUrl` | 不设置 | 非空才写入 `sync.url` |
| `db.host` | `host` | `localhost` | 通用连接路径使用 |
| `db.port` | `port` | 空串 | 通用连接路径使用 |
| `db.username` | `username` | 无（通用路径必填） | 通用路径映射为 `db.user` |
| `db.password` | `password` | 无（通用路径必填） | 映射为 `db.password` |
| `db.name` | `database` | 空串 | 通用路径拼接进 `db.url` |
| `symmetric.table.include.list` | `tableIncludeList` | 空串 | 逗号分隔的 `schema.table`；为空则不注册任何触发器路由 |
| `symmetric.table.exclude.list` | `tableExcludeList` | — | 键可设置；引擎侧逻辑只读取 include 列表 |
| `symmetric.auto.create.tables` | `autoCreateTables` | `true` | 同时写入 `auto.create.tables` / `auto.create.sym.tables` / `auto.upgrade` |
| `symmetric.initial.load` | `initialLoad` | `false` | `true` 时启动后执行初始全量加载 |
| `symmetric.auto.register` | `autoRegister` | `true` | 映射为 `auto.register` |
| `symmetric.auto.setup` | `autoSetup` | `false` | `true` 时按 dbType 走 SPI 调 `SymmetricEnvironmentSetup.setup(...)` |

Builder 侧默认（不落入 SymmetricDS 属性）：`pollingInterval=10`、`timeUnit=SECONDS`、`events={CREATE, MODIFY, DELETE}`。

引擎侧固定开启的任务（源码硬编码，不受配置控制）：`start.pull.job`、`start.push.job`、`start.route.job`、`start.purge.job`、`start.synctriggers.job`、`start.heartbeat.job`、`start.watchdog.job` 均为 `true`。

---

## 5. SPI 扩展点

### 5.1 本模块对外注册的 SPI

`src/main/resources/META-INF/extensions/com.chua.common.support.lang.directory.PolledDirectory`：

```text
symmetric=com.chua.symmetric.support.directory.SymmetricPolledDirectory
```

即在任何使用 `ServiceProvider.of(PolledDirectory.class)` 的地方，用名字 `symmetric` 即可取到本实现。

### 5.2 本模块消费的 SPI（扩展入口）

| SPI 接口 | 所在模块 | 查找键 | 调用时机 | 未命中时 |
| --- | --- | --- | --- | --- |
| `com.chua.datasource.support.config.symmetric.SymmetricConnectorConfig` | `utils-support-datasource-starter` | `symmetric.db.type` | `start()` 组装 SymmetricDS `Properties` 时，`configure(props, environment)` | 走通用 JDBC 拼接：`db.driver=""`、`db.url="jdbc:"+host+":"+port+"/"+database`、`db.user`、`db.password` |
| `com.chua.datasource.support.config.symmetric.SymmetricEnvironmentSetup` | `utils-support-datasource-starter` | `symmetric.db.type` | `symmetric.auto.setup=true` 时，`setup(environment)` | 打印警告并跳过自动配置 |

> 这两个接口本模块**只消费、不提供实现**；截至当前仓库，也没有任何模块提供实现类。宿主如需定制某数据库的连接参数或自动建库逻辑，实现这两个接口并按 dbType 注册即可。

### 5.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-symmetric-starter/`：

- `reflect-config.json`：注册 `SymmetricPolledDirectory` 的全部构造器/方法/字段反射；
- `resource-config.json`：包含 `META-INF/extensions/.*`，保证 SPI 注册文件进原生镜像。

---

## 6. 构建与依赖

```xml
<parent>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-network-parent</artifactId>
    <version>4.0.0.51</version>
</parent>
<artifactId>utils-support-symmetric-starter</artifactId>
<name>Utils Support SymmetricDS Starter</name>
<description>SymmetricDS 数据库同步集成</description>
```

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 pom | `PolledDirectory`、`DirectoryPollerEnvironment`、`ServiceProvider` 等契约 |
| `com.chua:utils-support-datasource-starter` | `${project.version}`，`provided` | `SymmetricConnectorConfig` / `SymmetricEnvironmentSetup` SPI 接口 |
| `org.jumpmind.symmetric:symmetric-core` | `3.17.9` | SymmetricDS 内核 |
| `org.jumpmind.symmetric:symmetric-client` | `3.17.9` | `ClientSymmetricEngine` |

构建注意：

- SymmetricDS 依赖来自 **JumpMind Maven 仓库**：`https://maven.jumpmind.com/repo/`（pom 中已配置，仅 releases）；
- 本模块 `dependencyManagement` 将传递依赖的 `log4j-core` / `log4j-api` / `log4j-slf4j2-impl` / `log4j-web` 统一覆盖为 `2.26.1`，并将 `mssql-jdbc` 覆盖为 `13.6.0.jre11`；
- 编译遵循仓内统一口径：`maven.compiler.release=25`（JDK 25）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 / 说明 |
| --- | --- |
| 各数据库的连接器实现（`SymmetricConnectorConfig`） | 本模块只按 dbType 做 SPI 查找；仓库内当前没有实现类，通用路径走 `jdbc:host:port/database` 拼接 |
| 数据库环境自动配置实现（`SymmetricEnvironmentSetup`） | 同上，`auto.setup` 未命中实现时仅告警跳过 |
| Spring Boot 自动装配 / `application.yml` 配置键 | 源码未提供；接入完全靠程序化 Builder + SPI |
| 数据同步本身的调度、传输、冲突解决 | 由 SymmetricDS 引擎负责，本模块只是启动它并转发 batch 事件 |
| Binlog / WAL 级 CDC | 源码注释明确：本实现基于触发器，适合 Debezium 不支持的数据库版本 |
| 目录/文件监听 | common-starter 的 `DirectoryWatcher`（WatchService）等，本模块不涉及 |
