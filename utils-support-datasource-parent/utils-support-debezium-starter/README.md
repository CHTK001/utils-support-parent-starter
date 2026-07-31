# utils-support-debezium-starter

Debezium CDC 集成，基于 Debezium Engine

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-debezium-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DebeziumConnectorConfig` | DebeziumConnectorConfig |
| `DebeziumEnvironmentSetup` | DebeziumEnvironmentSetup |
| `DebeziumPolledDirectory` | Debezium CDC 目录轮询实现，基于 Debezium Engine。 |
| `DebeziumEnvironment` | Debezium 环境配置构造器。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-debezium-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```