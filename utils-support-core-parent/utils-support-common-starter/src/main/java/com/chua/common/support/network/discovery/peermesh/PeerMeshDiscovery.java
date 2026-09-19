package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.exception.StartFailedException;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;
import com.chua.common.support.utils.ThreadUtils;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PeerMesh 服务发现实现。
 * <p>
 * 基于 TCP 的 P2P 服务发现，支持 C / SEED / C-SEED 三种启动发现模式，
 * 并定期进行成员心跳和超时剔除。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("peerMesh")
@SpiOrder(Integer.MAX_VALUE)
public class PeerMeshDiscovery extends AbstractServiceDiscovery {

    /**
     * 内存成员表
     */
    private final NodeTable nodeTable = new NodeTable();

    /**
     * 磁盘持久化
     */
    private DiskStore diskStore;

    /**
     * 本地 serverId
     */
    private final String serverId = UUID.randomUUID().toString();

    /**
     * Mesh 配置
     */
    private MeshConfig config = new MeshConfig();

    /**
     * 本地绑定 IP
     */
    private String localIp;

    /**
     * 本地监听端口
     */
    private int localPort;

    /**
     * 本地节点信息
     */
    private Discovery self;

    /**
     * 服务器 Socket
     */
    private ServerSocket serverSocket;

    /**
     * 连接处理线程池
     */
    private ExecutorService executor;

    /**
     * 定时任务线程池
     */
    private ScheduledExecutorService scheduler;

    /**
     * 心跳传播组件
     */
    private MembershipPropagation membershipPropagation;

    /**
     * 超时剔除组件
     */
    private EvictionManager evictionManager;

    /**
     * 运行状态标志
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * UDP 服务器 Socket（仅 mode=udp 时使用）
     */
    private java.net.DatagramSocket udpSocket;

    /**
     * UDP 服务器执行器
     */
    private ExecutorService udpExecutor;

    // ======================== 构造方法 ========================

    /**
     * 默认构造函数。
     */
    public PeerMeshDiscovery() {
        this(new DiscoveryOption());
    }

