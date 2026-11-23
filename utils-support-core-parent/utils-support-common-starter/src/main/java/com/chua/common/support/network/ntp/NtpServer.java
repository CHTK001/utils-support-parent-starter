package com.chua.common.support.network.ntp;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * NTP/SNTP 服务端（RFC 2030）。
 *
 * <p>基于 UDP 的轻量时间服务：收到 48 字节 NTP 请求后，按 RFC 2030
 * 回填服务端各时间戳（reference/originate/receive/transmit）。
 * 客户端可用 {@link NtpClient} 据此计算本机时钟偏移。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * NtpServer server = new NtpServer(NtpConfig.builder().port(5123).build());
 * server.start();
 * try {
 *     NtpClient.NtpResult result = new NtpClient().query("127.0.0.1", 5123);
 *     System.out.println("offset=" + result.getOffsetMillis() + "ms");
 * } finally {
 *     server.close();
 * }
 * }</pre>
 *
 * <p>标准 NTP 端口为 UDP 123（绑定需相应权限）；测试可配置高端口。
 * 服务端时间为单实例本机时钟，不主动向上游同步。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class NtpServer implements AutoCloseable {

    /**
     * 时钟精度：2^-20 秒（约 1 微秒），表示本机时钟读取精度量级
     */
    private static final int PRECISION = -20;

    private final NtpConfig config;
    private DatagramSocket socket;
    private Thread worker;
    private volatile boolean running;

    /**
     * 实际绑定的端口（启动后有效；未启动返回 -1）
     */
    @Getter
    private volatile int boundPort = -1;

    /**
     * 使用默认配置构造服务端。
     */
    public NtpServer() {
        this(NtpConfig.builder().build());
    }

    /**
     * 构造服务端。
     *
     * @param config NTP 配置，不允许为 null
     */
    public NtpServer(NtpConfig config) {
        this.config = config == null ? NtpConfig.builder().build() : config;
    }

    /**
     * 启动服务端（绑定 UDP 端口并开始应答）。
     *
     * @throws Exception 绑定失败（端口被占用等）
     */
    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        socket = new DatagramSocket(new InetSocketAddress(config.getBindHost(), config.getPort()));
        running = true;
        boundPort = socket.getLocalPort();
        worker = new Thread(this::loop, "ntp-server-" + boundPort);
        worker.setDaemon(true);
        worker.start();
        log.info("NTP 服务端已启动: {}:{} (stratum={})", config.getBindHost(), boundPort, config.getStratum());
    }

    /**
     * 接收循环：逐包应答，套接字关闭后退出。
     */
    private void loop() {
        byte[] buffer = new byte[NtpMessage.LENGTH];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (SocketException e) {
                // stop() 关闭套接字触发，正常退出
                break;
            } catch (Exception e) {
                if (running) {
                    log.debug("NTP 接收异常: {}", e.getMessage());
                }
                continue;
            }
            long receiveTimeMillis = System.currentTimeMillis();
            respond(packet, receiveTimeMillis);
        }
    }

    /**
     * 处理单个 NTP 请求并应答。
     *
     * @param packet 收到的数据报
     * @param receiveTimeMillis 服务端收到请求的时刻
     */
    private void respond(DatagramPacket packet, long receiveTimeMillis) {
        try {
            int length = packet.getLength();
            if (length < NtpMessage.LENGTH) {
                log.debug("NTP 请求长度不足，忽略: {} 字节", length);
                return;
            }
            NtpMessage request = NtpMessage.decode(packet.getData());
            NtpMessage response = new NtpMessage();
            response.setLeapIndicator(0);
            response.setVersion(request.getVersion() > 0 ? request.getVersion() : config.getVersion());
            response.setMode(NtpMessage.MODE_SERVER);
            response.setStratum(Math.max(1, config.getStratum()));
            response.setPoll(request.getPoll());
            response.setPrecision(PRECISION);
            response.setRootDelay(0L);
            response.setRootDispersion(0L);
            response.setReferenceId(encodeReferenceId(config.getReferenceId()));
            response.setReferenceTimestamp(NtpMessage.toNtpTimestamp(receiveTimeMillis));
            // originate = 请求的 transmit（客户端发出时刻），供客户端做往返匹配
            response.setOriginateTimestamp(request.getTransmitTimestamp());
            response.setReceiveTimestamp(NtpMessage.toNtpTimestamp(receiveTimeMillis));
            response.setTransmitTimestamp(NtpMessage.toNtpTimestamp(System.currentTimeMillis()));
            byte[] data = response.encode();
            socket.send(new DatagramPacket(data, data.length, packet.getSocketAddress()));
        } catch (Exception e) {
            log.debug("NTP 应答失败: {} - {}", packet.getSocketAddress(), e.getMessage());
        }
    }

    /**
     * 参考时钟标识编码为 32 位（ASCII 左对齐，不足补零）。
     *
     * @param referenceId 标识文本（建议 ≤4 个 ASCII 字符）
     * @return 32 位参考标识
     */
    private static long encodeReferenceId(String referenceId) {
        long value = 0L;
        String text = referenceId == null ? "LOCL" : referenceId;
        int n = Math.min(4, text.length());
        for (int i = 0; i < n; i++) {
            value = (value << 8) | (text.charAt(i) & 0xFFL);
        }
        return value << (8 * (4 - n));
    }

    /**
     * 停止服务端并释放端口。
     */
    public synchronized void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        Thread thread = worker;
        worker = null;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        log.info("NTP 服务端已停止: 端口 {}", boundPort);
    }

    @Override
    public void close() {
        stop();
    }
}
