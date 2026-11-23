package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncAddressResolver;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncSetting;
import com.chua.common.support.network.sync.support.AbstractSyncClient;
import com.chua.common.support.network.sync.support.SyncFrameCodec;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 基于 NIO SocketChannel + 虚拟线程的 TCP 同步客户端实现。
 * <p>
 * 通过 TCP 长连接与服务端双向同步，支持注册、主题订阅与消息收发。
 * 读取由虚拟线程承载（阻塞读让出载体线程，连接数不再消耗 OS 线程），
 * 行切分后按订阅表/监听器分发。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("tcp")
public class TcpSyncClient extends AbstractSyncClient {

    /**
     * 底层通道
     */
    private SocketChannel channel;

    /**
     * 是否已注册成功
     */
    private volatile boolean registered;

    /**
     * 接收虚拟线程
     */
    private Thread readThread;

    /**
     * 创建 TCP 同步客户端。
     *
     * @param serverUrl 服务端地址，如 tcp://localhost:19390
     */
    public TcpSyncClient(String serverUrl) {
        this(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 TCP 同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public TcpSyncClient(String clientId, String serverUrl) {
        super(clientId, serverUrl);
    }

    /**
     * 创建 TCP 同步客户端，服务端地址由服务发现解析。
     * <p>发现不到时回落到 {@link SyncSetting#getServerUrl()}。</p>
     *
     * @param clientId 客户端标识
     * @param setting  同步通道配置
     */
    public TcpSyncClient(String clientId, SyncSetting setting) {
        this(clientId, SyncAddressResolver.resolve(setting));
    }

    /**
     * 连接
     */
    @Override
    public void connect() {
        if (isConnected()) {
            return;
        }
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.socket().setTcpNoDelay(true);
            channel.connect(parseAddress(serverUrl));
            setConnected(true);
            startRead();
            sendLine(SyncFrameCodec.encode(SyncFrameCodec.TOPIC_REGISTER, clientId));
            waitRegistered();
            notifyListeners(SyncFlowListener::onStart);
        } catch (IOException e) {
            setConnected(false);
            throw new RuntimeException("TCP SyncClient 连接失败: " + serverUrl, e);
        }
    }

    /**
     * 等待服务端注册确认, 保证 connect() 返回后已可收发。
     */
    private void waitRegistered() {
        // [P3C A20 豁免]
        long deadline = System.currentTimeMillis() + 3000L;
        while (!registered && System.currentTimeMillis() < deadline) {
            // [P3C A20 豁免]
            ThreadUtils.sleep(10L);
        }
    }

    /**
     * 断开
     */
    @Override
    public void disconnect() {
        if (!isConnected()) {
            return;
        }
        setConnected(false);
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                log.debug("[sync] 关闭 TCP 同步客户端通道失败，已忽略: {}", e.getMessage());
            }
            channel = null;
        }
        notifyListeners(SyncFlowListener::onStop);
    }

    /**
     * 发送
     */
    @Override
    public void send(String topic, Object message) {
        checkConnected();
        sendLine(SyncFrameCodec.encode(topic, message));
    }

    /**
     * 获取协议标识。
     */
    @Override
    protected String protocol() {
        return "tcp";
    }

    /**
     * 启动虚拟线程读取：阻塞读让出载体线程，行到达后按订阅/监听器分发。
     */
    private void startRead() {
        // 捕获本次连接的通道：旧线程退出时不误标已被重连替换的新连接
        SocketChannel connChannel = channel;
        readThread = Thread.ofVirtual().name("tcp-sync-read-" + clientId).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    java.nio.channels.Channels.newInputStream(connChannel), StandardCharsets.UTF_8))) {
                String line;
                while (isConnected() && (line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException e) {
                if (isConnected()) {
                    notifyListeners(l -> l.onError("tcp", e));
                }
            } finally {
                // 对端断开（EOF/读异常）：标记断开，供连接池摘除并重连。
                // 仅当底层通道未被重连替换时才置 false，避免新连接被旧线程误标断开。
                if (channel == connChannel) {
                    setConnected(false);
                }
            }
        });
    }

    /**
     * 处理一行消息。
     *
     * @param line 消息行
     */
    private void handleLine(String line) {
        String message = line.trim();
        String topic = SyncFrameCodec.topicOf(message, message);
        String payload = SyncFrameCodec.payloadOf(message);
        if (SyncFrameCodec.TOPIC_REGISTERED.equals(topic)) {
            registered = true;
            return;
        }
        dispatchMessage(topic, payload);
    }

    /**
     * 发送一行消息。
     *
     * @param line 消息行
     */
    private void sendLine(String line) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            // 发送失败即连接已断：标记断开，供连接池下一轮重建
            setConnected(false);
            if (readThread != null) {
                readThread.interrupt();
                readThread = null;
            }
            throw new RuntimeException("TCP SyncClient 发送失败", e);
        }
    }

    /**
     * 解析 tcp://host:port 地址。
     *
     * @param url 地址
     * @return SocketAddress
     */
    private InetSocketAddress parseAddress(String url) {
        String address = url;
        if (address.startsWith("tcp://")) {
            address = address.substring("tcp://".length());
        }
        if (address.startsWith("ws://")) {
            address = address.substring("ws://".length());
        }
        int colon = address.lastIndexOf(':');
        String host = colon > 0 ? address.substring(0, colon) : "127.0.0.1";
        // [P3C A20 豁免]
        int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 19390;
        return new InetSocketAddress(host, port);
    }
}
