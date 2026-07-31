# utils-support-socketio-starter

Socket.IO 实时通信集成，基于 netty-socketio，含 ConfigServer 及 DispatcherProvider SPI 实现

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-socketio-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `SocketIODataSyncAgent` | SocketIO 数据同步 Agent 通过 SocketIO 与 DataSyncServer 保持长连接，支持双向事件通信。 |
| `SocketIoDispatcherProvider` | Socket.IO 分发器提供者，基于 socket.io-client 连接远程 Socket.IO 服务。 (SPI: `socketio`) |
| `SocketIODataSyncAgentServer` | SocketIO 数据同步 Agent 服务端 运行在 DataSyncServer 侧，通过 SocketIO 管理 Agent 连接，支持事件拉取和推送。 |
| `SocketIOServer` | Socket.IO 嵌入式服务器，轻量级实现。 (SPI: `socketio`) |
| `SocketIOAgentDataSyncSource` | SocketIO Agent 数据源 Server 侧通过 SocketIO 事件与 Agent 交互，支持拉取和推送。 |
| `SocketIoSubscriber` | Socket.IO 协议订阅者实现，基于 socket.io-client 接入 Socket.IO 服务器。 (SPI: `socketio`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-socketio-starter
├── utils-support-common-starter
├── utils-support-datalake-subscribe-starter
├── utils-support-datasync-agent-starter
├── utils-support-datasync-starter
```