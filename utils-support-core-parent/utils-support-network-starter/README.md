# utils-support-network-starter

网络通信模块：Netty HTTP/TCP/UDP、Socket、WebSocket、ServerFilter 集合

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-network-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MetadataDownloader` | MetadataDownloader |
| `PeerWireClient` | PeerWireClient |
| `DhtConfig` | DHT 节点配置。 包含 DHT 协议运行所需的所有配置参数，如端口、K 值、Alpha 并行度、超时时间等。 可通过 从通用服务发现配置创建。 |
| `DhtCrawlListener` | DhtCrawlListener |
| `DhtMessage` | DHT 消息体。 表示 DHT 协议中节点之间交换的消息，通过 JSON 序列化进行网络传输。 包含消息类型、发送者和接收者的标识符、以及可选的载荷数据。 |
| `DhtMessageType` | DHT 消息类型枚举。 定义了 DHT 协议中所有的消息类型，包括请求类型和对应的响应类型。 |
| `DhtPeer` | DHT 远端对等节点。 表示 DHT 网络中的一个节点，包含节点 ID、网络地址以及与当前节点的交互状态。 |
| `DhtPolledDirectory` | 基于 Kademlia DHT 协议的轮询目录实现。 |
| `DhtProtocol` | DHT 协议引擎。 实现了 Kademlia 协议的核心逻辑，包括节点发现、路由表维护、 迭代查找、值存储与检索、自动 Bootstrap 和重新发布等功能。  |
| `DhtRoutingTable` | Kademlia 路由表。 |
| ... | 共 37 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-network-starter
├── utils-support-common-starter
```