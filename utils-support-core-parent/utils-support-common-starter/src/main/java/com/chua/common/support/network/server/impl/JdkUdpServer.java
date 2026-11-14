// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK DatagramSocket 的 UDP 服务器实现。
 *
 * <p>同步阻塞模型，每个数据包使用线程池处理。
 * 支持 UDP 数据包处理器注册、分包处理和链式调用。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 基础用法
 * JdkUdpServer server = new JdkUdpServer(setting)
 *         .registerHandler("*", (data, sender) -> {
 *             return ("echo:" + new String(data)).getBytes();
 *         });
 *
 * // 链式调用
 * JdkUdpServer server = new JdkUdpServer(setting)
 *         .maxRequestSize(8192)
 *         .workerThreads(100)
 *         .handler((data, sender) -> {
 *             return processUdp(data);
 *         });
 * }</pre>
 *
 * @author CH
 * @since 2026/07/26
 */
@Slf4j
@Spi({"jdk-udp"})
public class JdkUdpServer extends AbstractServer {

    /**
     * DatagramSocket
     */
    private DatagramSocket datagramSocket;
    /**
     * Worker池
     */
    private ExecutorService workerPool;
    /**
     * handlers
     */
    private final Map<String, UdpHandler> handlers = new ConcurrentHashMap<>();
    /**
     * Receiver线程
     */
    private Thread receiverThread;

    /**
     * 创建 JdkUdpServer 实例
     * @param setting setting
     */
    public JdkUdpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        rejectStreamEncryption("UDP 数据报没有流式加密通道，请在上层自行加密载荷");
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            int bufferSize = (int) Math.min(setting.getMaxRequestSize() > 0 ? setting.getMaxRequestSize() : 65535,
                    65535);
            datagramSocket = new DatagramSocket(addr);
            // 回写实际端口：port=0 时由内核分配，不回写的话 getPort() 恒为 0。
            setting.setPort(datagramSocket.getLocalPort());
            datagramSocket.setReceiveBufferSize(bufferSize);
            datagramSocket.setSoTimeout(1000);
// [P3C C1 豁免] 场景化创建线程，需文档化
            workerPool = new ThreadPoolExecutor(
                    setting.getWorkerThreads(),
                    Math.max(1, setting.getWorkerThreads()),
                    60L, TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>()
            );
            running = true;

            receiverThread = ThreadUtils.newThread(this::receiveLoop, "udp-receiver");
            receiverThread.setDaemon(true);
            receiverThread.start();

            log.info("JDK UdpServer started on {}:{}", setting.getHost(), setting.getPort());
        } catch (Exception e) {
            throw new RuntimeException("UDP 服务器启动失败", e);
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        running = false;
        if (datagramSocket != null && !datagramSocket.isClosed()) {
            datagramSocket.close();
            log.info("JDK UdpServer stopped");
        }
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(setting.getShutdownQuietPeriod(), TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.UDP;
    }

    /**
     * 接收Loop
     */
    private void receiveLoop() {
        byte[] buffer = new byte[65535];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                datagramSocket.receive(packet);

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());

                InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
                workerPool.submit(() -> handlePacket(data, sender));
            } catch (Exception e) {
                if (running) {
                    log.debug("UDP 接收异常: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 处理Packet
     * @param data 数据，不允许为 null
     * @param sender 方法入参 sender
     */
    private void handlePacket(byte[] data, InetSocketAddress sender) {
        UdpHandler handler = findHandler();
        if (handler != null) {
            try {
                byte[] response = handler.handle(data, sender);
                if (response != null && response.length > 0) {
                    DatagramPacket responsePacket = new DatagramPacket(
                            response, response.length, sender);
                    datagramSocket.send(responsePacket);
                }
            } catch (Exception e) {
                log.error("UDP 处理异常: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 查找Handler
     * @return Udp处理器 对象
     */
    private UdpHandler findHandler() {
        for (Map.Entry<String, UdpHandler> entry : handlers.entrySet()) {
            if ("*".equals(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 注册 UDP 处理器（链式调用）。
     *
     * @param name    处理器名称（"*" 表示匹配所有数据包）
     * @param handler 处理器
     * @return 当前服务器实例
     */
    public JdkUdpServer registerHandler(String name, UdpHandler handler) {
        handlers.put(name, handler);
        return this;
    }

    /**
     * 设置最大请求大小。
     *
     * @param size 最大字节数
     * @return 当前服务器实例
     */
    public JdkUdpServer maxRequestSize(long size) {
        setting.setMaxRequestSize(size);
        return this;
    }

    /**
     * 设置工作线程数。
     *
     * @param threads 线程数
     * @return 当前服务器实例
     */
    public JdkUdpServer workerThreads(int threads) {
        setting.setWorkerThreads(threads);
        return this;
    }

    /**
     * 发送 UDP 数据包。
     * @param data 数据
     * @param host 目标主机
     * @param port 目标端口
     * @return 返回值
     * @throws Exception 异常
    */
    public void send(String host, int port, byte[] data) throws Exception {
        DatagramPacket packet = new DatagramPacket(data, data.length, new InetSocketAddress(host, port));
        datagramSocket.send(packet);
    }

    /**
     * UDP 处理器接口。
     */
    @FunctionalInterface
    public interface UdpHandler {
        /**
         * 处理 UDP 数据包。
         *
         * @param data   接收的数据
         * @param sender 发送方地址
         * @return 响应数据，null 表示不回复
         */
        byte[] handle(byte[] data, InetSocketAddress sender) throws Exception;
    }}