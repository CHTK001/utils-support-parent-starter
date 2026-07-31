# utils-support-mqtt-starter

MQTT 消息中间件集成，基于 Eclipse Paho，含 DispatcherProvider 及 Subscriber SPI 实现

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mqtt-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MqttClientWrapper` | MQTT 全功能链式客户端。 |
| `MqttDispatcherProvider` | MQTT 分发器提供者，基于 Eclipse Paho MQTT v3 客户端实现跨进程的发布/订阅消息分发。 (SPI: `mqtt`) |
| `MqttListenerParser` | MQTT 注解监听解析器，扫描 @OnOpen/@OnClose/@OnMessage。 |
| `MqttServer` | MQTT 嵌入式服务器，轻量级实现。 (SPI: `mqtt`) |
| `MqttSubscriber` | MQTT 协议订阅者实现，基于 Eclipse Paho MQTT v3 客户端接入 MQTT Broker。 (SPI: `mqtt`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-mqtt-starter
├── utils-support-common-starter
├── utils-support-datalake-subscribe-starter
```