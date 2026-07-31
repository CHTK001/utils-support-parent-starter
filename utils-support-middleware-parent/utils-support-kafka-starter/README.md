# utils-support-kafka-starter

Kafka 消息中间件集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-kafka-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `KafkaClient` | Kafka 全功能链式客户端。 |
| `KafkaDispatcherProvider` | Kafka 分发器提供者，基于 Kafka 实现跨进程的发布订阅。 (SPI: `kafka`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-kafka-starter
├── utils-support-common-starter
```