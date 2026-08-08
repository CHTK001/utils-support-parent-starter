package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 Netty NIO 的 UDP 同步客户端实现。
 * <p>
 * 通过数据报与服务端双向同步,支持注册、主题订阅与消息收发。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("netty-udp")
public class NettyUdpSyncClient implements SyncClient {

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
     * 客户端 Channel
     */
    private Channel channel;

    /**
     * 服务端地址
     */
    private java.net.InetSocketAddress serverAddress;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 订阅的主题映射（topic -> handler）
     */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建 Netty UDP 同步客户端。
     *
     * @param serverUrl 服务端地址，如 udp://localhost:19401
     */
    public NettyUdpSyncClient(String serverUrl) {
        this(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 Netty UDP 同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public NettyUdpSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            serverAddress = parseAddress(serverUrl);
            group = new NioEventLoopGroup(1);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelHandler());
                        }
                    });
            channel = bootstrap.bind(0).sync().channel();
            connected = true;
            sendData("register:" + clientId);
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            connected = false;
            if (group != null) {
                group.shutdownGracefully();
                group = null;
            }
            throw new RuntimeException("Netty UDP SyncClient 连接失败: " + serverUrl, e);
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
        sendData(topic + ":" + message);
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
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "netty-udp");
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * 发送数据报。
     *
     * @param payload 消息内容
     */
    private void sendData(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = channel.alloc().buffer(bytes.length);
        buf.writeBytes(bytes);
        channel.writeAndFlush(new DatagramPacket(buf, serverAddress));
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
     * 解析 udp://host:port 地址。
     *
     * @param url 地址
     * @return SocketAddress
     */
    private java.net.InetSocketAddress parseAddress(String url) {
        String address = url;
        if (address.startsWith("udp://")) {
            address = address.substring("udp://".length());
        }
        int colon = address.lastIndexOf(':');
        String host = colon > 0 ? address.substring(0, colon) : "127.0.0.1";
        int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 19401;
        return new java.net.InetSocketAddress(host, port);
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
     * UDP 消息处理器。
     *
     * @author CH
     */
    private final class ChannelHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        public void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            String message = msg.content().toString(StandardCharsets.UTF_8).trim();
            handleMessage(message);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (connected) {
                notifyListeners(l -> l.onError("netty-udp", cause));
            }
        }

        /**
         * 处理消息。
         *
         * @param message 消息内容
         */
        private void handleMessage(String message) {
            int colon = message.indexOf(':');
            String topic = colon > 0 ? message.substring(0, colon) : message;
            String payload = colon > 0 ? message.substring(colon + 1) : message;
            SyncMessageHandler handler = subscriptions.get(topic);
            if (handler != null) {
                handler.handle(topic, payload);
            }
            notifyListeners(l -> l.onMessage(topic, payload));
        }
    }
}
