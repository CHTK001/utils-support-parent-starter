# utils-support-rabbitmq-starter

RabbitMQ 消息中间件集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rabbitmq-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `RabbitmqClient` | RabbitMQ 全功能链式客户端。 |
| `RabbitmqDispatcherProvider` | RabbitMQ 分发器提供者，基于 RabbitMQ 实现跨进程的发布订阅。 (SPI: `rabbitmq`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-rabbitmq-starter
├── utils-support-common-starter
```