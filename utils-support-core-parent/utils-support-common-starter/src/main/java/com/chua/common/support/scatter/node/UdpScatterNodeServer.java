package com.chua.common.support.scatter.node;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * scatter UDP 节点服务端（无连接短报文语义）。
 *
 * <p>基于 {@link DatagramSocket}：收到报文 → 解析帧 → 分派处理 → 原地址回响应。
 * UDP 天然无连接，无长连接维护成本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class UdpScatterNodeServer extends AbstractServer {

    private final ScatterNodeHandler handler; // 处理器
    private DatagramSocket socket; // 套接字
    private ExecutorService workerPool; // 工人游泳池
    private volatile boolean running = false; // running

    /**
     * udpscatter节点服务端。
     * @param setting setting
     * @param handler 处理器
     */
    public UdpScatterNodeServer(ServerSetting setting, ScatterNodeHandler handler) {
        super(setting);
        this.handler = handler;
    }

    @Override
    protected void doStart() {
        try {
            socket = new DatagramSocket(new InetSocketAddress(setting.getHost(), setting.getPort()));
            setting.setPort(socket.getLocalPort());
            running = true;
            workerPool = Executors.newVirtualThreadPerTaskExecutor();
            workerPool.submit(this::receiveLoop);
            log.info("Scatter UdpNodeServer started on {}:{}", setting.getHost(), setting.getPort());
        } catch (Exception e) {
            throw new RuntimeException("UDP 节点服务器启动失败", e);
        }
    }

    private void receiveLoop() {
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
