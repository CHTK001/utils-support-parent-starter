# utils-support-redis-starter

utils-support-redis-starter module

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-redis-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `RedisClient` | Redis 链式客户端，全功能封装 Jedis。 |
| `RedisConfigCenter` | Redis 配置中心实现。 (SPI: `redis`) |
| `RedisDataTable` | Redis 数据表实现，将 Redis Hash 数据映射为表格结构。 |
| `RedisDeduplicator` | 基于 Redisson 的 Redis 去重器，实现分布式幂等。 (SPI: `redis`) |
| `RedisServiceDiscovery` | Redis 服务发现实现。 (SPI: `redis`) |
| `RedissonDispatcherProvider` | 基于 Redisson RTopic 的 Redis 发布订阅分发器提供者 (SPI: `redis`) |
| `RediSearchEngine` | Redis RediSearch 引擎实现。 继承 ，基于 FT.SEARCH 实现实体查询、更新、删除， 并支持全量扫描回退机制。 (SPI: `redis`) |
| `RediSearchQueryConverter` | RediSearch 查询语句转换器。 |
| `RedisEngine` | Redis 基础引擎，提供数据源管理、连接池和基础工具方法。 不实现 接口， 仅作为 的基类使用。 |
| `SimpleRedisDataSource` | SimpleRedisDataSource |
| ... | 共 17 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-redis-starter
├── utils-support-datasource-starter
├── utils-support-common-starter
├── utils-support-datalake-sink-api-starter
```