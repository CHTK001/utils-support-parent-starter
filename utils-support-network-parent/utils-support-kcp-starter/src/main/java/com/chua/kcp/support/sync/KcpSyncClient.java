package com.chua.kcp.support.sync;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.kcp.support.server.KcpServer;
import io.jpower.kcp.netty.ChannelOptionHelper;
import io.jpower.kcp.netty.UkcpChannel;
import io.jpower.kcp.netty.UkcpChannelOption;
import io.jpower.kcp.netty.UkcpClientChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 基于 kcp-netty 的 KCP 同步客户端实现。
 *
 * <p>通过 KCP 可靠 UDP 长连接与服务端双向同步，支持注册、主题订阅与消息收发，
 * 与 {@link KcpSyncServer} 配对使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("kcp")
public class KcpSyncClient implements SyncClient {

    /**
     * 注册指令前缀
     */
    private static final String CMD_REGISTER = "register:";

    /**
     * 注册确认主题
     */
    private static final String CMD_REGISTERED = "registered";

    /**
     * 等待注册确认的超时时间（毫秒）
     */
    private static final long REGISTER_TIMEOUT_MS = 3000L;

    /**
     * KCP 最大传输单元（字节）
     */
    private static final int KCP_MTU = 512;

    /**
     * KCP 更新间隔（毫秒）
     */
    private static final int KCP_INTERVAL = 20;

    /**
     * KCP 快速重传阈值
     */
    private static final int KCP_FAST_RESEND = 2;

    /**
     * 默认服务端端口
     */
    private static final int DEFAULT_PORT = 19380;

    /**
     * 客户端标识
     */
    private final String clientId;

    /**
     * 服务端地址
     */
    private final String serverUrl;

    /**
     * 订阅的主题映射（topic -> handler）
     */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Netty 事件循环组
     */
    private EventLoopGroup group;

    /**
     * 底层 KCP 通道
     */
    private UkcpChannel channel;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 是否已注册成功
     */
    private volatile boolean registered;

    /**
     * 创建 KCP 同步客户端。
     *
     * @param serverUrl 服务端地址，如 kcp://localhost:19380
     */
    public KcpSyncClient(String serverUrl) {
        this(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 KCP 同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public KcpSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(UkcpClientChannel.class)
                .option(UkcpChannelOption.UKCP_MTU, KCP_MTU)
                .handler(new ChannelInitializer<UkcpChannel>() {
                    @Override
                    protected void initChannel(UkcpChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new KcpClientHandler());
                    }
                });
        ChannelOptionHelper.nodelay(bootstrap, true, KCP_INTERVAL, KCP_FAST_RESEND, true);
        try {
            ChannelFuture future = bootstrap.connect(host(), port()).sync();
            channel = (UkcpChannel) future.channel();
            channel.conv(KcpServer.KCP_CONV);
            connected = true;
            sendLine(CMD_REGISTER + clientId);
            waitRegistered();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            connected = false;
            shutdownGroup();
            throw new RuntimeException("KCP SyncClient 连接失败: " + serverUrl, e);
        }
    }

    /**
     * 等待服务端注册确认，保证 connect() 返回后已可收发。
     */
    private void waitRegistered() {
        long deadline = System.currentTimeMillis() + REGISTER_TIMEOUT_MS;
        while (!registered && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        if (channel != null) {
            channel.close();
            channel = null;
        }
        shutdownGroup();
        notifyListeners(SyncFlowListener::onStop);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public void send(String topic, Object message) {
        checkConnected();
        sendLine(topic + ":" + message);
    }

    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
    }

    @Override
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "kcp");
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * 处理一行消息。
     *
     * @param line 消息行
     */
    private void handleLine(String line) {
        String message = line.trim();
        int colon = message.indexOf(':');
        String topic = colon > 0 ? message.substring(0, colon) : message;
        String payload = colon > 0 ? message.substring(colon + 1) : message;
        if (CMD_REGISTERED.equals(topic)) {
            registered = true;
            return;
        }
        SyncMessageHandler handler = subscriptions.get(topic);
        if (handler != null) {
            handler.handle(topic, payload);
        }
        notifyListeners(listener -> listener.onMessage(topic, payload));
    }

    /**
     * 发送一行消息。
     *
     * @param line 消息行
     */
    private void sendLine(String line) {
        if (channel == null) {
            throw new IllegalStateException("客户端未连接");
        }
        channel.writeAndFlush(Unpooled.copiedBuffer(line, StandardCharsets.UTF_8));
    }

    /**
     * 校验连接状态。
     */
    private void checkConnected() {
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
    }

    /**
     * 关闭事件循环组。
     */
    private void shutdownGroup() {
        if (group != null) {
            group.shutdownGracefully().syncUninterruptibly();
            group = null;
        }
    }

    /**
     * 解析服务端主机地址。
     *
     * @return 主机名
     */
    private String host() {
        String address = stripScheme(serverUrl);
        int colon = address.lastIndexOf(':');
        return colon > 0 ? address.substring(0, colon) : "127.0.0.1";
    }

    /**
     * 解析服务端端口。
     *
     * @return 端口号
     */
    private int port() {
        String address = stripScheme(serverUrl);
        int colon = address.lastIndexOf(':');
        return colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : DEFAULT_PORT;
    }

    /**
     * 去除地址的协议前缀。
     *
     * @param url 原始地址
     * @return 去除协议前缀后的地址
     */
    private static String stripScheme(String url) {
        String address = url;
        if (address.startsWith("kcp://")) {
            address = address.substring("kcp://".length());
        }
        return address;
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    private void notifyListeners(Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * KCP 客户端连接处理器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private final class KcpClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            UkcpChannel kcpChannel = (UkcpChannel) ctx.channel();
            kcpChannel.conv(KcpServer.KCP_CONV);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                handleLine(buffer.toString(StandardCharsets.UTF_8));
            } finally {
                ReferenceCountUtil.release(buffer);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (connected) {
                notifyListeners(listener -> listener.onError("kcp", cause));
            }
            ctx.close();
        }
    }
}
