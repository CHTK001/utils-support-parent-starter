# utils-support-serialize-starter

轻量高可用序列化模块：Kryo、JSON、Java原生序列化

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-serialize-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AutoSerializer` | 自动降级序列化器。 支持多级序列化策略，按优先级依次尝试： Kryo — 高性能二进制序列化（首选） JSON (Jackson) — 跨语言兼容（次选） Ja (SPI: `auto`) |
| `AutoSerializerProvider` | 自动序列化器提供者。 基于 ConcurrentHashMap 实现线程安全的序列化器缓存， 按实体类类型缓存 AutoSerializer 实例，避免重复创建 |
| `KryoSerializer` | 基于 Kryo 的高性能序列化器。 (SPI: `kryo`) |
| `KryoPoolManager` | Kryo 序列化器对象池管理器。 |
| `SerializeSupport` | 序列化支持工具类。 提供多种序列化方式的便捷创建和管理功能，包括： Kryo 序列化 — 高性能二进制序列化 自动降级序列化 — Kryo → JSON → J |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-serialize-starter
├── utils-support-common-starter
```