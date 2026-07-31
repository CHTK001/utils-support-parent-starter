# utils-support-rsocket-starter

RSocket 响应式通信集成，基于 RSocket Java，含 DispatcherProvider 及 Subscriber SPI 实现

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rsocket-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `RSocketDataSyncAgent` | RSocket 数据同步 Agent 通过 RSocket 与 DataSyncServer 建立双向流式连接。 |
| `RSocketDispatcherProvider` | RSocket 分发器提供者，基于 RSocket Java 客户端连接远程 RSocket 服务。 (SPI: `rsocket`) |
| `RSocketDataSyncAgentServer` | RSocket 数据同步 Agent 服务端 运行在 DataSyncServer 侧，通过 RSocket 管理 Agent 连接，支持 request-st |
| `RSocketServer` | RSocket 嵌入式服务器，轻量级实现。 (SPI: `rsocket`) |
| `RSocketAgentDataSyncSource` | RSocket Agent 数据源 Server 侧通过 RSocket request-stream / fire-and-forget 与 Agent 交互 |
| `RSocketSubscriber` | RSocket 协议订阅者实现，基于 RSocket Java 客户端接入 RSocket 服务器。 (SPI: `rsocket`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-rsocket-starter
├── utils-support-common-starter
├── utils-support-datalake-subscribe-starter
├── utils-support-datasync-agent-starter
├── utils-support-datasync-starter
```