package com.chua.common.support.network.server.dht;

import com.chua.common.support.network.server.dht.krpc.KrpcDhtBridge;
import com.chua.common.support.network.server.dht.store.DhtValueStore;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * DHT 协议引擎。
 * <p>
 * 实现了 Kademlia 协议的核心逻辑，包括节点发现、路由表维护、
 * 迭代查找、值存储与检索、自动 Bootstrap 和重新发布等功能。
 * 使用 {@link DhtNettyServer} 替代原有的阻塞式 UDP 实现。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DhtProtocol implements AutoCloseable {

    /**
     * DHT 配置
     */
    private final DhtConfig config;

    /**
      * 本地节点的 Kademlia 标识
     */
    private final KademliaNodeId selfId;

    /**
     * 路由表
     */
    private final DhtRoutingTable routingTable;

    /**
      * UDP 服务器（基于 抽象服务端 实现）
     */
    private final DhtNettyServer server;

    /**
     * 本地键值存储
     */
    private final DhtValueStore valueStore;

    /**
     * 定时任务调度器（Bootstrap 和重新发布）
     */
    private final ScheduledExecutorService scheduler;

    /**
      * Bootstrap 种子节点集合，格式 "主机:端口"
     */
    private final Set<String> bootstrapSeeds = ConcurrentHashMap.newKeySet();

    /**
      * 被动收集到的 infohash 集合（来自入站 获取_peers 查询）
     */
    private final Set<String> infohashes = ConcurrentHashMap.newKeySet();

    /**
     * 已订阅服务名的监听器列表（用于服务发现通知）
     */
    private final List<ServiceListenerEntry> serviceListeners = new CopyOnWriteArrayList<>();

    /**
     * KRPC 桥接器（非空时出站消息使用 KRPC 而非 JSON）。
     */
    private KrpcDhtBridge krpcBridge;

    /**
     * DHT 爬取监听器。
     */
    private DhtCrawlListener crawlListener;

    /**
     * 设置 DHT 爬取监听器。
     *
     * @param listener 监听器实例
     */
    public void setCrawlListener(DhtCrawlListener listener) {
        this.crawlListener = listener;
    }

    /**
     * 构造 DHT 协议引擎。
     *
     * @param config DHT 配置
     * @param selfId 本地节点 标识
     */
    public DhtProtocol(DhtConfig config, KademliaNodeId selfId) {
        this.config = config;
        this.selfId = selfId;
        this.routingTable = new DhtRoutingTable(selfId, config.getKBucketSize());
        this.server = new DhtNettyServer(config);
        this.valueStore = new DhtValueStore(config.getValueTtlMs());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dht-protocol");
            t.setDaemon(true);
            return t;
        });
        if (config.getSeeds() != null) {
            bootstrapSeeds.addAll(config.getSeeds());
        }
    }

    /**
     * 启动 DHT 协议引擎。
     *
     * @throws Exception 启动失败时抛出
     */
    public void start() throws Exception {
        server.start();
        server.setMessageHandler(this::handleMessage);
        scheduler.scheduleWithFixedDelay(this::bootstrap,
                0, config.getBootstrapIntervalMs(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::republish,
                config.getRepublishIntervalMs(), config.getRepublishIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("DHT protocol started. NodeId={}", selfId);
    }

    /**
     * 获取 UDP 服务器实例。
     *
     * @return DhtNettyServer 实例
     */
    public DhtNettyServer server() {
        return server;
    }

    /**
     * 获取路由表。
     *
     * @return DhtRoutingTable 实例
     */
    public DhtRoutingTable routingTable() {
        return routingTable;
    }

    /**
     * 获取值存储。
     *
     * @return DhtValueStore 实例
     */
    public DhtValueStore valueStore() {
        return valueStore;
    }

    /**
      * 获取本地节点 标识。
     *
     * @return KademliaNodeId 实例
     */
    public KademliaNodeId selfId() {
        return selfId;
    }

    /**
     * 添加种子节点地址。
     *
     * @param seed 种子节点地址，格式 "主机:端口"
     */
    public void addSeed(String seed) {
        bootstrapSeeds.add(seed);
    }

    /**
     * 启用 KRPC 协议桥接。
     * <p>
      * 启用后，所有出站查询（find节点、bootstrap 等）将使用 KRPC (Bencode) 编码
      * 而非 JSON，使本节点能与标准 钻头torrent DHT 网络互通。
     * </p>
     *
     * @param bridge krpcdhtbridge 实例
     */
    public void enableKrpc(KrpcDhtBridge bridge) {
        this.krpcBridge = bridge;
    }

    /**
     * 执行 Bootstrap，加入 DHT 网络。
     * <p>
      * 路由表为空时向种子节点发送 查找_节点 请求；
     * 路由表非空时从路由表取 3 个节点刷新。
     * </p>
     */
    public void bootstrap() {
        int total = routingTable.totalPeers();
        if (total < 20 && !bootstrapSeeds.isEmpty()) {
            java.util.List<CompletableFuture<Void>> seedFutures = new ArrayList<>();
            for (String seed : bootstrapSeeds) {
                seedFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        String[] parts = seed.split(":");
                        InetSocketAddress addr = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
                        List<DhtPeer> peers = findNode(addr, selfId);
                        for (DhtPeer p : peers) {
                            if (!p.getNodeId().equals(selfId.toString())) {
                                routingTable.insert(p);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }, scheduler));
            }
            try {
                CompletableFuture.allOf(seedFutures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        if (routingTable.totalPeers() > 0) {
            List<DhtPeer> allPeers = routingTable.getAllPeers();
            int count = Math.min(config.getAlpha(), allPeers.size());
            java.util.List<CompletableFuture<Void>> peerFutures = new ArrayList<>();
            for (DhtPeer p : allPeers.subList(0, count)) {
                peerFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        InetSocketAddress addr = new InetSocketAddress(p.getHost(), p.getPort());
                        List<DhtPeer> peers = findNode(addr, selfId);
                        for (DhtPeer np : peers) {
                            if (!np.getNodeId().equals(selfId.toString())) {
                                routingTable.insert(np);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }, scheduler));
            }
            try {
                CompletableFuture.allOf(peerFutures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        log.debug("DHT routing table: {} peers", routingTable.totalPeers());
    }

    /**
      * 向指定节点发送 查找_节点 查询，获取目标节点附近的节点列表。
     * <p>
     * 启用 KRPC 桥接时使用 KRPC 编码，否则使用 JSON 编码。
     * </p>
     *
     * @param target   目标节点地址
     * @param targetId 目标节点 标识
     * @return 查询到的节点列表
     */
    public List<DhtPeer> findNode(InetSocketAddress target, KademliaNodeId targetId) {
        if (krpcBridge != null) {
            return krpcFindNode(target, targetId);
        }
        DhtMessage msg = DhtMessage.builder()
                .type(DhtMessageType.FIND_NODE)
                .senderId(selfId.toString())
                .senderHost(config.hasAdvertisedAddress() ? config.getAdvertisedHost() : config.getHost())
                .senderPort(config.hasAdvertisedAddress() ? config.getAdvertisedPort() : config.getPort())
                .targetId(targetId.toString())
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            DhtMessage resp = server.send(msg, target, 5000).get(5000, TimeUnit.MILLISECONDS);
            if (resp.getType() == DhtMessageType.FIND_NODE_RESPONSE && resp.getPeers() != null) {
                return fixPeers(resp.getPeers(), target);
            }
        } catch (Exception e) {
            log.debug("findNode to {} failed: {}", target, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
      * 使用 KRPC 协议向指定节点发送 查找_节点 查询。
     *
     * @param target   目标节点地址
     * @param targetId 目标节点 标识
     * @return 查询到的节点列表
     */
    private List<DhtPeer> krpcFindNode(InetSocketAddress target, KademliaNodeId targetId) {
        DhtMessage msg = DhtMessage.builder()
                .type(DhtMessageType.FIND_NODE)
                .senderId(selfId.toString())
                .senderHost(config.hasAdvertisedAddress() ? config.getAdvertisedHost() : config.getHost())
                .senderPort(config.hasAdvertisedAddress() ? config.getAdvertisedPort() : config.getPort())
                .targetId(targetId.toString())
                .timestamp(System.currentTimeMillis())
                .build();
        String txId = krpcBridge.nextTxId();
        byte[] raw = krpcBridge.encodeQuery(msg, txId);
        String pendingKey = txId + "@" + target.getAddress().getHostAddress() + ":" + target.getPort();
        try {
            byte[] rawResp = server.sendRaw(raw, target, pendingKey, 5000).get(5000, TimeUnit.MILLISECONDS);
            DhtMessage resp = krpcBridge.decodeResponse(rawResp, msg);
            if (resp.getPeers() != null && !resp.getPeers().isEmpty()) {
                return fixPeers(resp.getPeers(), target);
            }
        } catch (Exception e) {
            log.warn("KRPC find_node to {} failed: {}", target, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 修正并过滤节点列表（替换 0.0.0.0 地址，过滤无效地址）。
     *
     * @param peers  原始节点列表
     * @param target 请求目标地址（用于替换 0.0.0.0）
     * @return 修正后的节点列表
     */
    private List<DhtPeer> fixPeers(List<DhtPeer> peers, InetSocketAddress target) {
        return peers.stream()
                .map(p -> {
                    if ("0.0.0.0".equals(p.getHost())) {
                        return p.toBuilder().host(target.getHostString()).build();
                    }
                    return p;
                })
                .filter(p -> !"0.0.0.0".equals(p.getHost()))
                .collect(Collectors.toList());
    }

    /**
     * 迭代查找目标节点。
     * <p>
     * 实现 Kademlia 迭代查找算法：从路由表取 alpha 个最接近目标节点的节点，
      * 并行发送 查找_节点，将返回的新节点插入路由表并检查是否找到更近的节点，
     * 直到不再发现更近的节点为止。
     * </p>
     *
     * @param target 目标节点 标识
     * @return 最接近目标节点的 k 个节点
     */
    public List<DhtPeer> iterativeFindNode(KademliaNodeId target) {
        Set<String> visited = new HashSet<>();
        Set<DhtPeer> closest = new HashSet<>(routingTable.findClosestPeers(target, config.getAlpha()));

        while (true) {
            List<DhtPeer> newPeers = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> futures = closest.stream()
                    .filter(p -> !visited.contains(p.getNodeId()))
                    .limit(config.getAlpha())
                    .map(p -> CompletableFuture.runAsync(() -> {
                        visited.add(p.getNodeId());
                        try {
                            InetSocketAddress addr = new InetSocketAddress(p.getHost(), p.getPort());
                            List<DhtPeer> peers = findNode(addr, target);
                            synchronized (newPeers) {
                                newPeers.addAll(peers);
                            }
                        } catch (Exception ignored) {
                        }
                    }))
                    .collect(Collectors.toList());

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(5000, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }

            boolean foundCloser = false;
            List<DhtPeer> snapshot;
            synchronized (newPeers) {
                snapshot = new ArrayList<>(newPeers);
                newPeers.clear();
            }
            for (DhtPeer p : snapshot) {
                if (p.getNodeId().equals(selfId.toString()) || visited.contains(p.getNodeId())) {
                    continue;
                }
                routingTable.insert(p);
                KademliaNodeId pid = KademliaNodeId.fromHex(p.getNodeId());
                long distNow = pid.xor(target).getInt().bitLength();
                boolean isCloser = closest.stream()
                        .noneMatch(c -> KademliaNodeId.fromHex(c.getNodeId())
                                .xor(target).getInt().bitLength() < distNow);
                if (isCloser) {
                    closest.add(p);
                    foundCloser = true;
                }
            }
            if (!foundCloser) {
                break;
            }
        }
        return closest.stream()
                .sorted(Comparator.comparingInt(p ->
                        KademliaNodeId.fromHex(p.getNodeId()).xor(target).getInt().bitLength()))
                .limit(config.getKBucketSize())
                .collect(Collectors.toList());
    }

    /**
     * 存储键值对到 DHT 网络。
     * <p>
      * 先本地存储，然后通过迭代查找找到最接近的 k 个节点并向它们发送 存储 请求。
     * </p>
     *
     * @param key   键
     * @param value 值
     */
    public void store(String key, String value) {
        valueStore.put(key, value);
        KademliaNodeId keyId = KademliaNodeId.fromString(key);
        List<DhtPeer> closest = iterativeFindNode(keyId);
        for (DhtPeer peer : closest) {
            try {
                InetSocketAddress addr = new InetSocketAddress(peer.getHost(), peer.getPort());
                DhtMessage msg = DhtMessage.builder()
                        .type(DhtMessageType.STORE)
                        .senderId(selfId.toString())
                        .senderHost(config.hasAdvertisedAddress() ? config.getAdvertisedHost() : config.getHost())
                        .senderPort(config.hasAdvertisedAddress() ? config.getAdvertisedPort() : config.getPort())
                        .targetId(keyId.toString())
                        .key(key)
                        .value(value)
                        .ttl((int) config.getValueTtlMs())
                        .timestamp(System.currentTimeMillis())
                        .build();
                server.send(msg, addr, 3000);
            } catch (Exception ignored) {
            }
        }
        log.debug("Stored key={} to {} closest peers", key, closest.size());
    }

    /**
     * 从 DHT 网络中查找键对应的值。
     * <p>
      * 先查本地存储，未命中时通过迭代查找找到最接近的节点并发送 查找_值。
     * </p>
     *
     * @param key 键
     * @return 找到的值，未找到返回 空
     */
    public String findValue(String key) {
        String local = valueStore.get(key);
        if (local != null) {
            return local;
        }

        KademliaNodeId keyId = KademliaNodeId.fromString(key);
        List<DhtPeer> closest = iterativeFindNode(keyId);
        for (DhtPeer peer : closest) {
            try {
                InetSocketAddress addr = new InetSocketAddress(peer.getHost(), peer.getPort());
                DhtMessage msg = DhtMessage.builder()
                        .type(DhtMessageType.FIND_VALUE)
                        .senderId(selfId.toString())
                        .senderHost(config.hasAdvertisedAddress() ? config.getAdvertisedHost() : config.getHost())
                        .senderPort(config.hasAdvertisedAddress() ? config.getAdvertisedPort() : config.getPort())
                        .targetId(keyId.toString())
                        .key(key)
                        .timestamp(System.currentTimeMillis())
                        .build();
                DhtMessage resp = server.send(msg, addr, 3000).get(3000, TimeUnit.MILLISECONDS);
                if (resp.getType() == DhtMessageType.FIND_VALUE_RESPONSE && resp.getValue() != null) {
                    return resp.getValue();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 处理接收到的 DHT 消息。
     * <p>
     * 根据消息类型路由到对应的处理方法，并自动将发送者加入路由表。
     * </p>
     *
     * @param msg    接收到的消息
     * @param sender 发送者地址
     */
    /**
      * 接收并处理 DHT 消息（包访问，供 dhtnetty服务端 调用）。
     *
     * @param msg    接收到的消息
     * @param sender 发送者地址
     */
    void receiveMessage(DhtMessage msg, InetSocketAddress sender) {
        handleMessage(msg, sender);
    }

    /**
     * 处理接收到的 DHT 消息（内部实现）。
     *
     * @param msg    接收到的消息
     * @param sender 发送者地址
     */
    private void handleMessage(DhtMessage msg, InetSocketAddress sender) {
        if (msg.getSenderId() != null && !msg.getSenderId().equals(selfId.toString())) {
            DhtPeer peer = DhtPeer.builder()
                    .nodeId(msg.getSenderId())
                    .host(msg.getSenderHost() != null ? msg.getSenderHost() : sender.getHostString())
                    .port(msg.getSenderPort() > 0 ? msg.getSenderPort() : sender.getPort())
                    .lastSeen(System.currentTimeMillis())
                    .build();
            routingTable.insert(peer);
        }

        switch (msg.getType()) {
            case PING:
                handlePing(msg, sender);
                break;
            case FIND_NODE:
                handleFindNode(msg, sender);
                break;
            case FIND_VALUE:
                handleFindValue(msg, sender);
                break;
            case STORE:
                handleStore(msg, sender);
                break;
            case GET_PEERS:
                handleGetPeers(msg, sender);
                break;
            case NAT_DETECT:
                handleNatDetect(msg, sender);
                break;
            default:
                break;
        }
    }

    /**
     * 获取本机对外宣告的地址（用于构建响应消息，支持 NAT 穿透）。
     *
     * @param msg 入站消息（用于从中提取 recipient 地址）
     * @return 本机对外地址字符串
     */
    private String selfHost(DhtMessage msg) {
        String host = config.hasAdvertisedAddress() ? config.getAdvertisedHost()
                : (msg.getRecipientHost() != null && !"0.0.0.0".equals(msg.getRecipientHost())
                ? msg.getRecipientHost() : config.getHost());
        if ("0.0.0.0".equals(host)) {
            host = msg.getSenderHost();
        }
        return host;
    }

    /**
     * 获取本机对外宣告的端口（用于构建响应消息，支持 NAT 穿透）。
     *
     * @param msg 入站消息（用于从中提取 recipient 端口）
     * @return 本机对外端口
     */
    private int selfPort(DhtMessage msg) {
        return config.hasAdvertisedAddress() ? config.getAdvertisedPort()
                : (msg.getRecipientPort() > 0 ? msg.getRecipientPort() : config.getPort());
    }

    /**
     * 发送 DHT 响应消息。
     *
     * @param req   原始请求消息
     * @param resp  响应消息
     * @param target 目标地址
     */
    private void sendResponse(DhtMessage req, DhtMessage resp, InetSocketAddress target) {
        resp.setKrpcTxId(req.getKrpcTxId());
        server.sendNoResponse(resp, target);
    }

    /**
     * 处理 PING 消息，回复 PONG。
     *
     * @param msg    接收到的 PING 消息
     * @param sender 发送者地址
     */
    private void handlePing(DhtMessage msg, InetSocketAddress sender) {
        DhtMessage resp = DhtMessage.builder()
                .type(DhtMessageType.PONG)
                .senderId(selfId.toString())
                .targetId(msg.getSenderId())
                .senderHost(selfHost(msg))
                .senderPort(selfPort(msg))
                .timestamp(System.currentTimeMillis())
                .build();
        sendResponse(msg, resp, sender);
    }

    /**
      * 处理 查找_节点 消息，返回目标节点附近的节点列表。
     *
     * @param msg    接收到的 查找_节点 消息
     * @param sender 发送者地址
     */
    private void handleFindNode(DhtMessage msg, InetSocketAddress sender) {
        KademliaNodeId target = KademliaNodeId.fromHex(msg.getTargetId());
        List<DhtPeer> closest = routingTable.findClosestPeers(target, config.getKBucketSize());
        closest.removeIf(p -> p.getNodeId().equals(msg.getSenderId()));
        if (closest.isEmpty()) {
            closest = List.of(DhtPeer.builder()
                    .nodeId(selfId.toString())
                    .host(selfHost(msg))
                    .port(selfPort(msg))
                    .lastSeen(System.currentTimeMillis())
                    .build());
        }
        DhtMessage resp = DhtMessage.builder()
                .type(DhtMessageType.FIND_NODE_RESPONSE)
                .senderId(selfId.toString())
                .senderHost(selfHost(msg))
                .senderPort(selfPort(msg))
                .targetId(msg.getSenderId())
                .peers(closest)
                .timestamp(System.currentTimeMillis())
                .build();
        sendResponse(msg, resp, sender);
    }

    /**
      * 处理 查找_值 消息。
     * <p>
     * 如果本地有存储的值则返回，否则返回近邻节点列表供请求者继续查找。
     * </p>
     *
     * @param msg    接收到的 查找_值 消息
     * @param sender 发送者地址
     */
    private void handleFindValue(DhtMessage msg, InetSocketAddress sender) {
        String value = valueStore.get(msg.getKey());
        DhtMessage resp;
        if (value != null) {
            resp = DhtMessage.builder()
                    .type(DhtMessageType.FIND_VALUE_RESPONSE)
                    .senderId(selfId.toString())
                    .senderHost(selfHost(msg))
                    .senderPort(selfPort(msg))
                    .targetId(msg.getTargetId())
                    .key(msg.getKey())
                    .value(value)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } else {
            KademliaNodeId keyId = KademliaNodeId.fromString(msg.getKey());
            List<DhtPeer> closest = routingTable.findClosestPeers(keyId, config.getKBucketSize());
            resp = DhtMessage.builder()
                    .type(DhtMessageType.FIND_NODE_RESPONSE)
                    .senderId(selfId.toString())
                    .senderHost(selfHost(msg))
                    .senderPort(selfPort(msg))
                    .targetId(msg.getTargetId())
                    .peers(closest)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
        sendResponse(msg, resp, sender);
    }

    /**
      * 处理 存储 消息，将键值对存入本地存储。
     * @param msg msg
     * @param sender 发送
     */
    private void handleStore(DhtMessage msg, InetSocketAddress sender) {
        if (msg.getKey() != null && msg.getValue() != null) {
            long ttl = msg.getTtl() > 0 ? msg.getTtl() : config.getValueTtlMs();
            valueStore.put(msg.getKey(), msg.getValue(), ttl);
        }
        DhtMessage resp = DhtMessage.builder()
                .type(DhtMessageType.STORE_RESPONSE)
                .senderId(selfId.toString())
                .senderHost(selfHost(msg))
                .senderPort(selfPort(msg))
                .targetId(msg.getTargetId())
                .timestamp(System.currentTimeMillis())
                .build();
        sendResponse(msg, resp, sender);
    }

    /**
     * 处理 NAT_DETECT 消息，在响应中携带发送者的来源地址。
     * @param msg msg
     * @param sender 发送
     */
    private void handleNatDetect(DhtMessage msg, InetSocketAddress sender) {
        DhtMessage resp = DhtMessage.builder()
                .type(DhtMessageType.NAT_DETECT_RESPONSE)
                .senderId(selfId.toString())
                .targetId(msg.getSenderId())
                .senderHost(sender.getHostString())
                .senderPort(sender.getPort())
                .timestamp(System.currentTimeMillis())
                .build();
        sendResponse(msg, resp, sender);
    }

    /**
      * 处理 获取_PEERS 消息，返回目标 infohash 附近的节点列表。
     *
     * @param msg    接收到的 获取_PEERS 消息
     * @param sender 发送者地址
     */
    private void handleGetPeers(DhtMessage msg, InetSocketAddress sender) {
        String infohash = msg.getInfohash() != null ? msg.getInfohash() : msg.getTargetId();
        if (infohash != null && !infohash.isEmpty()) {
            boolean isNew = infohashes.add(infohash);
            if (crawlListener != null) {
                crawlListener.onInfohash(infohash, sender);
            }
            if (isNew) {
                CompletableFuture.runAsync(() -> activeCrawl(infohash), scheduler);
            }
        }
        KademliaNodeId target = KademliaNodeId.fromHex(msg.getTargetId());
        List<DhtPeer> closest = routingTable.findClosestPeers(target, config.getKBucketSize());
        closest.removeIf(p -> p.getNodeId().equals(msg.getSenderId()));
        if (closest.isEmpty()) {
            closest = List.of(DhtPeer.builder()
                    .nodeId(selfId.toString())
                    .host(selfHost(msg))
                    .port(selfPort(msg))
                    .lastSeen(System.currentTimeMillis())
                    .build());
        }
        String token = Integer.toHexString((sender.getHostString() + ":" + sender.getPort()).hashCode());
        DhtMessage resp = DhtMessage.builder()
                .type(DhtMessageType.GET_PEERS_RESPONSE)
                .senderId(selfId.toString())
                .senderHost(selfHost(msg))
                .senderPort(selfPort(msg))
                .targetId(msg.getSenderId())
                .peers(closest)
                .value(token)
                .infohash(infohash)
                .timestamp(System.currentTimeMillis())
                .build();
        sendResponse(msg, resp, sender);
    }

    /**
      * 使用 KRPC 协议向指定节点发送 获取_PEERS 查询。
     *
     * @param target   目标节点地址
     * @param infohash 目标 infohash
     * @return 查询到的 peer 列表
     */
    public List<DhtPeer> krpcGetPeers(InetSocketAddress target, String infohash) {
        if (krpcBridge == null) {
            return Collections.emptyList();
        }
        DhtMessage msg = DhtMessage.builder()
                .type(DhtMessageType.GET_PEERS)
                .senderId(selfId.toString())
                .senderHost(config.hasAdvertisedAddress() ? config.getAdvertisedHost() : config.getHost())
                .senderPort(config.hasAdvertisedAddress() ? config.getAdvertisedPort() : config.getPort())
                .targetId(infohash)
                .infohash(infohash)
                .timestamp(System.currentTimeMillis())
                .build();
        String txId = krpcBridge.nextTxId();
        byte[] raw = krpcBridge.encodeQuery(msg, txId);
        String pendingKey = txId + "@" + target.getAddress().getHostAddress() + ":" + target.getPort();
        try {
            byte[] rawResp = server.sendRaw(raw, target, pendingKey, 5000).get(5000, TimeUnit.MILLISECONDS);
            DhtMessage resp = krpcBridge.decodeResponse(rawResp, msg);
            if (resp != null && resp.getPeers() != null && !resp.getPeers().isEmpty()) {
                return fixPeers(resp.getPeers(), target);
            }
        } catch (Exception e) {
            log.warn("KRPC get_peers to {} failed: {}", target, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 主动爬取：针对一个已发现的 infohash，向路由表中离它最近的若干节点
      * 并行发送 获取_peers 查询，收集其中的 BT 节点（值）。
     * <p>
      * 被动发现（收到他人 获取_peers 查询）只能得到 infohash，必须主动查询
     * 才能拿到真实的 BT peer 列表，进而用 BEP 9 解析种子名。
     * </p>
     *
     * @param infohash 20 字节十六进制 infohash
     * @return 收集到的 BT peer 列表（可能为空）
     */
    public List<DhtPeer> activeCrawl(String infohash) {
        if (krpcBridge == null || infohash == null || infohash.isEmpty()) {
            return Collections.emptyList();
        }
        KademliaNodeId target = KademliaNodeId.fromHex(infohash);
        List<DhtPeer> closest = routingTable.findClosestPeers(target, config.getAlpha() * 3);
        if (closest.isEmpty()) {
            return Collections.emptyList();
        }

        List<DhtPeer> collected = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = closest.stream()
                .map(p -> CompletableFuture.runAsync(() -> {
                    try {
                        InetSocketAddress addr = new InetSocketAddress(p.getHost(), p.getPort());
                        List<DhtPeer> peers = krpcGetPeers(addr, infohash);
                        synchronized (collected) {
                            collected.addAll(peers);
                        }
                    } catch (Exception ignored) {
                    }
                }, scheduler))
                .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(Math.max(config.getPeerTimeoutMs(), 5000), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }

        if (!collected.isEmpty() && crawlListener != null) {
            List<DhtPeer> copy = new ArrayList<>(collected);
            crawlListener.onPeers(infohash, copy);
        }
        return collected;
    }

    /**
     * 获取被动收集到的 infohash 集合。
     *
     * @return infohash 集合
     */
    public Set<String> collectedInfohashes() {
        return Collections.unmodifiableSet(infohashes);
    }

    /**
     * 通过 infohash 查找 BT 种子名称。
     *
     * @param hexInfohash 十六进制 infohash
     * @param timeoutMs   超时时间（毫秒）
     * @return 种子名称，未找到返回 空
     */
    public String lookupName(String hexInfohash, int timeoutMs) {
        byte[] infoHash = new byte[20];
        for (int i = 0; i < 20; i++) {
            infoHash[i] = (byte) Integer.parseInt(hexInfohash.substring(i * 2, i * 2 + 2), 16);
        }
        KademliaNodeId target = KademliaNodeId.fromHex(hexInfohash);
        List<DhtPeer> closest = routingTable.findClosestPeers(target, config.getAlpha() * 2);
        for (DhtPeer p : closest) {
            try {
                InetSocketAddress addr = new InetSocketAddress(p.getHost(), p.getPort());
                List<DhtPeer> btPeers = krpcGetPeers(addr, hexInfohash);
                for (DhtPeer bt : btPeers) {
                    var result = new com.chua.common.support.network.server.dht.bep9.MetadataDownloader()
                            .download(new InetSocketAddress(bt.getHost(), bt.getPort()), infoHash, timeoutMs);
                    if (result.ok) {
                        return result.name;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 重新发布本地存储的所有值到 DHT 网络。
     */
    public void republish() {
        Map<String, String> all = valueStore.getAll();
        for (Map.Entry<String, String> e : all.entrySet()) {
            store(e.getKey(), e.getValue());
        }
    }

    /**
     * 检测外部地址（NAT 穿透）。
     * <p>
      * 向指定的 STUN 类型节点发送 NAT_DETECT 消息，通过响应中的 发送 地址获取公网地址。
     * </p>
     *
     * @param stunServer STUN 服务器地址
     * @return 检测到的外部地址，失败返回 空
     */
    public InetSocketAddress detectExternalAddress(InetSocketAddress stunServer) {
        try {
            DhtMessage msg = DhtMessage.builder()
                    .type(DhtMessageType.NAT_DETECT)
                    .senderId(selfId.toString())
                    .senderHost(config.getHost())
                    .senderPort(config.getPort())
                    .timestamp(System.currentTimeMillis())
                    .build();
            DhtMessage resp = server.send(msg, stunServer, 5000).get(5000, TimeUnit.MILLISECONDS);
            if (resp.getType() == DhtMessageType.NAT_DETECT_RESPONSE) {
                return new InetSocketAddress(resp.getSenderHost(), resp.getSenderPort());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 服务监听器条目，持有服务名和对应的监听器。
     * @author CH
     * @since 4.0.0
     */
    public static class ServiceListenerEntry {

        /**
         * 服务路径。
         */
        public final String path;

        /**
         * 变更监听器。
         */
        public final ServiceListener listener;

        /**
         * 构造监听器条目。
         *
         * @param path     服务路径
         * @param listener 监听器
         */
        public ServiceListenerEntry(String path, ServiceListener listener) {
            this.path = path;
            this.listener = listener;
        }
    }

    /**
     * 服务变更监听器接口。
     * @author CH
     * @since 4.0.0
     */
    public interface ServiceListener {

        /**
         * 服务值发生变更时回调。
         *
         * @param path  服务路径
         * @param value 新的值
         */
        void onChange(String path, String value);
    }

    @Override
    /** 关闭 */
    public void close() {
        scheduler.shutdown();
        server.close();
    }
}
