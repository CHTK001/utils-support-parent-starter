# utils-support-redis-starter

**一句话定位**：`utils-support-parent-starter` 体系中的 Redis 中间件模块（`com.chua:utils-support-redis-starter:4.0.0.51`，包名 `com.chua.redis.support`，JDK 25）。它在 **Jedis（同步/管理面）、Lettuce（响应式）、Redisson（锁/限流/订阅/发现/去重）** 三个客户端之上，提供同步 KV、响应式 KV、SQL 化查询（RediSearch）、时序、全文检索索引、分布式锁、限流、发布订阅、服务发现、配置中心、分布式去重与嵌入式 Redis 服务器等能力，并以 SPI 形式接入 common 层的十余个扩展点。

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界与注意事项](#边界与注意事项)

## 它是什么 / 不是什么

| 它是 ✅ | 说明 |
|---|---|
| 多客户端 Redis 统一封装 | Jedis（`RedisClient`、`RedisClientProvider`、`RedisConfigCenter`、`RediSearchEngine`）、Lettuce（`RedisReactorEngine`、`RedisSeriesEngine`）、Redisson（锁、限流、发布订阅、服务发现、去重） |
| common / datasource / datalake 层 SPI 的 redis 实现 | `KvEngine`、`Engine`、`ReactorEngine`、`SeriesEngine`、`SearchEngine`、`LockProvider`、`RateLimiterProvider`、`DispatcherProvider`、`ServiceDiscovery`、`ConfigCenter`、`DataSink`、`MiddlewareClientProvider`、`RedisCommandHandler` |
| 嵌入式 Redis 服务器封装 | `RedisServer` / `EmbeddedRedisServer`，用于本地开发与测试（依赖为 provided，生产不含） |
| Redis 命令处理器 SPI | `RedisCommandHandler` + `GET`/`SET`/`DEL`/`HSET`/`EXPIRE`/`INCR` 六个实现，按命令名（大写）加载 |

| 它不是 ❌ | 说明 |
|---|---|
| 不是 Spring Boot 自动配置 starter | `src/main/resources` 下仅有 `META-INF/services`、`META-INF/extensions`、`META-INF/native-image`，没有 `spring.factories` / `AutoConfiguration.imports` / `@ConfigurationProperties`；所有连接参数通过构造器或 Builder 传入 |
| 不是 Redis 客户端本身 | 只封装 Jedis / Lettuce / Redisson，不重复实现协议与连接池 |
| 不是运维/管理平台 | 管理面（`RedisClientProvider`）**只读**，不提供 DEL / FLUSHDB / CONFIG SET 等写操作 |
| 不是集群拓扑管理工具 | 构造器均按单节点地址（`redis://host:port`）建连，不支持在模块内声明哨兵/集群拓扑 |

## 核心 API 与速查表

### 速查表（最常查的 8 个入口）

| 能力 | 入口类（`com.chua.redis.support.*`） | SPI 键 | 最常用方法 |
|---|---|---|---|
| 同步 KV | `client.RedisClient` | `redis`（`KvEngine`/`KvOperations`） | `get` / `put` / `put(key,value,ttl)` / `delete` / `incr` / `ttl` / `expire` / `findAllByPrefix` |
| 响应式 KV / 命令 | `engine.RedisReactorEngine` | `redis`（`ReactorEngine`） | `addDataSource(name, url)` / `get` / `set` / `setex` / `hget` / `hset` / `scanKeys` / `execCommand` / `execBatch` |
| 分布式锁 | `lock.RedissonLockProvider` | `redis`（`LockProvider`） | `tryLock(timeout, unit)` / `unlock()` |
| 限流 | `rate.RedisRateLimiterProvider` | `redis`（`RateLimiterProvider`） | `tryAcquire()` / `tryAcquire(timeout, unit)` |
| 发布订阅 | `dispatcher.RedissonDispatcherProvider` | `redis`（`DispatcherProvider`） | `publish(topic, body)` / `subscribe(definition)` / `unsubscribe(definition)` |
| 服务发现 | `discovery.RedisServiceDiscovery` | `redis`（`ServiceDiscovery`） | `start()` / `registerService(path, discovery)` |
| 配置中心 | `config.RedisConfigCenter` | `redis`（`ConfigCenter`） | `start()` / `get(dataId)` |
| 嵌入式 Redis | `server.RedisServer`、`server.EmbeddedRedisServer` | 非 SPI | `create()` / `create(port)` / `builder()...build()` / `start()` / `stop()` |

### SQL 化查询（RediSearch）

- `engine.RediSearchEngine`：`@Spi("redis")`，实现 `Engine`，继承 `RedisEngine`。基于 RediSearch 的 `FT.SEARCH` 实现实体查询、更新、删除，并支持全量扫描回退机制；`store(name, list)` 按 getter 反射把实体写成 Hash（键为 `name:id`）；支持 `addDataSource(name, EngineDataSource)`、`setDefaultDataSourceName(name)`。
- `engine.RediSearchQueryConverter`：把 SQL `WHERE` 子句（`AND`/`OR`/`NOT`/`BETWEEN`/`LIKE`/`IN`/`IS NULL`/比较运算符）转换为 RediSearch `FT.SEARCH` 查询语法。
- `engine.RedisEngine`：Jedis 连接池与数据源管理基类，**不实现** `Engine` 接口，仅作为 `RediSearchEngine` 的基类。
- `engine.SimpleRedisDataSource`：`EngineDataSource<JedisPool>` 的简单实现。

### 时序（监控指标）

- `engine.RedisSeriesEngine`：`@Spi("redis")`，实现 `SeriesEngine`。每个指标一条 ZSET（member=`值:时间戳`，score=时间戳），键为 `monitor:metric:<target>:<monitorId>:<metricName>`，并用 SET `monitor:metrics:<target>:<monitorId>` 维护指标名索引。主要方法：`writePoint(target, monitorId, metricName, value, timestamp)`、`metrics(target, monitorId)`、`series(target, monitorId, metricName, hours, window)`、`purge(...)`、`isAvailable()`、`close()`。单指标上限 100_000 点，单次曲线最多 1440 点。

### 全文检索（需 Redis Stack 的 RediSearch 模块）

- `meta.RedisSearchEngineImpl`：`SearchEngine` SPI 的 `redis` 实现（Jedis 直连执行 RediSearch 命令）。
- `meta.RedisSearchMeta`：基于 `FT.CREATE ... ON HASH ... SCHEMA` 建索引；RediSearch 写入即建索引、无 refresh/optimize 命令，故 `refresh`/`optimize` 仅做索引存在性校验。
- `meta.RedisSearchMetaData`：元数据入口（继承 `DefaultMetaData`）。
- `sink.RedisSearchSink`：`@Spi("redis-search")`，实现 `DataSink`。为每条 topic 建立全文索引（索引前缀 `idx:ds:`，文档键前缀 `ds:`）；默认 `127.0.0.1:6379`，host/port/password 可由 setter 覆盖。

### 其他能力

- `deduplicate.RedisDeduplicator`：`@Spi("redis")`，实现 `Deduplicator`。基于 Redisson `RBucket` 的 `SETNX + TTL` 语义做分布式幂等去重；键前缀 `dedup:`，默认 TTL 5 分钟；`isDuplicate(key)` / `markProcessed(key)`；`clear()` 为空实现、`size()` 恒为 0（Redis 不支持按前缀原子批量删除）。
- `spi.RedisClientProvider`：`@Spi(value="redis", order=200)`，实现 `MiddlewareClientProvider`（displayName「Redis 缓存」，defaultPort 6379）。**只读管理面**：`dbsize` / `info` / `clients` / `slowlog` / `scan` / `type` / `ttl` / `get`；扫描每批 100 键、最多返回 500 键、超时 5s；不做 DEL / FLUSHDB / CONFIG SET。
- `command.RedisCommandHandler`：命令处理器 SPI 接口（`command()` 返回大写命令名，`execute(Jedis, List<String> args)` 返回「受影响行数」语义——写命令返回受影响数量、读命令返回命中数量 0/1）。实现类：`GetCommandHandler`(`GET`)、`SetCommandHandler`(`SET`)、`DelCommandHandler`(`DEL`)、`HsetCommandHandler`(`HSET`)、`ExpireCommandHandler`(`EXPIRE`)、`IncrCommandHandler`(`INCR`，返回递增后的新值)。通过 `ServiceProvider` 按命令名加载。
- `datasource.RedisDataTable`：实现 `DataTable`，把 Redis Hash 映射为表格（每个键一行、Hash 字段为列，自动附加 `_key` 列），按 key 模式（如 `user:*`）SCAN 加载（每批 100），**只读**，用于 Calcite 查询聚合。
- `engine.RedisReactiveKvEngine`：`RedisReactorEngine` 的响应式 KV 便捷封装（**非 SPI**，`KvEngine` 接口为同步）；所有操作经 `boundedElastic` 调度执行；Redis 不可用或解码失败时错误沿 `Mono`/`Flux` 传播，不降级为空结果。

## 快速开始

依赖：一个可用的 Redis（嵌入式示例见下），JDK 25。

```java
// 1. 同步 KV（RedisClient 实现 KvEngine）
RedisClient client = RedisClient.builder()
        .host("127.0.0.1")
        .port(6379)
        .build();

client.put("user:1", "alice");
client.get("user:1");                                 // "alice"
client.put("token:abc", "1", Duration.ofSeconds(60)); // 带 TTL 写入
client.incr("counter");                               // 1
client.findAllByPrefix("user:");                      // {user:1=alice}
client.close();
```

```java
// 2. 响应式 KV 与命令（Lettuce）
RedisReactorEngine engine = new RedisReactorEngine()
        .addDataSource("default", "redis://127.0.0.1:6379");

engine.set("rk", "v").block();
engine.get("rk").block();                    // "v"
engine.hset("h", "f", "1").block();
engine.scanKeys("r*").collectList().block(); // SCAN 游标扫描，上限 500 键
engine.close();
```

```java
// 3. 分布式锁（Redisson，看门狗自动续期、可重入）
RedissonLockProvider lock = new RedissonLockProvider("order-lock", "redis://127.0.0.1:6379");
if (lock.tryLock(5, TimeUnit.SECONDS)) {
    try {
        // 业务逻辑
    } finally {
        lock.unlock();
    }
}
lock.close();
```

```java
// 4. 本地开发/测试：嵌入式 Redis（provided 依赖，需本地 redis-server 可执行文件）
RedisServer redis = RedisServer.create(6380);   // 或 RedisServer.builder().port(6380).maxMemory("256mb").build()
redis.start();
// ... 用 redis://127.0.0.1:6380 连接测试 ...
redis.stop();
```

## 配置项

**无 Spring Boot 配置项。** 依据：本模块 `src/main/resources` 下仅有 `META-INF/services`、`META-INF/extensions`、`META-INF/native-image` 三类登记文件，不存在 `spring.factories`、`AutoConfiguration.imports` 或 `@ConfigurationProperties` 类；所有连接参数（地址、端口、密码、database、超时）均通过**构造器或 Builder** 传入。

各类默认值（源码常量）：

| 类 | 默认值 |
|---|---|
| `RedisClient` | 默认 `redis://127.0.0.1:6379`，超时 5s；`Builder` 可设 `host` / `port` / `password` / `database` / `timeout(long timeoutMs)`；也支持 `new RedisClient(url)`、`new RedisClient(url, password)`、`new RedisClient(RedisReactorEngine)` |
| `RedisReactorEngine` | `addDataSource(name, url[, password])` 支持 `redis://`、`rediss://`、`unix://`；密码需百分号编码，日志中掩码输出；`setTimeout(Duration)` / `setTimeout(name, Duration)` |
| `RedisClientProvider` | displayName「Redis 缓存」、defaultPort 6379；SCAN 每批 100、最多 500 键、超时 5000ms |
| `RedisConfigCenter` | 地址 `host:port`（缺省 `localhost:6379`）；`profile` 为数字时作为 Redis database 索引（0-15，默认 0）；连接池 maxTotal=20、maxIdle=10、minIdle=2，testOnBorrow/Return/WhileIdle=true；启动时 PING 健康检测 |
| `RedisSearchSink` | host `127.0.0.1`、port 6379，密码默认空；setter 可覆盖 |
| `RedisSeriesEngine` | 缺省连接 `redis://127.0.0.1:6379`；单指标 100_000 点上限；单次曲线 1440 点 |
| `RedisServiceDiscovery` | 注册记录 TTL 90s（靠调用方心跳重写续期）；本地读缓存 2s；记录键 `host:port[#scatterId]` |
| `RedisDeduplicator` | 默认 TTL 5 分钟；键前缀 `dedup:` |
| `RedissonDispatcherProvider` | 默认 `redis://127.0.0.1:6379`（`DispatcherConfig.getUrl()` 可覆盖） |

## SPI 扩展点

`META-INF/services`（Java SPI）：

| 接口 | 实现类 |
|---|---|
| `com.chua.common.support.lang.datasource.kv.KvEngine` | `com.chua.redis.support.client.RedisClient` |
| `com.chua.common.support.lang.datasource.kv.KvOperations` | `com.chua.redis.support.client.RedisClient` |
| `com.chua.common.support.concurrent.lock.LockProvider` | `com.chua.redis.support.lock.RedissonLockProvider` |

`META-INF/extensions`（键值式 SPI，`键=实现类全名`）：

| 接口 | SPI 键 | 实现类 |
|---|---|---|
| `...lang.datasource.engine.Engine` | `redis` | `engine.RediSearchEngine` |
| `...datasource.support.engine.ReactorEngine` | `redis` | `engine.RedisReactorEngine` |
| `...lang.datasource.series.SeriesEngine` | `redis` | `engine.RedisSeriesEngine` |
| `...lang.datasource.meta.SearchEngine` | `redis` | `meta.RedisSearchEngineImpl` |
| `...datasource.support.middleware.MiddlewareClientProvider` | `redis` | `spi.RedisClientProvider` |
| `...datalake.support.spi.sink.DataSink` | `redis-search` | `sink.RedisSearchSink` |
| `...config.center.ConfigCenter` | `redis` | `config.RedisConfigCenter` |
| `...concurrent.rate.RateLimiterProvider` | `redis` | `rate.RedisRateLimiterProvider` |
| `...concurrent.dispatcher.DispatcherProvider` | `redis` | `dispatcher.RedissonDispatcherProvider` |
| `...network.discovery.ServiceDiscovery` | `redis` | `discovery.RedisServiceDiscovery` |
| `...network.discovery.AbstractServiceDiscovery` | `redis` | `discovery.RedisServiceDiscovery` |
| `com.chua.redis.support.command.RedisCommandHandler` | `SET` / `GET` / `DEL` / `HSET` / `EXPIRE` / `INCR` | `command` 包下对应的 6 个处理器 |

另：`deduplicate.RedisDeduplicator` 标注 `@Spi("redis")`（`Deduplicator` 接口），本模块未为其提供 META-INF 登记文件。

## 构建与依赖

```bash
mvn clean compile -DskipTests    # JDK 25（maven.compiler.release=25）
```

| 依赖 | 版本 | Scope | 用途 |
|---|---|---|---|
| `com.chua:utils-support-common-starter` | 随父 POM | compile | common 层 SPI / 工具 |
| `org.redisson:redisson` | 4.8.0（`${redisson.version}`） | compile | 锁 / 限流 / 发布订阅 / 服务发现 / 去重 |
| `redis.clients:jedis` | 6.0.0（`${jedis.version}`） | compile | 同步客户端 / 管理面 / RediSearch / 配置中心 |
| `com.chua:utils-support-datasource-starter` | 随父 POM | provided | `Engine` / `EngineDataSource` / `MiddlewareClientProvider` 等契约 |
| `com.chua:utils-support-datalake-sink-starter` | 4.0.0.51 | provided | `DataSink` 契约 |
| `io.lettuce:lettuce-core` | 由 Spring Boot BOM 管理 | provided | 响应式引擎 / 时序引擎 |
| `it.ozimov:embedded-redis` | 0.7.3 | provided | 仅本地开发/测试的嵌入式 Redis |

GraalVM 原生镜像配置：`META-INF/native-image/com.chua/utils-support-redis-starter/` 下的 `reflect-config.json`、`resource-config.json`、`proxy-config.json`。

## 边界与注意事项

- **管理面只读**：`RedisClientProvider` 仅提供 dbsize/info/clients/slowlog/scan/type/ttl/get 等读操作，不做 `DEL` / `FLUSHDB` / `CONFIG SET`。
- **命令白名单**：`RedisReactorEngine.execCommand/execBatch` 仅允许白名单内的命令（GET/SET/DEL/EXISTS/TTL/PTTL/INCR/INCRBY/DECR/DECRBY/APPEND/STRLEN/LPUSH/RPUSH/LPOP/RPOP/LRANGE/LLEN/HGET/HSET/HDEL/HGETALL/HEXISTS/HLEN/SADD/SMEMBERS/SREM/SCARD/ZADD/ZRANGE/ZCARD/KEYS/DBSIZE/PING/INFO/SETEX/EXPIRE/FLUSHDB/SELECT/AUTH）；`query/update/delete` 等 SQL Lambda 入口抛 `UnsupportedOperationException`；`scanKeys` 单次最多返回 500 键。
- **Redis Stack 依赖**：`RediSearchEngine`、`RedisSearchEngineImpl`、`RedisSearchMeta`、`RedisSearchSink` 依赖 RediSearch 模块（`FT.*` 命令），普通 Redis 不可用。
- **服务发现订阅未完整实现**：`RedisServiceDiscovery.isSupportSubscribe()` 返回 `true`，但 `subscribe(...)` 仅打印告警日志（无 Pub/Sub 实现）；注册记录 TTL 90s，依赖调用方按心跳间隔重写续期，进程崩溃后记录自然过期。
- **去重器限制**：`RedisDeduplicator.clear()` 为空实现、`size()` 恒为 0（Redis 不支持按前缀原子批量删除）。
- **数据表只读**：`RedisDataTable` 仅用于查询聚合场景，列类型均为 `String`，数据经 SCAN 惰性加载。
- **配置中心无分组**：`RedisConfigCenter.get(dataId, group)` 忽略 `group`，直接按 `dataId` 查询；配置值支持 JSON（简单键值对）、Properties、纯文本三种格式。
- **时序键约定**：`RedisSeriesEngine` 面向监控指标场景，键前缀 `monitor:metric:` / `monitor:metrics:`，跨模块复用时需遵循该约定。
- **嵌入式 Redis**：`embedded-redis` 为 provided 依赖，生产环境不打包，需自备本地 `redis-server` 可执行文件。
