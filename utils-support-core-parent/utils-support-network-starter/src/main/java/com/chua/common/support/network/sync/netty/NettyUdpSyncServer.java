package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 Netty NIO 的 UDP 同步服务端实现。
 * <p>
 * 提供主题发布与客户端注册能力,消息以数据报方式推送。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("netty-udp")
public class NettyUdpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 客户端注册表（clientId -> 地址与元数据）
     */
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 事件循环组
     */
    private NioEventLoopGroup group;

    /**
     * 服务端 Channel
     */
    private Channel channel;

    /**
     * 创建 Netty UDP 同步服务端 (默认配置)。
     */
    public NettyUdpSyncServer() {
        this(ServerSetting.defaults());
    }

    /**
     * 创建 Netty UDP 同步服务端。
     *
     * @param setting 服务端配置
     */
    public NettyUdpSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public String getProtocol() {
        return "netty-udp";
    }

    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new NettyUdpSyncServer(setting);
    }

    @Override
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : "udp://127.0.0.1:19401";
        return new NettyUdpSyncClient(url);
    }

    @Override
    protected void doStart() {
        group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, setting.isSoReuseAddr())
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelHandler());
                        }
                    });
            ChannelFuture future = bootstrap.bind(setting.getHost(), setting.getPort()).sync();
            channel = future.channel();
        } catch (Exception e) {
            throw new RuntimeException("Netty UDP SyncServer 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        clients.clear();
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
    }

    @Override
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message;
        for (ClientInfo client : clients.values()) {
            sendTo(client, payload);
        }
    }

    @Override
    public void send(String clientId, String topic, Object message) {
        ClientInfo client = clients.get(clientId);
        if (client == null) {
            return;
        }
        sendTo(client, topic + ":" + message);
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    @Override
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        ClientInfo client = clients.get(clientId);
        return client != null ? Collections.unmodifiableMap(client.metadata) : Collections.emptyMap();
    }

    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.UDP;
    }

    /**
     * 发送数据报到客户端。
     *
     * @param client  客户端信息
     * @param payload 消息内容
     */
    private void sendTo(ClientInfo client, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        io.netty.buffer.ByteBuf buf = channel.alloc().buffer(bytes.length);
        buf.writeBytes(bytes);
        channel.writeAndFlush(new DatagramPacket(buf, client.address));
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 客户端信息。
     *
     * @author CH
     */
    private static final class ClientInfo {

        /**
         * 客户端地址
         */
        private final java.net.InetSocketAddress address;

        /**
         * 客户端元数据
         */
        private final Map<String, Object> metadata = new HashMap<>();

        /**
         * 创建客户端信息。
         *
         * @param address 客户端地址
         */
        private ClientInfo(java.net.InetSocketAddress address) {
            this.address = address;
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
            java.net.InetSocketAddress sender = msg.sender();
            handleMessage(sender, message);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            notifyListener(l -> l.onError(null, cause));
        }

        /**
         * 处理消息。
         *
         * @param sender  发送方地址
         * @param message 消息内容
         */
        private void handleMessage(java.net.InetSocketAddress sender, String message) {
            int colon = message.indexOf(':');
            String topic = colon > 0 ? message.substring(0, colon) : message;
            String payload = colon > 0 ? message.substring(colon + 1) : message;
            if ("register".equals(topic)) {
                ClientInfo client = new ClientInfo(sender);
                client.metadata.put("clientId", payload);
                client.metadata.put("address", sender.toString());
                clients.put(payload, client);
                notifyListener(l -> l.onClientConnected(payload, client.metadata));
            } else {
                notifyListener(l -> l.onMessage(null, topic, payload));
            }
        }
    }
}
