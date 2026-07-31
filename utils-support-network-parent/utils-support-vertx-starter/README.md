# utils-support-vertx-starter

Vert.x HTTP 服务器实现模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-vertx-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `WebSocketDataSyncAgent` | WebSocket 数据同步 Agent 通过 WebSocket 与 DataSyncServer 保持长连接，支持双向数据拉取和推送。 |
| `VertxHttpServer` | 基于 Vert.x 的 HTTP 服务器实现，支持同步阻塞和响应式两种模式。 |
| `VertxWebSocketServer` | VertxWebSocketServer |
| `WebSocketDataSyncAgentServer` | WebSocket 数据同步 Agent 服务端 运行在 DataSyncServer 侧，接受 WebSocket 连接，管理 Agent 注册、心跳、数据拉 |
| `WebSocketAgentDataSyncSource` | WebSocket Agent 数据源 Server 侧通过 WebSocket 与 Agent 交互，支持拉取和推送。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-vertx-starter
├── utils-support-common-starter
├── utils-support-datasync-agent-starter
├── utils-support-datasync-starter
```