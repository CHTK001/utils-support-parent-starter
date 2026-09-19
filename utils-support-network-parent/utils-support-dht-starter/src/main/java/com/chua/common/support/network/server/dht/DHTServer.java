package com.chua.common.support.network.server.dht;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.dht.krpc.KrpcDhtBridge;
import com.chua.common.support.network.server.dht.krpc.KrpcMessage;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DHT 服务器，提供链式构建的 DHT 节点服务。
 * <p>
 * 封装 {@link DhtProtocol} 和 {@link DhtNettyServer}，提供便捷的链式 API 和
 * 内置 KRPC 协议桥接支持，可与标准 钻头torrent DHT 网络互通。
 * </p>
 *
 * <pre>{@code
 * DHTServer server = DHTServer.builder()
 *     .port(6881)
 *     .addSeed("router.bittorrent.com:6881")
 *     .addSeed("dht.transmissionbt.com:6881")
 *     .build();
 * server.start();
 * }</pre>d();
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DHTServer extends AbstractServer {

    /**
     * DHT 配置。
     */
    private final DhtConfig config;

    /**
     * 本地节点 标识。
     */
    private final KademliaNodeId selfId;

    /**
     * DHT 协议引擎。
     */
    private DhtProtocol protocol;

    /**
     * KRPC 桥接器。
     */
    private KrpcDhtBridge bridge;

    /**
     * DHT 爬取监听器。
     */
    private DhtCrawlListener crawlListener;

    /**
     * 创建 dht服务端 实例
     * @param config 配置
     * @param selfId kademlia节点标识
     * @param selfId selfid
     */
    private DHTServer(DhtConfig config, KademliaNodeId selfId) {
        super(createSetting(config));
        this.config = config;
        this.selfId = selfId;
    }

    /**
     * 创建新的 dht服务端 构建器。
     *
     * @return DhtServerBuilder 实例
     */
    public static DhtServerBuilder builder() {
        return new DhtServerBuilder();
    }

    /**
     * 创建Setting
     *
     * @param config 配置
     * @return 创建setting的结果
     */
    private static ServerSetting createSetting(DhtConfig config) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setProtocol("dht");
        setting.setHost(config.getHost() != null ? config.getHost() : "0.0.0.0");
        setting.setPort(config.getPort() > 0 ? config.getPort() : 6881);
        return setting;
    }

    @Override
    /**
     * 获取协议类型
    */
    public ProtocolType getProtocolType() {
        return ProtocolType.UDP;
    }

    @Override
    /**
     * 执行开始
    */
    protected void doStart() {
        try {
            this.bridge = new KrpcDhtBridge();
            this.protocol = new DhtProtocol(config, selfId);
            protocol.enableKrpc(bridge);
            if (crawlListener != null) {
                protocol.setCrawlListener(crawlListener);
            }
            DhtNettyServer transport = protocol.server();
            transport.setRawMessageHandler(this::handleKrpcMessage);
            transport.setResponseEncoder((msg, target) -> {
                String txId = msg.getKrpcTxId() != null ? msg.getKrpcTxId() : bridge.nextTxId();
                byte[] raw = bridge.encodeResponse(msg, txId);
                transport.sendNoResponseRaw(raw, target);
            });
            protocol.start();
            log.info("DHTServer started. NodeId={}, port={}", selfId, config.getPort());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start DHTServer", e);
        }
    }

    @Override
    /**
     * 执行停止
    */
    protected void doStop() {
        if (protocol != null) {
            protocol.close();
        }
    }

    /**
     * 处理krpc消息
     *
     * @param data 数据
     * @param sender 发送
     */
    private void handleKrpcMessage(byte[] data, InetSocketAddress sender) {
        try {
            KrpcMessage krpc = KrpcMessage.parse(data);
            if (krpc.y == 'q') {
                DhtMessage msg = bridge.decodeQuery(krpc, sender);
                protocol.receiveMessage(msg, sender);
            }
        } catch (Exception e) {
            log.debug("KRPC message error from {}: {}", sender, e.getMessage());
        }
    }

    /**
     * 获取内部协议引擎。
     *
     * @return DhtProtocol 实例
     */
    public DhtProtocol protocol() {
        return protocol;
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
     * 获取 KRPC 桥接器。
     *
     * @return KrpcDhtBridge 实例
     */
    public KrpcDhtBridge bridge() {
        return bridge;
    }

    /**
     * 向指定节点发送 查找_节点 查询。
     *
     * @param target   目标节点地址
     * @param targetId 目标节点 标识
     * @return 查询到的节点列表
     */
    public List<DhtPeer> findNode(InetSocketAddress target, KademliaNodeId targetId) {
        return protocol.findNode(target, targetId);
    }

    /**
     * 迭代查找目标节点，返回最接近的 k 个节点。
     *
     * @param target 目标节点 标识
     * @return 最接近的节点列表
     */
    public List<DhtPeer> iterativeFindNode(KademliaNodeId target) {
        return protocol.iterativeFindNode(target);
    }

    /**
     * 将键值对存储到 DHT 网络。
     *
     * @param key   键
     * @param value 值
     */
    public void store(String key, String value) {
        protocol.store(key, value);
    }

    /**
     * 从 DHT 网络中查找键对应的值。
     *
     * @param key 键
     * @return 找到的值，未找到返回 空
     */
    public String findValue(String key) {
        return protocol.findValue(key);
    }

    /**
     * 获取被动收集到的 infohash 集合。
     *
     * @return infohash 集合
     */
    public Set<String> collectedInfohashes() {
        return protocol.collectedInfohashes();
    }

    /**
     * 向指定节点发送 KRPC 获取_peers 查询。
     *
     * @param target   目标节点地址
     * @param infohash 目标 infohash
     * @return 查询到的 peer 列表
     */
    public List<DhtPeer> krpcGetPeers(InetSocketAddress target, String infohash) {
        return protocol.krpcGetPeers(target, infohash);
    }

    /**
     * 通过 infohash 查找 BT 种子名称（BEP 9）。
     *
     * @param hexInfohash 十六进制 infohash
     * @param timeoutMs   超时时间（毫秒）
     * @return 种子名称，未找到返回 空
     */
    public String lookupName(String hexInfohash, int timeoutMs) {
        return protocol.lookupName(hexInfohash, timeoutMs);
    }

    /**
     * 添加种子节点地址。
     *
     * @param seed 种子节点地址，格式 "主机:端口"
     */
    public void addSeed(String seed) {
        protocol.addSeed(seed);
    }

    /**
     * 执行 Bootstrap，加入 DHT 网络。
     */
    public void bootstrap() {
        protocol.bootstrap();
    }

    /**
     * 检测外部地址（NAT 穿透）。
     *
     * @param stunServer STUN 服务器地址
     * @return 检测到的外部地址，失败返回 空
     */
    public InetSocketAddress detectExternalAddress(InetSocketAddress stunServer) {
        return protocol.detectExternalAddress(stunServer);
    }

    /**
     * 全网公开的 钻头torrent DHT 引导节点地址，默认包含。
     */
    private static final Set<String> DEFAULT_SEEDS = Set.of(
            "router.bittorrent.com:6881",
            "dht.transmissionbt.com:6881",
            "dht.aelitis.com:6881",
            "router.utorrent.com:6881"
    );

    /**
     * dht服务端 构建器，支持链式调用。
     * @author CH
     * @since 4.0.0
     */
    public static class DhtServerBuilder {

        /**
         * UDP 监听端口，默认 6881。
         */
        private int port = 6881;

        /**
         * 绑定主机地址，默认 0.0.0.0。
         */
        private String host = "0.0.0.0";

        /**
         * 固定节点 标识（hex 格式），为空则随机生成。
         */
        private String nodeId;

        /**
         * K-Bucket 容量（K 值），默认 8。
         */
        private int kBucketSize = 8;

        /**
         * 并行查询节点数（Alpha 值），默认 3。
         */
        private int alpha = 3;

        /**
         * 自动 Bootstrap 间隔（毫秒），默认 60000。
         */
        private long bootstrapIntervalMs = 60_000;

        /**
         * 重新发布间隔（毫秒），默认 300000。
         */
        private long republishIntervalMs = 300_000;

        /**
         * 存储值生存时间（毫秒），默认 600000。
         */
        private long valueTtlMs = 600_000;

        /**
         * 节点超时时间（毫秒），默认 30000。
         */
        private long peerTimeoutMs = 30_000;

        /**
         * 对外宣告的主机地址（NAT 穿透）。
         */
        private String advertisedHost;

        /**
         * 对外宣告的端口（NAT 穿透）。
         */
        private int advertisedPort;

        /**
         * DHT 爬取监听器。
         */
        private DhtCrawlListener crawlListener;

        /**
         * 种子节点地址集合。
         */
        private Set<String> seeds = new LinkedHashSet<>(DEFAULT_SEEDS);

        /**
         * 构造 dht服务端 构建器。
         */
        DhtServerBuilder() {
        }

        /**
         * 设置 DHT 爬取监听器。
         *
         * @param listener 监听器实例
         * @return this
         */
        public DhtServerBuilder crawlListener(DhtCrawlListener listener) {
            this.crawlListener = listener;
            return this;
        }

        /**
         * 设置 UDP 监听端口。
         *
         * @param port 端口号
         * @return this
         */
        public DhtServerBuilder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置绑定主机地址。
         *
         * @param host 主机地址
         * @return this
         */
        public DhtServerBuilder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置固定节点 标识（hex 格式），为空则随机生成。
         *
         * @param nodeId 40 字符 hex 字符串
         * @return this
         */
        public DhtServerBuilder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * 设置 K-Bucket 容量（K 值）。
         *
         * @param kBucketSize K 值，默认 8
         * @return this
         */
        public DhtServerBuilder kBucketSize(int kBucketSize) {
            this.kBucketSize = kBucketSize;
            return this;
        }

        /**
         * 设置并行查询节点数（Alpha 值）。
         *
         * @param alpha Alpha 值，默认 3
         * @return this
         */
        public DhtServerBuilder alpha(int alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * 设置自动 Bootstrap 间隔（毫秒）。
         *
         * @param intervalMs 间隔时间，默认 60000
         * @return this
         */
        public DhtServerBuilder bootstrapIntervalMs(long intervalMs) {
            this.bootstrapIntervalMs = intervalMs;
            return this;
        }

        /**
         * 设置重新发布间隔（毫秒）。
         *
         * @param intervalMs 间隔时间，默认 300000
         * @return this
         */
        public DhtServerBuilder republishIntervalMs(long intervalMs) {
            this.republishIntervalMs = intervalMs;
            return this;
        }

        /**
         * 设置存储值生存时间（毫秒）。
         *
         * @param ttlMs 生存时间，默认 600000
         * @return this
         */
        public DhtServerBuilder valueTtlMs(long ttlMs) {
            this.valueTtlMs = ttlMs;
            return this;
        }

        /**
         * 设置节点超时时间（毫秒）。
         *
         * @param timeoutMs 超时时间，默认 30000
         * @return this
         */
        public DhtServerBuilder peerTimeoutMs(long timeoutMs) {
            this.peerTimeoutMs = timeoutMs;
            return this;
        }

        /**
         * 设置对外宣告的主机地址（NAT 穿透）。
         *
         * @param advertisedHost 公网主机地址
         * @return this
         */
        public DhtServerBuilder advertisedHost(String advertisedHost) {
            this.advertisedHost = advertisedHost;
            return this;
        }

        /**
         * 设置对外宣告的端口（NAT 穿透）。
         *
         * @param advertisedPort 公网端口
         * @return this
         */
        public DhtServerBuilder advertisedPort(int advertisedPort) {
            this.advertisedPort = advertisedPort;
            return this;
        }

        /**
         * 添加一个种子节点地址。
         *
         * @param seed 种子地址，格式 "主机:端口"
         * @return this
         */
        public DhtServerBuilder addSeed(String seed) {
            this.seeds.add(seed);
            return this;
        }

        /**
         * 设置种子节点地址（覆盖默认的公开引导节点）。
         *
         * @param seeds 种子地址数组，格式 "主机:端口"
         * @return this
         */
        public DhtServerBuilder seeds(String... seeds) {
            this.seeds.clear();
            Collections.addAll(this.seeds, seeds);
            return this;
        }

        /**
         * 构建 dht服务端 实例。
         *
         * @return DHTServer 实例
         */
        public DHTServer build() {
            KademliaNodeId id = nodeId != null && !nodeId.isEmpty()
                    ? KademliaNodeId.fromHex(nodeId)
                    : KademliaNodeId.random();
            DhtConfig config = DhtConfig.builder()
                    .port(port)
                    .host(host)
                    .kBucketSize(kBucketSize)
                    .alpha(alpha)
                    .bootstrapIntervalMs(bootstrapIntervalMs)
                    .republishIntervalMs(republishIntervalMs)
                    .valueTtlMs(valueTtlMs)
                    .peerTimeoutMs(peerTimeoutMs)
                    .advertisedHost(advertisedHost)
                    .advertisedPort(advertisedPort)
                    .nodeId(id.toString())
                    .seeds(seeds)
                    .build();
            DHTServer server = new DHTServer(config, id);
            server.crawlListener = this.crawlListener;
            return server;
        }
    }
}
