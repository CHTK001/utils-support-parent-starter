# utils-support-chronicle-starter

Chronicle Queue 消息中间件集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-chronicle-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ChronicleActiveCollector` | Chronicle Queue 主动采集器。 (SPI: `chronicle`) |
| `ChronicleDispatcherProvider` | Chronicle Queue 分发器提供者，基于 Chronicle Queue 实现进程内的持久化发布订阅。 (SPI: `chronicle`) |
| `ChronicleLockProvider` | 基于文件锁的进程间锁提供者 同 JVM 内通过 协调多线程， 跨进程通过文件锁协调多 JVM。 (SPI: `chronicle`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-chronicle-starter
├── utils-support-common-starter
├── utils-support-datalake-sink-api-starter
```