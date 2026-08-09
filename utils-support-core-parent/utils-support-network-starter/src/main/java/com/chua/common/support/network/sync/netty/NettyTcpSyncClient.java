package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 Netty NIO 的 TCP 同步客户端实现。
 * <p>
 * 使用长度前缀帧协议与服务端双向同步。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("netty-tcp")
public class NettyTcpSyncClient implements SyncClient {

    /**
     * 客户端标识
     */
    private final String clientId;

    /**
     * 服务端地址
     */
    private final String serverUrl;

    /**
     * 事件循环组
     */
    private NioEventLoopGroup group;

    /**
     * 客户端通道
     */
    private Channel channel;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 是否已注册成功
     */
    private volatile boolean registered;

    /**
     * 订阅的主题映射（topic -> handler）
     */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建 Netty TCP 同步客户端。
     *
     * @param serverUrl 服务端地址，如 tcp://localhost:19400
     */
    public NettyTcpSyncClient(String serverUrl) {
        this(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 Netty TCP 同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public NettyTcpSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            group = new NioEventLoopGroup(1);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4))
                                    .addLast(new ChannelHandler());
                        }
                    });
            ChannelFuture future = bootstrap.connect(parseHost(), parsePort()).sync();
            channel = future.channel();
            connected = true;
            sendLine("register:" + clientId);
            waitRegistered();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            connected = false;
            if (group != null) {
                group.shutdownGracefully();
                group = null;
            }
            throw new RuntimeException("Netty TCP SyncClient 连接失败: " + serverUrl, e);
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
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
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
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "netty-tcp");
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * 等待服务端注册确认, 保证 connect() 返回后已可收发。
     */
    private void waitRegistered() {
        long deadline = System.currentTimeMillis() + 3000L;
        while (!registered && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 发送一行消息。
     *
     * @param line 消息行
     */
    private void sendLine(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = channel.alloc().buffer(4 + bytes.length);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
        channel.writeAndFlush(buf);
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
     * 解析主机名。
     *
     * @return 主机名
     */
    private String parseHost() {
        String address = serverUrl;
        if (address.startsWith("tcp://")) {
            address = address.substring("tcp://".length());
        }
        int colon = address.lastIndexOf(':');
        return colon > 0 ? address.substring(0, colon) : "127.0.0.1";
    }

    /**
     * 解析端口。
     *
     * @return 端口
     */
    private int parsePort() {
        String address = serverUrl;
        if (address.startsWith("tcp://")) {
            address = address.substring("tcp://".length());
        }
        int colon = address.lastIndexOf(':');
        return colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 19400;
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    private void notifyListeners(java.util.function.Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 客户端消息处理器。
     *
     * @author CH
     */
    private final class ChannelHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        public void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] bytes = new byte[msg.readableBytes()];
            msg.readBytes(bytes);
            String line = new String(bytes, StandardCharsets.UTF_8).trim();
            handleLine(line);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (connected) {
                notifyListeners(l -> l.onError("netty-tcp", cause));
            }
            ctx.close();
        }

        /**
         * 处理一行消息。
         *
         * @param line 消息行
         */
        private void handleLine(String line) {
            int colon = line.indexOf(':');
            String topic = colon > 0 ? line.substring(0, colon) : line;
            String payload = colon > 0 ? line.substring(colon + 1) : line;
            if ("registered".equals(topic)) {
                registered = true;
                return;
            }
            SyncMessageHandler handler = subscriptions.get(topic);
            if (handler != null) {
                handler.handle(topic, payload);
            }
            notifyListeners(l -> l.onMessage(topic, payload));
        }
    }
}
