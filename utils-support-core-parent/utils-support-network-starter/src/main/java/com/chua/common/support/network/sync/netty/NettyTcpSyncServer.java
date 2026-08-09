package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 Netty NIO 的 TCP 同步服务端实现。
 * <p>
 * 使用长度前缀帧协议,提供主题发布、客户端注册与消息下行推送。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("netty-tcp")
public class NettyTcpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 客户端注册表（clientId -> channel）
     */
    private final Map<String, Channel> clients = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Boss 线程组
     */
    private NioEventLoopGroup bossGroup;

    /**
     * Worker 线程组
     */
    private NioEventLoopGroup workerGroup;

    /**
     * 服务端 Channel
     */
    private Channel serverChannel;

    /**
     * 创建 Netty TCP 同步服务端。
     *
     * @param setting 服务端配置
     */
    public NettyTcpSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public String getProtocol() {
        return "netty-tcp";
    }

    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new NettyTcpSyncServer(setting);
    }

    @Override
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : "tcp://127.0.0.1:19400";
        return new NettyTcpSyncClient(url);
    }

    @Override
    protected void doStart() {
        bossGroup = new NioEventLoopGroup(Math.max(1, setting.getBossThreads()));
        workerGroup = new NioEventLoopGroup(setting.getWorkerThreads());
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, setting.getBacklog())
                    .option(ChannelOption.SO_REUSEADDR, setting.isSoReuseAddr())
                    .childOption(ChannelOption.TCP_NODELAY, setting.isTcpNoDelay())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new LengthFieldBasedFrameDecoder(setting.getMaxFrameSize(), 0, 4, 0, 4))
                                    .addLast(new ChannelHandler());
                        }
                    });
            ChannelFuture future = bootstrap.bind(setting.getHost(), setting.getPort()).sync();
            serverChannel = future.channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Netty TCP SyncServer 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        for (Channel channel : clients.values()) {
            channel.close();
        }
        clients.clear();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    @Override
    public void publish(String topic, Object message) {
        for (Channel channel : clients.values()) {
            writeFrame(channel, topic + ":" + message);
        }
    }

    @Override
    public void send(String clientId, String topic, Object message) {
        Channel channel = clients.get(clientId);
        if (channel == null) {
            return;
        }
        writeFrame(channel, topic + ":" + message);
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    @Override
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        Channel channel = clients.get(clientId);
        if (channel == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("clientId", clientId);
        meta.put("channel", channel.remoteAddress().toString());
        return Collections.unmodifiableMap(meta);
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
        return ProtocolType.TCP;
    }

    /**
     * 写入长度前缀帧。
     *
     * @param channel 目标通道
     * @param payload 消息内容
     */
    private void writeFrame(Channel channel, String payload) {
        ByteBuf buf = channel.alloc().buffer(4 + payload.getBytes(StandardCharsets.UTF_8).length);
        buf.writeInt(payload.getBytes(StandardCharsets.UTF_8).length);
        buf.writeBytes(payload.getBytes(StandardCharsets.UTF_8));
        channel.writeAndFlush(buf);
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
     * 客户端消息处理器。
     *
     * @author CH
     */
    private final class ChannelHandler extends SimpleChannelInboundHandler<ByteBuf> {

        /**
         * 客户端标识
         */
        private String clientId;

        @Override
        public void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] bytes = new byte[msg.readableBytes()];
            msg.readBytes(bytes);
            String line = new String(bytes, StandardCharsets.UTF_8).trim();
            handleLine(ctx, line);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (clientId != null) {
                clients.remove(clientId);
                notifyListener(l -> l.onClientDisconnected(clientId));
            }
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            notifyListener(l -> l.onError(clientId, cause));
            ctx.close();
        }

        /**
         * 处理一行消息。
         *
         * @param ctx  上下文
         * @param line 消息行
         */
        private void handleLine(ChannelHandlerContext ctx, String line) {
            int colon = line.indexOf(':');
            String topic = colon > 0 ? line.substring(0, colon) : line;
            String payload = colon > 0 ? line.substring(colon + 1) : line;
            if ("register".equals(topic)) {
                clientId = payload;
                clients.put(payload, ctx.channel());
                Map<String, Object> meta = new HashMap<>();
                meta.put("clientId", payload);
                meta.put("channel", ctx.channel().remoteAddress().toString());
                writeFrame(ctx.channel(), "registered:" + payload);
                notifyListener(l -> l.onClientConnected(payload, meta));
            } else {
                notifyListener(l -> l.onMessage(clientId, topic, payload));
            }
        }
    }
}
