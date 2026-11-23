package com.chua.common.support.network.ntp;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * NTP/SNTP 客户端（RFC 2030）。
 *
 * <p>向 NTP 服务端发送一次 48 字节查询，依据四个时刻计算本机时钟偏移与网络往返时延：</p>
 * <pre>
 * t0 = 客户端发出时刻；t1 = 服务端收到时刻；
 * t2 = 服务端发出时刻；t3 = 客户端收到时刻
 * offset = ((t1 - t0) + (t2 - t3)) / 2   —— 本机时钟相对服务端的偏移
 * delay  = (t3 - t0) - (t2 - t1)         —— 网络往返时延
 * </pre>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * try (NtpClient client = new NtpClient()) {
 *     NtpClient.NtpResult result = client.query("ntp.aliyun.com");
 *     System.out.println("offset=" + result.getOffsetMillis() + "ms, rtt=" + result.getDelayMillis() + "ms");
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class NtpClient implements AutoCloseable {

    private final NtpConfig config;

    /**
     * 使用默认配置构造客户端。
     */
    public NtpClient() {
        this(NtpConfig.builder().build());
    }

    /**
     * 构造客户端。
     *
     * @param config NTP 配置，不允许为 null
     */
    public NtpClient(NtpConfig config) {
        this.config = config == null ? NtpConfig.builder().build() : config;
    }

    /**
     * 查询指定 NTP 服务器（使用配置端口，默认 123）。
     *
     * @param host 服务器地址，不允许为 null
     * @return 查询结果
     * @throws IOException 网络异常或响应超时
     */
    public NtpResult query(String host) throws IOException {
        int port = config.getPort() > 0 ? config.getPort() : 123;
        return query(host, port);
    }

    /**
     * 查询指定 NTP 服务器与端口。
     *
     * @param host 服务器地址，不允许为 null
     * @param port 服务器端口（如标准 123 或服务端自定义端口）
     * @return 查询结果
     * @throws IOException 网络异常或响应超时
     */
    public NtpResult query(String host, int port) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int) Math.max(1, config.getTimeoutMillis()));
            long t0 = System.currentTimeMillis();
            NtpMessage request = new NtpMessage();
            request.setLeapIndicator(3);
            request.setVersion(config.getVersion());
            request.setMode(NtpMessage.MODE_CLIENT);
            request.setStratum(0);
            request.setPoll(4);
            request.setTransmitTimestamp(NtpMessage.toNtpTimestamp(t0));
            byte[] data = request.encode();
            socket.send(new DatagramPacket(data, data.length, new InetSocketAddress(host, port)));

            byte[] buffer = new byte[NtpMessage.LENGTH];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                throw new IOException("NTP 查询超时: " + host + ":" + port
                        + " (" + config.getTimeoutMillis() + "ms)");
            }
            long t3 = System.currentTimeMillis();
            if (packet.getLength() < NtpMessage.LENGTH) {
                throw new IOException("NTP 响应长度不足: " + packet.getLength());
            }
            NtpMessage response = NtpMessage.decode(buffer);
            if (response.getMode() != NtpMessage.MODE_SERVER) {
                throw new IOException("NTP 响应模式异常: " + response.getMode());
            }
            long t1 = NtpMessage.toEpochMillis(response.getReceiveTimestamp());
            long t2 = NtpMessage.toEpochMillis(response.getTransmitTimestamp());
            long offset = ((t1 - t0) + (t2 - t3)) / 2L;
            long delay = (t3 - t0) - (t2 - t1);
            return new NtpResult(offset, delay, t3 + offset, response);
        }
    }

    /**
     * 查询并仅返回本机时钟偏移（毫秒，正数表示本机快于服务端）。
     *
     * @param host 服务器地址，不允许为 null
     * @return 时钟偏移毫秒
     * @throws IOException 网络异常或响应超时
     */
    public long queryOffset(String host) throws IOException {
        return query(host).getOffsetMillis();
    }

    @Override
    public void close() {
        // 无常驻资源，保持 AutoCloseable 语义便于 try-with-resources
    }

    /**
     * NTP 查询结果。
     */
    @Getter
    @ToString
    @RequiredArgsConstructor
    public static final class NtpResult {

        /**
         * 本机时钟相对服务端的偏移（毫秒，正数表示本机偏快）
         */
        private final long offsetMillis;

        /**
         * 网络往返时延（毫秒）
         */
        private final long delayMillis;

        /**
         * 校准后的当前时间（服务端时间轴，毫秒）
         */
        private final long serverTimeMillis;

        /**
         * 原始响应报文
         */
        private final NtpMessage message;

        /**
         * 服务端层级。
         *
         * @return stratum
         */
        public int getStratum() {
            return message.getStratum();
        }

        /**
         * 服务端参考时钟标识（ASCII 可读形式）。
         *
         * @return 标识文本
         */
        public String getReferenceId() {
            return message.referenceIdText();
        }
    }
}
