# utils-support-shardingv5-starter

utils-support-shardingv5-starter module

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-shardingv5-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AlgorithmHolder` | AlgorithmHolder |
| `CacheEntry` | CacheEntry |
| `DbConfig` | DbConfig |
| `ShardingV5Conversion` | ShardingSphere V5 分库分表数据源转换器。 链式注册分片表 + 自定义算法，自动发现物理表。 使用示例 (SPI: `SHARDINGV5`) |
| `TableCache` | ShardingSphere 表结构缓存。 |
| `TableConfig` | TableConfig |
| `TimeRangeConfig` | TimeRangeConfig |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-shardingv5-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```