package com.chua.common.support.scatter.node;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * scatter UDP 节点服务端（无连接短报文语义）。
 *
 * <p>基于 {@link DatagramSocket}：收到报文 → 解析帧 → 分派处理 → 原地址回响应。
 * UDP 天然无连接，无长连接维护成本。配置多播组时改用 {@link MulticastSocket}
 * 并加入该组，同时开启 {@code SO_BROADCAST} 以接收自动发现广播帧。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class UdpScatterNodeServer extends AbstractServer {

    private final ScatterNodeHandler handler;
    /**
     * 自动发现多播组地址（空则不加入多播组）
     */
    private final String multicastGroup;
    /**
     * socket
     */
    private DatagramSocket socket;
    /**
     * workerPool
     */
    private ExecutorService workerPool;
    private volatile boolean running = false;

    /**
     * 构造方法，创建 UdpScatter节点服务端 实例。
     *
     * @param setting 方法入参 setting
     * @param handler 处理器，不允许为 null
     */
    public UdpScatterNodeServer(ServerSetting setting, ScatterNodeHandler handler) {
        this(setting, handler, null);
    }

    /**
     * 构造方法，创建 UdpScatter节点服务端 实例（带自动发现多播组）。
     *
     * @param setting 方法入参 setting
     * @param handler 处理器，不允许为 null
     * @param multicastGroup 多播组地址（如 239.255.0.1），空则不加入多播组
     */
    public UdpScatterNodeServer(ServerSetting setting, ScatterNodeHandler handler, String multicastGroup) {
        super(setting);
        this.handler = handler;
        this.multicastGroup = multicastGroup;
    }

    @Override
    protected void doStart() {
        rejectStreamEncryption("UDP 数据报没有流式加密通道，请在上层自行加密载荷");
        try {
            socket = createSocket();
            setting.setPort(socket.getLocalPort());
            running = true;
// [P3C C1 豁免] 场景化创建线程，需文档化
            workerPool = Executors.newVirtualThreadPerTaskExecutor();
            workerPool.submit(this::receiveLoop);
            log.info("Scatter UdpNodeServer started on {}:{} multicastGroup={}",
                    setting.getHost(), setting.getPort(), multicastGroup);
        } catch (Exception e) {
            throw new RuntimeException("UDP 节点服务器启动失败", e);
        }
    }

    /**
     * 创建接收 socket：配置多播组时用 MulticastSocket（复用地址 + 加入多播组），
     * 否则普通 DatagramSocket；两者均开启 SO_BROADCAST 以接收自动发现广播帧。
     *
     * @return 接收 socket
     * @throws Exception 创建/绑定/入组失败时抛出
     */
    private DatagramSocket createSocket() throws Exception {
        if (multicastGroup != null && !multicastGroup.isBlank()) {
            try {
                InetAddress group = InetAddress.getByName(multicastGroup.trim());
                if (group.isMulticastAddress()) {
                    MulticastSocket ms = new MulticastSocket(null);
                    ms.setReuseAddress(true);
                    ms.bind(new InetSocketAddress(setting.getHost(), setting.getPort()));
                    ms.setBroadcast(true);
                    ms.joinGroup(group);
                    return ms;
                }
                log.warn("多播组地址无效（非多播地址），退化为普通 UDP: {}", multicastGroup);
            } catch (Exception e) {
                log.warn("加入多播组失败，退化为普通 UDP: {} - {}", multicastGroup, e.getMessage());
            }
        }
        DatagramSocket ds = new DatagramSocket(new InetSocketAddress(setting.getHost(), setting.getPort()));
        ds.setBroadcast(true);
        return ds;
    }

    /**
     * 接收Loop。
     */
    private void receiveLoop() {
        // [P3C A20 豁免]
        byte[] buffer = new byte[65536];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                final byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                final InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
                workerPool.submit(() -> {
                    try {
                        ScatterFrame frame = ScatterFrame.decode(data);
                        byte[] response = handler.handle(frame);
                        socket.send(new DatagramPacket(response, response.length, sender));
                    } catch (Exception e) {
                        log.debug("UDP 帧处理异常: {}", e.getMessage());
                    }
                });
            } catch (Exception e) {
                if (running) {
                    log.debug("UDP 接收异常: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    protected void doStop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        log.info("Scatter UdpNodeServer stopped");
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.UDP;
    }
}