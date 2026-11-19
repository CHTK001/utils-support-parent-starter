# utils-support-chronicle-starter

> utils 体系中基于 **Chronicle Queue / Chronicle Map** 的中间件适配模块：提供预写日志（WAL）、进程内持久化 KV、持久化发布/订阅派发器、互斥锁与数据采集器五类 SPI 实现。所有契约接口（`WalLog`、`KvEngine`、`DispatcherProvider`、`LockProvider` 等）与工厂都定义在 `utils-support-common-starter`，本模块只做 Chronicle 后端实现。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.chronicle.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 规模](#13-规模)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 预写日志 WAL](#31-预写日志-wal)
  - [3.2 键值存储 KV](#32-键值存储-kv)
  - [3.3 发布/订阅派发器](#33-发布订阅派发器)
  - [3.4 互斥锁](#34-互斥锁)
  - [3.5 数据采集器](#35-数据采集器)
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
    <artifactId>utils-support-chronicle-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Chronicle Queue / Chronicle Map 的 SPI 适配层：WAL、KV、派发器、锁、采集器 | 不是消息中间件本身，不代理 Chronicle 的运维管理 |
| 形态 | 纯 Java SPI 实现库，接口与工厂均在 `utils-support-common-starter` | 不是 Spring Boot starter：无自动装配、无配置前缀、无 Spring 依赖 |
| WAL | `WalLog` 的 `chronicle` 实现（直接构造 `ChronicleWalLog`） | `WalFactory.open()` 当前不路由 `CHRONICLE`（见[边界](#7-边界这些能力不在本模块)） |
| KV | 持久化/内存 KV、原子计数、前缀扫描 | 不支持 TTL（Chronicle Map 3.27ea2 无条目级过期 API） |
| 锁 | JVM 内互斥（`ReentrantLock`） | 类注释所称「跨进程文件锁」在当前源码中未实现 |

### 1.3 规模

- Java 源文件 **5** 个（另有 2 个内部类：`ChronicleWalLog$ChronicleWalChain`、`ChronicleDispatcherProvider$ChronicleQueueSerializer`），全部标注 `@Spi("chronicle")`，`@since 4.0.0.42`。
- SPI 注册文件 **5** 个（`META-INF/services/` 3 个、`META-INF/extensions/` 2 个）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json` / `proxy-config.json` / `resource-config.json`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 预写日志：追加 / 回放 / checkpoint | `wal.ChronicleWalLog` | 实现 `com.chua.common.support.wal.WalLog`，直接 `new ChronicleWalLog(WalConfig)` |
| 分片管理与清理 | `wal.ChronicleWalLog#listSegments` / `#purgeCheckpointed` | 按 Chronicle cycle（`cycle.<hex>.cq4`）统计与删除 |
| 键值缓存 | `kv.ChronicleMapKv` | 实现 `KvEngine` / `KvOperations`，SPI 名 `chronicle` |
| 原子计数 | `kv.ChronicleMapKv#incr` | `ChronicleMap.compute` 条目级锁内读-改-写 |
| 前缀查询 | `kv.ChronicleMapKv#findAllByPrefix` | 线性扫描；空前缀返回全部 |
| 发布/订阅 | `dispatcher.ChronicleDispatcherProvider` | 实现 `DispatcherProvider`，Chronicle Queue 持久化 + Jackson JSON 序列化 |
| 互斥锁 | `lock.ChronicleLockProvider` | 继承 `AbstractLockProvider`，`tryLock` / `unlock` |
| 数据采集接入 | `collector.ChronicleActiveCollector` | 实现 datalake 的 `ActiveCollector`，协议名 `CHRONICLE` |

### 2.2 ChronicleWalLog（WAL）

```java
public ChronicleWalLog(WalConfig config)   // 唯一构造入口
```

| 方法 | 说明 |
| --- | --- |
| `long append(byte op, byte[] payload)` | 追加一条记录，返回自增 LSN |
| `long appendChain(WalChainHandler handler)` | 链式事务：回调内 `chain.add(op, payload / String / 无)`，返回最后一条 LSN |
| `WalReplayResult replay(WalReplayHandler handler)` | 从 checkpoint 之后回放；`handler.onRecord(lsn, op, payload)` 返回 `false` 中止 |
| `replay(long fromLsn, handler)` / `replay(long fromLsn, long toLsn, handler)` | 指定范围回放（左闭右开） |
| `long currentLsn()` | 当前最大 LSN |
| `CheckpointMeta loadCheckpoint()` / `void markCheckpoint(long lsn)` / `resetCheckpoint()` / `forceCheckpoint(long lsn)` | checkpoint 读取与推进（写入 `checkpointLsn` 专用文档） |
| `Optional<WalRecord> findByLsn(long lsn)` | 全队列扫描定位单条记录 |
| `List<WalSegmentInfo> listSegments()` / `WalSegmentInfo currentSegment()` | 按 cycle 还原分片列表（segmentNo 从 1 起，最大 cycle 为活跃分片） |
| `int purgeCheckpointed(int keepSegments)` | 删除「非活跃且全部记录 ≤ checkpoint」的 cycle 分片文件，保留最近 `keepSegments` 个 |
| `void sync()` | 空操作（Chronicle Queue 写入即持久化，仅校验未关闭） |
| `void close()` | 关闭队列，幂等；关闭后调用抛 `IllegalStateException` |

- 目录规则：`config.walDir()` 不为空时使用 `walDir/namespace`，否则退回 `${java.io.tmpdir}/wal/<namespace>`。
- 启动时全量扫描队列恢复 `currentLsn` 与最后一个 `checkpointLsn`。
- Wire 字段名：`lsn` / `op` / `payload` / `checkpointLsn`。

### 2.3 ChronicleMapKv（KV）

| 构造 | 说明 |
| --- | --- |
| `ChronicleMapKv()` | 内存型（名称 `chronicle-kv`，容量 10000） |
| `ChronicleMapKv(File file)` | 持久化到指定文件 |
| `ChronicleMapKv(Properties properties)` | SPI 入口，见[配置项](#4-配置项) |
| `ChronicleMapKv(ChronicleMap<String, String> map)` | 包装已有实例 |

实现 `KvEngine` 的 `get` / `put`（value 为 `null` 等效删除）/ `containsKey` / `delete` / `incr` / `findAllByPrefix`；`close()` 幂等，关闭后读写抛 `IllegalStateException`。TTL 相关方法（`put(key, value, Duration)` / `ttl` / `expire`）沿用接口默认实现，抛 `UnsupportedOperationException`，`supportsTtl()` 为 `false`。

### 2.4 ChronicleDispatcherProvider（发布/订阅）

构造 `ChronicleDispatcherProvider(DispatcherConfig config)`，实现 `DispatcherProvider` 的 `publish(topic, body)` / `subscribe(definition)` / `unsubscribe(definition)` / `close()`（`start()` 为接口默认空实现）。每个 topic 一条 Chronicle Queue；首个订阅者到达时以**虚拟线程**（线程名前缀 `chronicle-dispatcher-`）启动 tailer 消费循环；消息体经内置 `ChronicleQueueSerializer`（Jackson `ObjectMapper`）以 JSON 字节写入 wire 字段 `msg`。Chronicle 初始化失败时自动降级为内存队列投递并打印告警。

### 2.5 ChronicleLockProvider（锁）

构造 `ChronicleLockProvider()`（名称 `default`）或 `ChronicleLockProvider(String name)`。继承 `AbstractLockProvider` 后对外提供 `tryLock(int timeout, TimeUnit)`、`tryLock(int timeoutMillis)`、`unlock()`、`getName()`、`getType()`（返回 `chronicle`）、`close()`（等效 `unlock()`）。

### 2.6 ChronicleActiveCollector（采集器）

实现 `com.chua.datalake.support.spi.collection.ActiveCollector`：`protocol()` 返回 `CHRONICLE`，`defaultPort()` 返回 `0`；`start(int port)` 启动守护采集线程，`stop()` / `isRunning()` 控制生命周期；`registerMapping(topic, pipelineId)` / `unregisterMapping(topic)` / `getMappings()` / `subscribedTopics()` 维护 topic → pipeline 映射；`setHandler(DataHandler)` 注入处理器；`getStatus()` 返回协议、运行状态、映射数与 topic 集合；`simulateMessage(topic, payload)` 手动注入一条消息并交给 `DataHandler.handle(pipelineId, envelope)`。

---

## 3. 快速开始

### 3.1 预写日志 WAL

```java
import com.chua.chronicle.support.wal.ChronicleWalLog;
import com.chua.common.support.wal.WalConfig;

try (ChronicleWalLog wal = new ChronicleWalLog(WalConfig.builder()
        .walDir(Path.of("./data/wal"))
        .namespace("orders")
        .impl(WalConfig.WalImpl.CHRONICLE)
        .build())) {

    long lsn = wal.append((byte) 1, "hello".getBytes(StandardCharsets.UTF_8));

    wal.appendChain(chain -> {
        chain.add((byte) 1, "step-1");
        chain.add((byte) 2, "step-2".getBytes(StandardCharsets.UTF_8));
    });

    wal.replay((lsn0, op, payload) -> {
        // 逐条恢复；返回 false 中止回放
        return true;
    });

    wal.markCheckpoint(lsn);   // 业务持久化完成后推进 checkpoint
    wal.purgeCheckpointed(1);  // 清理已 checkpoint 的旧分片
}
```

> 注意：`WalFactory.open()` 当前只构建 `SIMPLE` / `SEGMENT` 两种实现，不会路由到 `CHRONICLE`；使用 Chronicle WAL 请直接 `new ChronicleWalLog(config)`。

### 3.2 键值存储 KV

```java
import com.chua.chronicle.support.kv.ChronicleMapKv;
import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.common.support.spi.ServiceProvider;

// 方式一：SPI（支持 file / name / entries / averageKeySize / averageValueSize 属性）
Properties props = new Properties();
props.setProperty("file", "./data/kv.dat");   // 省略则为内存型
KvEngine kv = ServiceProvider.of(KvEngine.class).getNewExtension("chronicle", props);

// 方式二：直接构造
try (ChronicleMapKv kv2 = new ChronicleMapKv(new File("./data/kv.dat"))) {
    kv2.put("user:1", "张三");
    kv2.incr("counter");                       // 原子 +1，返回最新值
    kv2.findAllByPrefix("user:");              // 前缀查询
}
```

### 3.3 发布/订阅派发器

```java
import com.chua.chronicle.support.dispatcher.ChronicleDispatcherProvider;
import com.chua.common.support.concurrent.dispatcher.ConsumerDispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.spi.ServiceProvider;

DispatcherProvider dispatcher = ServiceProvider.of(DispatcherProvider.class)
        .getNewExtension("chronicle", DispatcherConfig.builder()
                .dataPath("./data/dispatcher")     // 缺省退回 ${java.io.tmpdir}/chronicle
                .build());

dispatcher.subscribe(new ConsumerDispatcherDefinition<>(
        (String msg) -> System.out.println("收到: " + msg),
        String.class,
        List.of("trade")));

dispatcher.publish("trade", "hello");
// ...
dispatcher.close();
```

> 运行期 Chronicle 需要 JVM 放开相关模块（`--add-opens`），初始化失败时会抛出提示并自动回退内存队列。

### 3.4 互斥锁

```java
import com.chua.chronicle.support.lock.ChronicleLockProvider;

ChronicleLockProvider lock = new ChronicleLockProvider("order-lock");
if (lock.tryLock(1000)) {              // 也可 tryLock(1000, TimeUnit.MILLISECONDS)
    try {
        // 临界区
    } finally {
        lock.unlock();
    }
}
```

### 3.5 数据采集器

```java
import com.chua.chronicle.support.collector.ChronicleActiveCollector;

ChronicleActiveCollector collector = new ChronicleActiveCollector();
collector.setHandler(handler);                       // DataHandler 实现
collector.registerMapping("trade", "pipeline-1");    // topic → pipelineId
collector.start(0);                                  // 端口对 CHRONICLE 无意义，传 0
collector.simulateMessage("trade", "payload");       // 注入一条消息驱动 pipeline
collector.stop();
```

---

## 4. 配置项

本模块无 Spring 配置前缀（无 `application.properties` / `application.yml` 键）；配置均通过构造参数或 SPI `Properties` 传入。依据：`src/main/resources` 下仅有 `META-INF` 的 SPI 注册与 native-image 配置文件，模块依赖中不含 Spring。

### 4.1 KV（`ChronicleMapKv(Properties)`）

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `file` | 无（内存型） | 持久化文件路径；提供时调用 `ChronicleMapBuilder.createPersistedTo(file)` |
| `name` | `chronicle-kv` | Chronicle Map 名称 |
| `entries` | `10000` | 预估条目数 |
| `averageKeySize` | `32` | 键平均序列化字节数（变长 String 必须配置） |
| `averageValueSize` | `512` | 值平均序列化字节数 |

内部固定 `maxChunksPerEntry(128)` 以容纳远超均值的长值。

### 4.2 派发器（`DispatcherConfig`，本实现实际读取的字段）

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `dataPath` | `${java.io.tmpdir}/chronicle` | 队列根目录；实际队列为 `dataPath/<topic>` |
| `blockSize` | `64MB`（`64L * 1024 * 1024`） | mmap 块大小；`> 0` 时才设置到 builder |

`DispatcherConfig` 其余字段（`url` / `offsetPath` / `connectionTimeoutMillis` / `sessionTimeoutMillis` / `maxRetries` / `groupId` / `clientId` / `autoCommitOffset` / `maxBatchSize` / `serializer`）由通用配置模型携带，本实现未读取；消息序列化固定使用内置 Jackson。

### 4.3 WAL（`WalConfig` 相关项）

| 项 | 默认值 | 说明 |
| --- | --- | --- |
| `walDir` | `./wal` | 根目录；Chronicle 实际目录为 `walDir/<namespace>`，为 `null` 时退回 `${java.io.tmpdir}/wal/<namespace>` |
| `namespace` | `default` | 业务隔离子目录 |
| `impl` | `SEGMENT` | 传 `WalConfig.WalImpl.CHRONICLE` 标识 Chronicle 实现（枚举值由 common-starter 定义） |

`syncOnWrite` / `fsyncBatchSize` / `fsyncBatchIntervalMs` / `maxSegmentBytes` / `maxRecordsPerSegment` 等参数面向 common-starter 内置的 Simple/Segment 实现；Chronicle 实现的落盘由 Chronicle Queue 自身机制决定，`sync()` 为空操作。

### 4.4 JVM 参数

Chronicle 堆外实现需要 JDK 模块开放，模块 `pom.xml` 的 surefire `argLine` 已按此配置，应用运行期需保持一致：

```text
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.lang.ref=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.util.concurrent=ALL-UNNAMED
--add-opens java.base/java.io=ALL-UNNAMED
--add-exports java.base/jdk.internal.ref=ALL-UNNAMED
--add-exports java.base/sun.nio.ch=ALL-UNNAMED
--add-exports jdk.unsupported/sun.misc=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
```

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**；五个实现类的 SPI 名称均为 `chronicle`（`@Spi("chronicle")`）。注册文件清单：

| 契约接口 | 实现类 | 注册文件 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.kv.KvEngine` | `com.chua.chronicle.support.kv.ChronicleMapKv` | `META-INF/services/…KvEngine` |
| `com.chua.common.support.lang.datasource.kv.KvOperations` | `com.chua.chronicle.support.kv.ChronicleMapKv` | `META-INF/services/…KvOperations` |
| `com.chua.common.support.concurrent.lock.LockProvider` | `com.chua.chronicle.support.lock.ChronicleLockProvider` | `META-INF/services/…LockProvider` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `com.chua.chronicle.support.dispatcher.ChronicleDispatcherProvider` | `META-INF/extensions/…DispatcherProvider`（`chronicle=…`） |
| `com.chua.datalake.support.spi.collection.ActiveCollector` | `com.chua.chronicle.support.collector.ChronicleActiveCollector` | `META-INF/extensions/…ActiveCollector` |
| `com.chua.common.support.wal.WalLog` | `com.chua.chronicle.support.wal.ChronicleWalLog` | 无注册文件（见下方说明） |

获取方式：

```java
// KV：SPI 名 chronicle + Properties 构造参数
KvEngine kv = ServiceProvider.of(KvEngine.class).getNewExtension("chronicle", props);

// 派发器：SPI 名 chronicle + DispatcherConfig 构造参数
DispatcherProvider d = ServiceProvider.of(DispatcherProvider.class)
        .getNewExtension("chronicle", dispatcherConfig);

// WAL：@Spi 注解名为 chronicle，但未提供 META-INF 注册文件；
// WalFactory.open() 也不路由 CHRONICLE，因此请直接构造：
WalLog wal = new ChronicleWalLog(walConfig);
```

GraalVM native-image：`reflect-config.json` 已为 `ChronicleActiveCollector`、`ChronicleDispatcherProvider`、`ChronicleMapKv`、`ChronicleLockProvider` 四个类开放全部构造器/方法/字段反射；`ChronicleWalLog` 未包含在该配置中。

---

## 6. 构建与依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-chronicle-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM | 契约与 SPI 框架 |
| `net.openhft:chronicle-queue` | `5.27ea5`（父 POM dependencyManagement） | WAL、派发器底层 |
| `net.openhft:chronicle-map` | `3.27ea2` | KV 底层 |
| `com.fasterxml.jackson.core:jackson-databind` | 随父 POM | 派发器消息 JSON 序列化 |
| `com.chua:utils-support-fory-starter` | `${project.version}`，`optional` | 可选 |
| `com.chua:utils-support-datalake-sink-starter` | `${project.version}`，`scope=provided` | 采集器编译期依赖，运行期由宿主提供 |

- 父模块：`utils-support-middleware-parent`（Kafka、RabbitMQ、Redis、Chronicle 等中间件集成聚合）。
- 构建/测试需 JDK 25；surefire 已配置上文 `--add-opens` / `--add-exports` 参数。
- 编译命令：`mvn -o -q compile -DskipTests`（在模块或父 POM 目录执行）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 | 应去之处 |
| --- | --- | --- |
| `WalFactory.open()` 路由 Chronicle | 未实现：工厂只区分 `SIMPLE` / `SEGMENT`，传入 `CHRONICLE` 会落到 `SegmentWalLog`；`ChronicleWalLog` 也无 META-INF 注册文件 | 需 Chronicle WAL 时直接 `new ChronicleWalLog(config)` |
| KV 过期时间（TTL） | 不支持：`put(key, value, Duration)` / `ttl` / `expire` 抛 `UnsupportedOperationException`，`supportsTtl()` 为 `false` | 需 TTL 的 KV 用 `utils-support-redis-starter` 等后端 |
| 跨进程分布式锁 | 类注释声称「跨进程通过文件锁协调多 JVM」，但当前源码只有 JVM 内 `ReentrantLock`，无文件锁代码 | 跨进程锁用 Redis / ZooKeeper 等 starter 的 `LockProvider` |
| 采集线程自动消费队列 | `ChronicleActiveCollector` 的轮询线程目前为占位实现（循环休眠），不自动读取 Chronicle Queue；数据注入走 `simulateMessage` | 真正消费队列用 `ChronicleDispatcherProvider` |
| KV 的 `incr` 前缀扫描性能 | `findAllByPrefix` 为全键线性扫描，大容量场景性能受限 | 大规模前缀查询用 Redis 等支持索引的后端 |
| Spring Boot 自动装配 | 本模块无任何 Spring 依赖与自动配置 | 需要框架集成在上层工程完成 |
| 其它中间件（Kafka / RabbitMQ / Redis / Nats 等） | 不在本模块 | `utils-support-kafka-starter`、`utils-support-rabbitmq-starter`、`utils-support-redis-starter` 等 |