    /**
     * 带选项的构造函数。
     *
     * @param discoveryOption 发现选项
     */
    public PeerMeshDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
        this.diskStore = new DiskStore(config.getPeersFile());
    }

    // ======================== 生命周期 ========================

    @Override
    /** 开始 */
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        InterfaceSelector selector = new InterfaceSelector();
        localIp = selector.select(config.getBindIp(), config.getBindInterface());

        List<NodeTable.NodeEntry> loaded = diskStore.load();
        long now = System.currentTimeMillis();
        for (NodeTable.NodeEntry entry : loaded) {
            nodeTable.upsert(entry.getDiscovery().getServerId(), entry.getDiscovery(), entry.getEpoch(), now);
        }

        bindPort();

        self = Discovery.builder()
                .serverId(serverId)
                .host(localIp)
                .port(localPort)
                .protocol(config.getMode())
                .build();
        nodeTable.upsert(serverId, self, 0, System.currentTimeMillis());

        startAcceptor();

        startScheduler();

        if ("udp".equalsIgnoreCase(config.getMode())) {
            startUdpServer();
        }

        BootstrapProbe probe = new BootstrapProbe(config, nodeTable, selector, serverId, localPort, diskStore, localIp);
        probe.run();

        NodeTable.NodeEntry selfEntry = nodeTable.get(serverId);
        if (selfEntry != null && membershipPropagation != null) {
            membershipPropagation.pushNewPeer(selfEntry);
        }

        log.info("PeerMesh 启动完成: serverId={}, host={}, port={}, peers={}",
                serverId, localIp, localPort, nodeTable.size());
    }

    /**
    * 尝试绑定端口。
    *
    * @throws IOException IO 异常
    */
    private void bindPort() throws IOException {
        int mainPort = config.getPort();
        int altPort = config.getAltPort();
        IOException lastException = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            int tryPort = (attempt == 0) ? mainPort : altPort;
            try {
                serverSocket = new ServerSocket();
                serverSocket.bind(new InetSocketAddress(localIp, tryPort));
                localPort = tryPort;
                return;
            } catch (IOException e) {
                lastException = e;
            }
        }

        throw new StartFailedException("无法绑定端口，已尝试: " + mainPort + ", " + altPort, lastException);
    }

    /**
     * 启动连接接受线程。
     */
    private void startAcceptor() {
        executor = Executors.newThreadPerTaskExecutor(ThreadUtils.newDaemonThreadFactory("peer-mesh-acceptor"));
        executor.submit(() -> {
            while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    executor.submit(() -> handleConnection(socket));
                } catch (IOException e) {
                    if (running.get()) {
                        log.debug("接受连接异常: {}", e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * 启动调度器（心跳 + 剔除）。
     */
    private void startScheduler() {
        scheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("peer-mesh-scheduler");

        membershipPropagation = new MembershipPropagation(config, nodeTable, serverId, this);
        evictionManager = new EvictionManager(config, nodeTable, serverId, this);

        int interval = Math.max(1, config.getHeartbeatInterval());
        scheduler.scheduleAtFixedRate(() -> {
            try {
                membershipPropagation.sendHeartbeat();
            } catch (Exception e) {
                log.warn("心跳发送异常: {}", e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                evictionManager.evict();
            } catch (Exception e) {
                log.warn("执行剔除异常: {}", e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    // ======================== UDP 服务器（mode=udp） ========================

    /**
     * 启动 UDP 服务器，监听发现消息。
     *
     * @throws IOException IO 异常
     */
    private void startUdpServer() throws IOException {
        udpSocket = new DatagramSocket(null);
        udpSocket.setReuseAddress(true);
        udpSocket.bind(new InetSocketAddress(localIp, localPort));
        udpSocket.setSoTimeout(1000);
        udpExecutor = ThreadUtils.newDaemonSingleThreadExecutor("peer-mesh-udp");
        udpExecutor.submit(() -> {
            byte[] buf = new byte[65507];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (running.get() && udpSocket != null && !udpSocket.isClosed()) {
                try {
                    udpSocket.receive(packet);
                    handleUdpPacket(packet);
                } catch (java.net.SocketTimeoutException e) {
                    // 正常超时，继续循环
                } catch (IOException e) {
                    if (running.get()) {
                        log.warn("UDP 接收异常: {}", e.getMessage());
                    }
                }
            }
        });
        log.info("UDP 服务器已启动，端口={}", localPort);
    }

    /**
     * 处理收到的 UDP 数据包。
     *
     * @param packet 数据包
     */
    private void handleUdpPacket(DatagramPacket packet) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
            MessageProtocol.PeerMeshMessage msg = MessageProtocol.decode(buf);
            if (msg == null) {
                return;
            }
            switch (msg.type()) {
                case MessageProtocol.TYPE_HEARTBEAT -> {
                    List<NodeTable.NodeEntry> entries = Json.fromJsonToList(msg.payload(), NodeTable.NodeEntry.class);
                    if (entries != null) {
                        long now = System.currentTimeMillis();
                        for (NodeTable.NodeEntry entry : entries) {
                            mergeNodeEntry(entry, now);
                        }
                    }
                    List<NodeTable.NodeEntry> known = new ArrayList<>(nodeTable.getAllEntries().values());
                    MessageProtocol.PeerMeshMessage pong = new MessageProtocol.PeerMeshMessage(
                            MessageProtocol.TYPE_PONG, Json.toJson(known));
                    sendUdpResponse(packet.getAddress(), packet.getPort(), pong);
                }
                case MessageProtocol.TYPE_NEW_PEER -> {
                    NodeTable.NodeEntry entry = Json.fromJson(msg.payload(), NodeTable.NodeEntry.class);
                    if (entry != null && !serverId.equals(entry.getDiscovery().getServerId())) {
                        mergeNodeEntry(entry, System.currentTimeMillis());
                    }
                    sendUdpResponse(packet.getAddress(), packet.getPort(),
                            new MessageProtocol.PeerMeshMessage(MessageProtocol.TYPE_ACK, ""));
                }
                case MessageProtocol.TYPE_PROBE -> {
                    List<NodeTable.NodeEntry> known = new ArrayList<>(nodeTable.getAllEntries().values());
                    sendUdpResponse(packet.getAddress(), packet.getPort(),
                            new MessageProtocol.PeerMeshMessage(MessageProtocol.TYPE_PONG, Json.toJson(known)));
                }
                case MessageProtocol.TYPE_PONG -> {
                    List<NodeTable.NodeEntry> entries = Json.fromJsonToList(msg.payload(), NodeTable.NodeEntry.class);
                    if (entries != null) {
                        long now = System.currentTimeMillis();
                        for (NodeTable.NodeEntry entry : entries) {
                            mergeNodeEntry(entry, now);
                        }
                    }
                }
                default -> log.debug("UDP 未知消息类型: {}", msg.type());
            }
        } catch (Exception e) {
            log.debug("处理 UDP 包异常: {}", e.getMessage());
        }
    }

    /**
     * 通过 UDP 发送响应。
     *
     * @param addr 目标地址
     * @param port 目标端口
     * @param msg  消息体
     */
    private void sendUdpResponse(java.net.InetAddress addr, int port,
                                 MessageProtocol.PeerMeshMessage msg) {
        try {
            byte[] data = MessageProtocol.encode(msg);
            DatagramPacket response = new DatagramPacket(data, data.length, addr, port);
            udpSocket.send(response);
        } catch (IOException e) {
            log.debug("UDP 发送失败: {}", e.getMessage());
        }
    }

    /**
     * 通过 UDP 向目标节点发送一条消息（UDP 模式下心跳/成员传播使用）。
     * 响应由 UDP 服务器线程统一接收处理。
     *
     * @param target 目标节点
     * @param msg    消息体
     */
    public void sendUdpMessage(Discovery target, MessageProtocol.PeerMeshMessage msg) {
        if (udpSocket == null || udpSocket.isClosed() || target == null) {
            return;
        }
        try {
            byte[] data = MessageProtocol.encode(msg);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    InetAddress.getByName(target.getHost()), target.getPort());
            udpSocket.send(packet);
        } catch (IOException e) {
            log.debug("UDP 发送失败至 {}: {}", target.getServerId(), e.getMessage());
        }
    }

    // ======================== 配置扩展 ========================

    /**
     * 替换当前配置并重建磁盘存储。
     * <p>
     * 必须在 {@link #start()} 之前调用。
     * </p>
     *
     * @param config 新的配置
     */
    public void setConfig(MeshConfig config) {
        this.config = config;
        this.diskStore = new DiskStore(config.getPeersFile());
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
        }
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
        if (udpExecutor != null) {
            udpExecutor.shutdownNow();
            udpExecutor = null;
        }

        membershipPropagation = null;
        evictionManager = null;

        diskStore.save(new ArrayList<>(nodeTable.getAllEntries().values()));
        clearCache();

        log.info("PeerMesh 已停止");
    }

    // ======================== 连接处理 ========================

    /**
     * 处理入站连接。
     *
     * @param socket 已接受的 Socket
     */
    private void handleConnection(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 主动发送 NewPeer，宣告自己
            try {
                NodeTable.NodeEntry selfEntry = nodeTable.get(serverId);
                Discovery announce = (selfEntry != null && selfEntry.getDiscovery() != null)
                        ? selfEntry.getDiscovery() : self;
                NodeTable.NodeEntry myEntry = new NodeTable.NodeEntry(announce, System.currentTimeMillis(), 0);
                MessageProtocol.PeerMeshMessage newPeer = new MessageProtocol.PeerMeshMessage(
                        MessageProtocol.TYPE_NEW_PEER, Json.toJson(myEntry));
                MessageProtocol.write(out, newPeer);
                out.flush();
            } catch (IOException e) {
                log.debug("发送 NewPeer 失败", e);
                // 继续尝试读取消息
            }

            while (running.get() && !socket.isClosed()) {
                MessageProtocol.PeerMeshMessage msg = MessageProtocol.read(in);
                if (msg == null) {
                    break;
                }
                processMessage(in, out, msg);
            }
        } catch (IOException e) {
            // 连接关闭，忽略
        } catch (Exception e) {
            log.debug("处理连接异常: {}", e.getMessage());
        }
    }

    /**
     * 处理消息。
     *
     * @param in  输入流
     * @param out 输出流
     * @param msg 消息体
     */
    private void processMessage(DataInputStream in, DataOutputStream out,
                                MessageProtocol.PeerMeshMessage msg) throws IOException {
        switch (msg.type()) {
            case MessageProtocol.TYPE_HEARTBEAT -> handleHeartbeat(msg, out);
            case MessageProtocol.TYPE_NEW_PEER -> handleNewPeer(msg, out);
            case MessageProtocol.TYPE_PONG -> handlePong(msg);
            case MessageProtocol.TYPE_ACK -> {
                // 确认消息，无需处理
            }
            default -> log.debug("收到未知消息类型: {}", msg.type());
        }
    }

    /**
     * 处理心跳消息。
     *
     * @param msg 消息
     * @param out 输出流
     */
    private void handleHeartbeat(MessageProtocol.PeerMeshMessage msg, DataOutputStream out) throws IOException {
        List<NodeTable.NodeEntry> entries = Json.fromJsonToList(msg.payload(), NodeTable.NodeEntry.class);
        if (entries != null) {
            long now = System.currentTimeMillis();
            for (NodeTable.NodeEntry entry : entries) {
                mergeNodeEntry(entry, now);
            }
        }
        // 回复 PONG
        List<NodeTable.NodeEntry> known = new ArrayList<>(nodeTable.getAllEntries().values());
        MessageProtocol.write(out, new MessageProtocol.PeerMeshMessage(
                MessageProtocol.TYPE_PONG, Json.toJson(known)));
    }

    /**
     * 处理 PONG 消息。
     *
     * @param msg 消息
     */
    private void handlePong(MessageProtocol.PeerMeshMessage msg) {
        List<NodeTable.NodeEntry> entries = Json.fromJsonToList(msg.payload(), NodeTable.NodeEntry.class);
        if (entries != null) {
            long now = System.currentTimeMillis();
            for (NodeTable.NodeEntry entry : entries) {
                mergeNodeEntry(entry, now);
            }
        }
    }

    /**
     * 处理新节点通知。
     *
     * @param msg 消息
     * @param out 输出流
     */
    private void handleNewPeer(MessageProtocol.PeerMeshMessage msg, DataOutputStream out) throws IOException {
        NodeTable.NodeEntry entry = Json.fromJson(msg.payload(), NodeTable.NodeEntry.class);
        if (entry != null && !serverId.equals(entry.getDiscovery().getServerId())) {
            mergeNodeEntry(entry, System.currentTimeMillis());
        }
        MessageProtocol.write(out, new MessageProtocol.PeerMeshMessage(
                MessageProtocol.TYPE_ACK, ""));
    }

    /**
     * 将远程节点条目合并到本地状态。
     *
     * @param entry 远程节点条目
     * @param nowMs 当前时间戳
     */
    private void mergeNodeEntry(NodeTable.NodeEntry entry, long nowMs) {
        if (entry == null) {
            return;
        }
        String sid = entry.getDiscovery().getServerId();
        if (serverId.equals(sid)) {
            return;
        }
        boolean isNew = nodeTable.get(sid) == null;
        nodeTable.upsert(sid, entry.getDiscovery(), entry.getEpoch(), nowMs);

        Discovery d = entry.getDiscovery();
        if (d != null) {
            String path = d.getUriSpec();
            if (path == null || path.isBlank()) {
                path = addClusterPrefix("/");
            }
            removeFromCache(path, sid);
            addToCache(path, resolveServiceInstance(sid, d));
        }

        if (isNew && membershipPropagation != null) {
            NodeTable.NodeEntry updated = nodeTable.get(sid);
            if (updated != null) {
                membershipPropagation.pushNewPeer(updated);
            }
        }
    }

    /**
     * 将远程节点的 discovery 解析为服务实例：若条目通过 metadata 携带了
     * 服务真实地址（svcHost/svcPort），则用其构造服务实例，保证各节点
     * 看到的服务实例地址与注册节点本地一致；否则回退使用条目自身地址。
     *
     * @param sid 节点 serverId
     * @param d   远程节点条目
     * @return 服务实例 discovery
     */
    private Discovery resolveServiceInstance(String sid, Discovery d) {
        Map<String, String> meta = d.getMetadata();
        if (meta != null && meta.containsKey("svcHost") && meta.containsKey("svcPort")) {
            try {
                return Discovery.builder()
                        .id(d.getId())
                        .serverId(sid)
                        .protocol(d.getProtocol())
                        .timeout(d.getTimeout())
                        .weight(d.getWeight())
                        .host(meta.get("svcHost"))
                        .port(Integer.parseInt(meta.get("svcPort")))
                        .uriSpec(d.getUriSpec())
                        .metadata(meta)
                        .env(d.getEnv())
                        .build();
            } catch (NumberFormatException ignored) {
                // 端口非法时回退条目自身地址
            }
        }
        return d;
    }

    // ======================== 对外发送 ========================

    /**
     * 向目标节点发送一条消息。
     *
     * @param target 目标发现信息
     * @param msg    消息体
     */
    public void sendMessage(Discovery target, MessageProtocol.PeerMeshMessage msg) {
        sendMessage(target, msg, false);
    }

    /**
     * 向目标节点发送一条消息（可选读取响应）。
     *
     * @param target 目标发现信息
     * @param msg 消息体
     * @param readResponse 是否读取响应（PONG/ACK/NEW_PEER），读取后由本类内部处理
     */
    public void sendMessage(Discovery target, MessageProtocol.PeerMeshMessage msg, boolean readResponse) {
        if (target == null) {
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.getHost(), target.getPort()), 2000);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            MessageProtocol.write(out, msg);
            out.flush();

            if (readResponse) {
                socket.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream()));
                long deadline = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        MessageProtocol.PeerMeshMessage response = MessageProtocol.read(in);
                        if (response == null) {
                            break;
                        }
                        if (response.type() == MessageProtocol.TYPE_PONG) {
                            handlePong(response);
                            break;
                        }
                        if (response.type() == MessageProtocol.TYPE_ACK) {
                            break;
                        }
                        if (response.type() == MessageProtocol.TYPE_NEW_PEER) {
                            handleNewPeerResponse(response);
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("发送消息失败至 {}: {}", target.getServerId(), e.getMessage());
        }
    }

    /**
     * 处理 NEW_PEER 响应（从出站连接读到的 NEW_PEER）。
     *
     * @param msg 消息
     */
    private void handleNewPeerResponse(MessageProtocol.PeerMeshMessage msg) {
        NodeTable.NodeEntry entry = Json.fromJson(msg.payload(), NodeTable.NodeEntry.class);
        if (entry != null && !serverId.equals(entry.getDiscovery().getServerId())) {
            mergeNodeEntry(entry, System.currentTimeMillis());
        }
    }

    // ======================== ServiceDiscovery 扩展 ========================

    /**
     * 注册本地服务。
     * <p>
     * 自动填充 serverId、host、port 等字段，并同步到节点表。
     * </p>
     *
     * @param path      服务路径
     * @param discovery 服务发现信息
     * @return 当前实例
     */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        if (discovery.getServerId() == null) {
            discovery.setServerId(serverId);
        }
        if (discovery.getHost() == null || discovery.getHost().isBlank()) {
            discovery.setHost(localIp);
        }
        if (discovery.getPort() == 0) {
            discovery.setPort(localPort);
        }
        if (discovery.getProtocol() == null) {
            discovery.setProtocol(config.getMode());
        }
        String prefixed = addClusterPrefix(path);
        discovery.setUriSpec(prefixed);
        addToCache(prefixed, discovery);
        incrementServiceVersion();
        if (serverId.equals(discovery.getServerId())) {
            // 本地服务挂在 self 名下：保留 self 的通信地址（host/port/protocol），
            // 仅更新 uriSpec 指向服务路径，并将服务真实地址写入 metadata，
            // 供其他节点解析出与本地一致的服务实例地址。
            NodeTable.NodeEntry existing = nodeTable.get(serverId);
            if (existing != null && existing.getDiscovery() != null) {
                Discovery base = existing.getDiscovery();
                Map<String, String> meta = discovery.getMetadata() != null
                        ? new HashMap<>(discovery.getMetadata()) : new HashMap<>();
                meta.put("svcHost", discovery.getHost());
                meta.put("svcPort", String.valueOf(discovery.getPort()));
                Discovery merged = Discovery.builder()
                        .id(base.getId())
                        .serverId(serverId)
                        .protocol(base.getProtocol() != null ? base.getProtocol() : config.getMode())
                        .timeout(base.getTimeout())
                        .weight(base.getWeight())
                        .host(base.getHost())
                        .port(base.getPort())
                        .uriSpec(prefixed)
                        .metadata(meta)
                        .env(base.getEnv())
                        .build();
                nodeTable.upsert(serverId, merged, existing.getEpoch(), System.currentTimeMillis());
                return this;
            }
        }
        nodeTable.upsert(discovery.getServerId(), discovery, 0, System.currentTimeMillis());
        return this;
    }

    /**
     * 根据 serverId 移除该节点注册的所有服务。
     *
     * @param sid 节点唯一标识
     */
    public void removeServicesByServerId(String sid) {
        for (List<Discovery> list : localCache.values()) {
            list.removeIf(d -> sid.equals(d.getServerId()));
        }
        incrementServiceVersion();
    }

    /**
     * Node获取大小
     * @return 结果数值
     */
    public int nodeSize() {
        return nodeTable.size();
    }

    /**
     * 获取内部节点表（仅用于测试和监控）。
     *
     * @return 节点表
     */
    public NodeTable getNodeTable() {
        return nodeTable;
    }
}
