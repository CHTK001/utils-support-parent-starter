package com.chua.common.support.network.syslog;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Syslog 客户端，向 Syslog 服务器（rsyslog、syslog-ng、Graylog、ELK 等）发送日志。
 *
 * <p>默认 UDP 传输、RFC 5424 格式；{@code send} 复用内部套接字，
 * UDP 天然无连接，TCP 惰性建连、断线后重发时自动重连。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * try (SyslogClient client = SyslogClient.builder()
 *         .host("127.0.0.1")
 *         .port(5514)
 *         .transport(SyslogTransport.UDP)
 *         .format(SyslogFormat.RFC5424)
 *         .appName("order-service")
 *         .build()) {
 *     client.info("订单创建成功");
 *     client.error("支付回调失败");
 *     // 或携带完整字段
 *     client.send(SyslogMessage.builder()
 *             .facility(SyslogFacility.LOCAL0.getValue())
 *             .severity(SyslogSeverity.WARNING.getValue())
 *             .message("库存低于阈值")
 *             .build());
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class SyslogClient implements AutoCloseable {

    /**
     * 默认连接/发送超时（毫秒）。
     */
    private static final int DEFAULT_TIMEOUT_MILLIS = 5000;

    /**
     * Syslog 服务器地址。
     */
    private final String host;

    /**
     * Syslog 服务器端口。
     */
    private final int port;

    /**
     * 传输协议。
     */
    private final SyslogTransport transport;

    /**
     * 报文格式。
     */
    private final SyslogFormat format;

    /**
     * 默认应用名（消息未指定时补齐）。
     */
    private final String appName;

    /**
     * 默认主机名（消息未指定时补齐）。
     */
    private final String hostname;

    /**
     * TCP 连接超时（毫秒）。
     */
    private final int connectTimeout;

    /**
     * TCP 发送超时（毫秒）。
     */
    private final int sendTimeout;

    /**
     * 复用的 UDP 套接字（惰性创建）。
     */
    private DatagramSocket udpSocket;

    /**
     * 复用的 TCP 套接字（惰性创建）。
     */
    private Socket tcpSocket;

    /**
     * 复用的 TCP 输出流。
     */
    private OutputStream tcpOutput;

    /**
     * TCP 写锁：单连接串行写，避免交叉帧。
     */
    private final Object tcpLock = new Object();

    private SyslogClient(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.transport = builder.transport;
        this.format = builder.format;
        this.appName = builder.appName;
        this.hostname = builder.hostname == null ? defaultHostname() : builder.hostname;
        this.connectTimeout = builder.connectTimeout;
        this.sendTimeout = builder.sendTimeout;
    }

    /**
     * 创建 Builder。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 发送一条消息（按客户端配置补齐默认应用名/主机名后编码发出）。
     *
     * @param message 消息，null 时忽略
     */
    public void send(SyslogMessage message) {
        if (message == null) {
            return;
        }
        SyslogMessage normalized = fillDefaults(message);
        String line = normalized.format(format);
        byte[] payload = line.getBytes(StandardCharsets.UTF_8);
        if (transport == SyslogTransport.UDP) {
            sendUdp(payload);
        } else {
            sendTcp(payload);
        }
    }

    /**
     * 发送 DEBUG 级日志。
     *
     * @param message 正文
     */
    public void debug(String message) {
        send(SyslogMessage.builder().severity(SyslogSeverity.DEBUG.getValue()).message(message).build());
    }

    /**
     * 发送 INFO 级日志。
     *
     * @param message 正文
     */
    public void info(String message) {
        send(SyslogMessage.builder().severity(SyslogSeverity.INFO.getValue()).message(message).build());
    }

    /**
     * 发送 WARNING 级日志。
     *
     * @param message 正文
     */
    public void warning(String message) {
        send(SyslogMessage.builder().severity(SyslogSeverity.WARNING.getValue()).message(message).build());
    }

    /**
     * 发送 ERROR 级日志。
     *
     * @param message 正文
     */
    public void error(String message) {
        send(SyslogMessage.builder().severity(SyslogSeverity.ERR.getValue()).message(message).build());
    }

    /**
     * 为消息补齐客户端默认值（应用名、主机名）。
     *
     * @param message 原消息
     * @return 补齐后的消息
     */
    private SyslogMessage fillDefaults(SyslogMessage message) {
        boolean needAppName = message.getAppName() == null || message.getAppName().isEmpty();
        boolean needHostname = message.getHostname() == null || message.getHostname().isEmpty();
        if (!needAppName && !needHostname) {
            return message;
        }
        SyslogMessage.SyslogMessageBuilder builder = SyslogMessage.builder()
                .facility(message.getFacility())
                .severity(message.getSeverity())
                .timestamp(message.getTimestamp())
                .procId(message.getProcId())
                .msgId(message.getMsgId())
                .message(message.getMessage());
        if (message.getStructuredData() != null) {
            builder.structuredData(message.getStructuredData());
        }
        builder.appName(needAppName ? appName : message.getAppName());
        builder.hostname(needHostname ? hostname : message.getHostname());
        return builder.build();
    }

    /**
     * UDP 发送。
     *
     * @param payload 报文字节
     */
    private void sendUdp(byte[] payload) {
        try {
            DatagramSocket socket = this.udpSocket;
            if (socket == null) {
                socket = new DatagramSocket();
                this.udpSocket = socket;
            }
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName(host), port));
        } catch (IOException e) {
            throw new IllegalStateException("Syslog UDP 发送失败: " + host + ":" + port, e);
        }
    }

    /**
     * TCP 发送（惰性建连、串行写、行分帧）。
     *
     * @param payload 报文字节
     */
    private void sendTcp(byte[] payload) {
        synchronized (tcpLock) {
            try {
                ensureTcpConnected();
                tcpOutput.write(payload);
                tcpOutput.write('\n');
                tcpOutput.flush();
            } catch (IOException e) {
                closeTcpQuietly();
                throw new IllegalStateException("Syslog TCP 发送失败: " + host + ":" + port, e);
            }
        }
    }

    /**
     * 确保 TCP 连接可用，失效时重建。
     *
     * @throws IOException 建连/取流失败
     */
    private void ensureTcpConnected() throws IOException {
        if (tcpSocket != null && !tcpSocket.isClosed() && tcpOutput != null) {
            return;
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeout);
        socket.setSoTimeout(sendTimeout);
        socket.setTcpNoDelay(true);
        this.tcpSocket = socket;
        this.tcpOutput = socket.getOutputStream();
    }

    /**
     * 静默关闭 TCP 资源。
     */
    private void closeTcpQuietly() {
        if (tcpOutput != null) {
            try {
                tcpOutput.close();
            } catch (IOException ignore) {
                // 关闭失败可忽略
            }
        }
        if (tcpSocket != null) {
            try {
                tcpSocket.close();
            } catch (IOException ignore) {
                // 关闭失败可忽略
            }
        }
        tcpSocket = null;
        tcpOutput = null;
    }

    /**
     * 默认主机名。
     *
     * @return 本机主机名，获取失败时为 unknown
     */
    private static String defaultHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 释放客户端资源。
     */
    @Override
    public void close() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        synchronized (tcpLock) {
            closeTcpQuietly();
        }
    }

    /**
     * Syslog 客户端 Builder。
     */
    public static class Builder {

        /**
         * 服务器地址（必填）。
         */
        private String host;

        /**
         * 服务器端口。
         */
        private int port = SyslogConfig.DEFAULT_PORT;

        /**
         * 传输协议。
         */
        private SyslogTransport transport = SyslogTransport.UDP;

        /**
         * 报文格式。
         */
        private SyslogFormat format = SyslogFormat.RFC5424;

        /**
         * 默认应用名。
         */
        private String appName = SyslogMessage.DEFAULT_APP_NAME;

        /**
         * 默认主机名。
         */
        private String hostname;

        /**
         * TCP 连接超时。
         */
        private int connectTimeout = DEFAULT_TIMEOUT_MILLIS;

        /**
         * TCP 发送超时。
         */
        private int sendTimeout = DEFAULT_TIMEOUT_MILLIS;

        /**
         * 设置服务器地址。
         *
         * @param host 地址
         * @return Builder
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置服务器端口。
         *
         * @param port 端口
         * @return Builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置传输协议。
         *
         * @param transport UDP / TCP
         * @return Builder
         */
        public Builder transport(SyslogTransport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * 设置报文格式。
         *
         * @param format RFC3164 / RFC5424
         * @return Builder
         */
        public Builder format(SyslogFormat format) {
            this.format = format;
            return this;
        }

        /**
         * 设置默认应用名。
         *
         * @param appName 应用名
         * @return Builder
         */
        public Builder appName(String appName) {
            this.appName = appName;
            return this;
        }

        /**
         * 设置默认主机名。
         *
         * @param hostname 主机名
         * @return Builder
         */
        public Builder hostname(String hostname) {
            this.hostname = hostname;
            return this;
        }

        /**
         * 设置 TCP 连接超时。
         *
         * @param connectTimeout 毫秒
         * @return Builder
         */
        public Builder connectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置 TCP 发送超时。
         *
         * @param sendTimeout 毫秒
         * @return Builder
         */
        public Builder sendTimeout(int sendTimeout) {
            this.sendTimeout = sendTimeout;
            return this;
        }

        /**
         * 构建客户端。
         *
         * @return Syslog 客户端
         */
        public SyslogClient build() {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Syslog 服务器地址不能为空");
            }
            return new SyslogClient(this);
        }
    }
}
