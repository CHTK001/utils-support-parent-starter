# utils-support-dht-starter

> utils 体系的 **DHT（Kademlia / BitTorrent）网络模块**：提供链式构建的 DHT 节点服务、KRPC（Bencode）协议桥接、BEP 5 get_peers/announce_peer、BEP 9 元数据下载，以及基于 DHT 的服务发现与目录轮询。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 pom `maven.compiler.source/target=25`）｜包名：`com.chua.common.support.network.server.dht`（子包 `krpc`、`bep9`、`store`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 快速开始](#2-快速开始)
- [3. 核心 API](#3-核心-api)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-dht-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | BitTorrent DHT（Kademlia）协议的最小可用实现：节点发现、路由表、值存储、peer 爬取 | 完整 BT 客户端：不做文件分片下载、不做种、不做 peer 间数据传输 |
| 协议 | 内部 JSON 消息 + 标准 KRPC/Bencode 互通（BEP 5 `get_peers`/`announce_peer`、BEP 9 元数据） | 不覆盖完整 BEP 系列（如 DHT 安全扩展，源码未提供） |
| 传输 | Netty NIO UDP（`NioDatagramChannel`） | 不含 TCP/TLS 传输 |
| 配置 | Builder / `DhtConfig` / `DiscoveryOption` / `dht.url` 显式传参 | 无 Spring Boot 自动配置、无配置文件键绑定 |
| 存储 | 内存键值（TTL 过期）+ 内存 peer/路由表 | 不做磁盘或数据库持久化 |
| 角色 | utils 层网络基础能力，供上层按需组合 | 不含任何业务编排 |

**规模**：`src/main/java` 下 21 个顶层类型；内置 GraalVM `native-image` 的 `reflect-config.json`（登记 `DHTServer`、`DhtProtocol`、`DhtServiceDiscovery`、`DhtPolledDirectory` 四个入口的反射元数据）。

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起一个 DHT 节点 | `DHTServer.builder()` | 链式配置端口/种子/宣告地址，`start()` 后自动启用 KRPC 桥接 |
| 加入 DHT 网络 | `DHTServer#bootstrap()` | 向种子节点发 `find_node` 并填充路由表；默认含 4 个公共 BT 引导节点 |
| 键值存取 | `store(key, value)` / `findValue(key)` | 本地优先，未命中走迭代查找 |
| 迭代找节点 | `iterativeFindNode(KademliaNodeId)` | Kademlia 迭代查找，返回最接近的 k 个节点 |
| 爬取 infohash | `DhtCrawlListener` + `collectedInfohashes()` | 被动发现 + 主动 `get_peers` 爬取 BT peer |
| 查种子名（BEP 9） | `lookupName(hexInfohash, timeoutMs)` | 向 peer 下载元数据并解析种子名 |
| 服务发现 | `DhtServiceDiscovery` | 以 `/dht/discovery/路径` 为键把服务注册进 DHT |
| 目录轮询 | `DhtPolledDirectory` | 定时查询 DHT 键值，变更时派发 CREATE/DELETE 事件 |
| NAT 探测 | `detectExternalAddress(stunServer)` | 发送 `NAT_DETECT` 消息取回外部地址 |

---

## 2. 快速开始

### 2.1 起节点、入网、存取值

```java
import com.chua.common.support.network.server.dht.DHTServer;
import com.chua.common.support.network.server.dht.DhtPeer;
import com.chua.common.support.network.server.dht.KademliaNodeId;

import java.util.List;

// 1. 构建并启动（默认已包含 4 个公共 BT 引导节点，可再 addSeed 追加）
DHTServer server = DHTServer.builder()
        .port(6881)
        .addSeed("router.bittorrent.com:6881")
        .addSeed("dht.transmissionbt.com:6881")
        .build();
server.start();                       // 生命周期方法继承自 AbstractServer

// 2. 加入 DHT 网络（向种子发送 find_node 并填充路由表）
server.bootstrap();

// 3. 键值存取：先查本地，未命中走网络迭代查找
server.store("greeting", "hello-dht");
String value = server.findValue("greeting");

// 4. 迭代查找距离目标最近的 k 个节点
KademliaNodeId target = KademliaNodeId.fromString("user-service");
List<DhtPeer> closest = server.iterativeFindNode(target);

server.stop();
```

### 2.2 爬取 infohash 并解析种子名

```java
import com.chua.common.support.network.server.dht.DHTServer;
import com.chua.common.support.network.server.dht.DhtCrawlListener;
import com.chua.common.support.network.server.dht.DhtPeer;

import java.net.InetSocketAddress;
import java.util.List;

DHTServer server = DHTServer.builder()
        .port(6881)
        .crawlListener(new DhtCrawlListener() {
            @Override
            public void onInfohash(String infohash, InetSocketAddress source) {
                // 被动发现：收到入站 get_peers / announce_peer 时触发
            }

            @Override
            public void onPeers(String infohash, List<DhtPeer> peers) {
                // 主动爬取：activeCrawl 收集到 BT peer 时触发
            }
        })
        .build();
server.start();
server.bootstrap();

// 通过 infohash（40 位十六进制）向 peer 下载元数据，解析种子名（BEP 9）
String name = server.lookupName("<40位十六进制infohash>", 15000);
```

### 2.3 基于 DHT 的服务发现 / 目录轮询

`DhtServiceDiscovery` 把服务信息以 `/dht/discovery/路径` 为键写入 DHT；`DhtPolledDirectory` 按固定间隔查询 DHT 键，值变化时向 `PolledListener` 派发 CREATE/DELETE 事件。`DhtServiceDiscovery` 需要先有一个运行中的 `DhtProtocol`（可经 `DHTServer#protocol()` 获取），并通过 `setProtocol(...)` 注入，未注入时 `start()` 抛 `IllegalStateException`；`DhtPolledDirectory` 不接受协议注入，`start(env, executor)` 时内部根据 `dht.url` 自行构建并启动 `DhtProtocol`。

`DhtPolledDirectory` 通过 URL 配置，格式（环境键常量 `KEY_DHT_URL = "dht.url"`）：

```text
dht://host:port/?watch=key1&watch=key2&seed=host2:port2&k=8&port=6881
```

---

## 3. 核心 API

### 3.1 DHTServer —— 推荐入口

`DHTServer extends AbstractServer`，封装 `DhtProtocol` + `DhtNettyServer`，启动时自动创建 `KrpcDhtBridge` 并 `enableKrpc(...)`，因此可与标准 BitTorrent DHT 网络互通。协议类型为 `ProtocolType.UDP`。

链式 Builder：`port` / `host` / `nodeId`（40 位 hex，缺省随机）/ `kBucketSize` / `alpha` / `bootstrapIntervalMs` / `republishIntervalMs` / `valueTtlMs` / `peerTimeoutMs` / `advertisedHost` / `advertisedPort` / `crawlListener` / `addSeed` / `seeds(String...)` / `build()`。

实例方法（生命周期 `start()` / `stop()` / `close()` / `isRunning()` / `getPort()` 继承自 `AbstractServer`）：

| 方法 | 说明 |
| --- | --- |
| `bootstrap()` | 加入 DHT 网络 |
| `addSeed(String)` | 追加种子地址（`主机:端口`） |
| `store(String key, String value)` | 存键值对 |
| `findValue(String key)` | 取键值对，未找到返回 `null` |
| `findNode(InetSocketAddress, KademliaNodeId)` | 向指定节点发 `find_node` |
| `iterativeFindNode(KademliaNodeId)` | 迭代查找最近节点 |
| `krpcGetPeers(InetSocketAddress, String infohash)` | 向指定节点发 KRPC `get_peers` |
| `lookupName(String hexInfohash, int timeoutMs)` | BEP 9 查种子名 |
| `collectedInfohashes()` | 被动收集到的 infohash 集合 |
| `detectExternalAddress(InetSocketAddress stunServer)` | NAT 探测 |
| `protocol()` / `selfId()` / `bridge()` | 取内部协议引擎 / 节点标识 / KRPC 桥接器 |

默认种子（`DEFAULT_SEEDS`）：`router.bittorrent.com:6881`、`dht.transmissionbt.com:6881`、`dht.aelitis.com:6881`、`router.utorrent.com:6881`。

### 3.2 DhtProtocol —— 协议引擎

实现 Kademlia 核心逻辑：路由表维护、迭代查找、值存储/检索、自动 Bootstrap（`bootstrapIntervalMs` 周期）与重新发布（`republishIntervalMs` 周期）。`implements AutoCloseable`，可独立使用：

```java
DhtProtocol protocol = new DhtProtocol(config, selfId);
protocol.start();          // throws Exception
// ...
protocol.close();
```

爬取侧：`activeCrawl(infohash)` 向路由表中离目标最近的 `alpha*3` 个节点并行发 `get_peers`，结果回调 `DhtCrawlListener#onPeers`；入站 `get_peers` / `announce_peer` 由协议引擎被动收集 infohash。BEP 5 语义：`implied_port=1` 时采用 UDP 来源端口；每个 infohash 最多保留 200 个 peer，TTL 30 分钟。

### 3.3 DhtNettyServer —— UDP 传输层

基于 Netty `NioDatagramChannel`（`SO_RCVBUF`/`SO_SNDBUF` 65536）。收包按首字节分发：`{` 走内部 JSON（`DhtMessage.fromJson`），否则走 KRPC/Bencode（`BencodeCodec.decode`）。挂起匹配键：JSON 为 `{targetId}@{ip}:{port}`，KRPC 为 `{txId}@{ip}:{port}`；超时由共享守护线程 `dht-netty-timeout` 触发 `TimeoutException`。

| 方法 | 说明 |
| --- | --- |
| `send(DhtMessage, target, timeoutMs)` | 发 JSON 消息并等待响应（`CompletableFuture<DhtMessage>`） |
| `sendNoResponse(DhtMessage, target)` | 发送不等响应；若设置 `responseEncoder` 则优先走编码器 |
| `sendRaw(byte[], target, pendingKey, timeoutMs)` | 发原始字节（KRPC）并等待响应（`CompletableFuture<byte[]>`） |
| `sendNoResponseRaw(byte[], target)` | 发原始字节不等响应 |
| `setMessageHandler` / `setRawMessageHandler` / `setResponseEncoder` | 注入 JSON 处理器 / KRPC 分发器 / 响应编码器 |

### 3.4 KRPC 桥接（`krpc` 包）

| 类型 | 说明 |
| --- | --- |
| `KrpcDhtBridge` | 内部 JSON 消息 ↔ BitTorrent KRPC（Bencode）双向转换：`encodeQuery(msg, txId)`、`decodeResponse(raw, msg)`、`encodeResponse(msg, txId)`、`decodeQuery(krpc, sender)`、`nextTxId()`；静态工具 `generateNodeId()`、`hexToRawId(hex)`、`rawIdToHex(raw)`；支持紧凑节点/peer 列表编解码 |
| `KrpcMessage` | KRPC 报文模型：`t`（事务 id）、`y`（`q` 查询 / `r` 响应 / `e` 错误）、`q`、`a`、`r`、`e`；`KrpcMessage.parse(byte[])` 解码 |
| `BencodeCodec` | Bencode 编解码静态门面（基于 `com.dampcake:bencode`，ISO_8859_1 保证字节串无损往返）：`decode(byte[])`、`encode(Object)`、`encode(long)` |

### 3.5 BEP 9 元数据下载（`bep9` 包）

| 类型 | 说明 |
| --- | --- |
| `MetadataDownloader` | `download(peer, infoHash, timeoutMs)` → `MetadataResult`；按 16 KiB 分块下载元数据（上限 10,000,000 字节，单块最多重试 2 次） |
| `MetadataDownloader.MetadataResult` | `ok` 标志 + `name`（种子名）+ 元数据字节；工厂 `ok(name, metadata)` / `err(msg)` |
| `PeerWireClient` | BT Peer Wire 扩展协议客户端：`new PeerWireClient(address, infoHash, timeoutMs)`，`getUtMetadataId()`、`getPeerMetadataSize()`、`sendExtHandshake(metadataSize)`、`sendExtendedMessage(extMsgId, payload)`、`close()` |

### 3.6 服务发现与目录轮询

| 类型 | 说明 |
| --- | --- |
| `DhtServiceDiscovery` | `extends AbstractServiceDiscovery`。`registerService(path, discovery)` 以 `addClusterPrefix("/dht/discovery/" + path)` 为键 `protocol.store(...)`；`getServiceAll(path)` 先查本地注册缓存、未命中 `protocol.findValue(...)`；`subscribe` / `addListener` 注册 `ServiceDiscoveryListener`；`isSupportSubscribe()` 返回 `true`；`balance` 为 `round`/`weight` 时按时间取模轮询，否则取首个 |
| `DhtPolledDirectory` | `implements PolledDirectory`。构造时从 `DirectoryPollerEnvironment` 取 `dht.url`（缺失抛 `IllegalArgumentException`）；`start(env, executor)` 内部建 `DhtProtocol`、`bootstrap()` 并按 `env.getPollingInterval()` / `getTimeUnit()` 轮询 watch 键；对比前后快照派发 `WatcherEvent.CREATE` / `DELETE`；`upgrade()` 触发一次立即轮询 |

### 3.7 数据模型与存储

| 类型 | 说明 |
| --- | --- |
| `KademliaNodeId` | 160 位（20 字节）SHA-1 节点标识：`random()`、`fromString(str)`、`fromHex(hex)`、`getBytes()`、`getInt()`、`xor(other)`、`getBucketIndex(other)`（0~159）、`getDistance(other)`；`ID_LENGTH = 160`；实现 `Comparable` |
| `DhtPeer` | 不可变 record：`nodeId`（40 位 hex）、`host`、`port`、`lastSeen`（epoch 毫秒）、`failedPings`；`toBuilder()` 派生新实例；`isReachable(timeoutMs)`、`toAddress()`、`toFullString()`；`host` 为 `null` / `"0.0.0.0"` 时无法入路由表 |
| `DhtMessage` | 消息体：`type`、`senderId/senderHost/senderPort`、`targetId`、`infohash`、`impliedPort`、`key`、`value`、`peers`、`values`、`timestamp`、`ttl`；`toJson()` / `fromJson(json)`；`recipientHost/recipientPort`、`krpcTxId` 为 transient |
| `DhtMessageType` | 16 个枚举：`PING`/`PONG`、`FIND_NODE`/`FIND_NODE_RESPONSE`、`STORE`/`STORE_RESPONSE`、`FIND_VALUE`/`FIND_VALUE_RESPONSE`、`BOOTSTRAP`/`BOOTSTRAP_RESPONSE`、`NAT_DETECT`/`NAT_DETECT_RESPONSE`、`GET_PEERS`/`GET_PEERS_RESPONSE`、`ANNOUNCE_PEER`/`ANNOUNCE_PEER_RESPONSE` |
| `DhtRoutingTable` | 160 个 `KBucket`：`insert(peer)`（桶满时按最冷节点 `failedPings >= 3` 淘汰）、`findPeer(nodeId)`、`removePeer(nodeId)`、`findClosestPeers(target, count)`、`totalPeers()`、`getAllPeers()` |
| `KBucket` / `KBucketEntry` | K 桶与条目封装（插入、移除、按 lastSeen 刷新/淘汰） |
| `store.DhtValueStore` | 内存 KV + TTL：`put(key, value[, ttlMs])`、`get(key)`（过期即删）、`getAll()`、`keys()`、`remove(key)` |
| `store.DhtResourceStore` | infohash → peers 内存表：`recordPeer(infohash, host, port, sourceNodeId)`、`recordPeersFromResponse(infohash, peers)`、`getPeers(infohash)` |

---

## 4. 配置项

### 4.1 DhtServerBuilder / DhtConfig 字段

| 参数 | Builder 默认值 | 说明 |
| --- | --- | --- |
| `port` | `6881` | UDP 监听端口 |
| `host` | `0.0.0.0` | 绑定主机地址 |
| `nodeId` | 随机生成 | 40 位 hex 固定节点标识 |
| `kBucketSize` | `8` | K 桶容量（K 值） |
| `alpha` | `3` | 并行查询节点数 |
| `bootstrapIntervalMs` | `60000` | 自动 Bootstrap 周期（毫秒） |
| `republishIntervalMs` | `300000` | 重新发布周期（毫秒） |
| `valueTtlMs` | `600000` | 存储值 TTL（毫秒） |
| `peerTimeoutMs` | `30000` | 节点超时（毫秒） |
| `advertisedHost` / `advertisedPort` | 空 / `0` | 对外宣告地址（NAT 穿透）；两者都有效时 `DhtConfig#hasAdvertisedAddress()` 为 `true` |
| `seeds` | 4 个公共 BT 引导节点 | 种子地址集合；`addSeed` 追加，`seeds(String...)` 覆盖 |

> 注：直接 `DhtConfig.builder()` 构建时，`bootstrapIntervalMs` 字段初始化值为 `10_000`（类注释口径为 60 秒）；经 `DHTServer.builder()` 构建则默认传 `60_000`。

### 4.2 DhtConfig.from(DiscoveryOption) 支持的 extra 键

| 键 | 类型 | 映射到 |
| --- | --- | --- |
| `dht.port` | Number | `port` |
| `dht.k` | Number | `kBucketSize` |
| `dht.alpha` | Number | `alpha` |
| `dht.value-ttl-ms` | Number | `valueTtlMs` |
| `dht.advertised-host` | String | `advertisedHost` |
| `dht.advertised-port` | Number | `advertisedPort` |
| `dht.node-id` | String | `nodeId` |
| `dht.seeds` | String（逗号分隔） | `seeds` |

另：`host` 取自 `DiscoveryOption#getAddress()`。

### 4.3 DhtPolledDirectory 的 dht.url

```text
dht://host:port/?watch=key1&watch=key2&seed=host2:port2&k=8&port=6881
```

- `watch`：可重复，指定要监听变更的 DHT 键；
- `seed`：可重复，追加种子节点；
- `k` / `port`：覆盖 K 值 / 端口；
- `host:port` 段：本机监听地址；host 非 `0.0.0.0` / `127.0.0.1` 时会把 `host:port` 自身加入种子。

环境键名常量：`DhtPolledDirectory.KEY_DHT_URL = "dht.url"`。

---

## 5. SPI 扩展点

| 扩展点 | 本模块角色 | 说明 |
| --- | --- | --- |
| `com.chua.common.support.lang.directory.PolledDirectory` | `DhtPolledDirectory` 实现 | 轮询目录 SPI；本模块 `src/main/resources` 未提供 `META-INF/extensions` 注册文件（源码未提供），需由上层装配或自行注册 |
| `com.chua.common.support.network.discovery.ServiceDiscovery` | `DhtServiceDiscovery` 继承 `AbstractServiceDiscovery` | 服务发现契约的 DHT 实现；同样未在本模块内置 SPI 注册文件 |
| `DhtCrawlListener` | 本模块自定义观察接口 | `onInfohash` / `onPeers` 两个 `default` 回调，按需覆写 |
| `DhtProtocol.ServiceListener` | 本模块内部接口 | `onChange(path, value)`，服务值变更通知（包内 `serviceListeners` 使用） |

GraalVM 侧：`META-INF/native-image/com.chua/utils-support-dht-starter/reflect-config.json` 为 `DHTServer`、`DhtProtocol`、`DhtServiceDiscovery`、`DhtPolledDirectory` 注册了全量反射元数据。

---

## 6. 构建与依赖

```text
utils-support-dht-starter
├── com.chua:utils-support-common-starter   （契约与工具：Json、Wraps、AbstractServer、discovery 契约等）
├── io.netty:netty-all                       （UDP 传输层）
└── com.dampcake:bencode:1.4.2               （KRPC/Bencode 编解码）
```

- 版本随父 pom `utils-support-parent-starter`（`4.0.0.51`）统一管理；
- 编译验证：在 `utils-support-parent-starter` 目录执行 `mvn -o -q compile -DskipTests`，EXIT=0；
- 本模块不依赖任何 `spring-*` 上层模块（本仓库为最底层工具库）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| BT 文件传输 | 不做分片下载、做种、上传；`MetadataDownloader` 只取元数据（种子名等），不取文件内容 |
| TCP/TLS 传输 | 传输层固定为 Netty NIO UDP；无 TCP 通道 |
| Spring Boot 自动配置 | 无 auto-configuration、无 `application.yml` 配置键绑定；参数一律显式传入 |
| 磁盘/数据库持久化 | 路由表、值、peer 均为内存存储 + TTL；模块内未提供持久化实现（`DhtPeer` 实现 `Serializable` 仅为契约预留） |
| 完整 NAT 穿透 | 仅 `NAT_DETECT` 消息探测与 `advertisedHost`/`advertisedPort` 宣告；不含 STUN/TURN 协议栈 |
| 完整 BEP 覆盖 | 仅 BEP 5（get_peers/announce_peer）与 BEP 9（元数据）的最小实现；其余 BEP 扩展源码未提供 |
| 服务治理语义 | `DhtServiceDiscovery` 无心跳摘除、无完整权重负载均衡（`round`/`weight` 均为按时间取模轮询） |
| 配置中心/注册中心 | Nacos/Apollo 等由对应 starter 提供，本模块只提供 DHT 侧实现 |
