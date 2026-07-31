package com.chua.common.support.network.server.dht;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * DHT 传输层，基于 Netty NIO Datagram Channel 实现。
 * <p>
 * 提供 UDP 消息的收发、pending 匹配、超时控制和 JSON/KRPC 自动分发。
 * 相比原 {@link DhtUdpServer}，本类使用 Netty 非阻塞 IO，
 * 在高并发下吞吐更稳定，线程模型更清晰。
 * </p>
 *
 * @author CH
 */
@Slf4j
public class DhtNettyServer {

    /**
     * DHT 专用配置
     */
    private final DhtConfig config;

    /**
     * Netty 事件循环组，负责 IO 事件调度
     */
    private NioEventLoopGroup group;

    /**
     * UDP 监听 Channel
     */
    private Channel channel;

    /**
     * JSON 消息处理器，非 pending 匹配的消息将转发至此处理器
     */
    private volatile BiConsumer<DhtMessage, InetSocketAddress> messageHandler;

    /**
     * 原始字节消息处理器，用于 KRPC/Bencode 格式消息分发
     */
    private volatile BiConsumer<byte[], InetSocketAddress> rawMessageHandler;

    /**
     * 自定义响应编码器，如果设置则优先于 JSON 编码
     */
    private volatile BiConsumer<DhtMessage, InetSocketAddress> responseEncoder;

    /**
     * JSON 格式挂起请求映射，键为 "targetId@host:port"
     */
    private final Map<String, CompletableFuture<DhtMessage>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * KRPC / 原始字节格式挂起请求映射，键为 "{txId}@{host}:{port}"
     */
    private final Map<String, CompletableFuture<byte[]>> pendingRaw = new ConcurrentHashMap<>();

    /**
     * 超时任务调度器（共享，避免每次创建线程池）。
     */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dht-netty-timeout");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造 DHT Netty 服务器。
     *
     * @param config DHT 配置
     */
    public DhtNettyServer(DhtConfig config) {
        this.config = config;
    }

    /**
     * 启动 UDP 监听并开始接收消息。
     *
     * @throws Exception 启动失败时抛出
     */
    public void start() throws Exception {
        group = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.SO_RCVBUF, 65536)
                .option(ChannelOption.SO_SNDBUF, 65536)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast(new DhtPacketHandler());
                    }
                });

        String host = config.getHost() != null ? config.getHost() : "0.0.0.0";
        int port = config.getPort() > 0 ? config.getPort() : 6881;
        ChannelFuture f = b.bind(new InetSocketAddress(host, port)).sync();
        channel = f.channel();
        log.info("DHT Netty server started on {}:{}", host, port);
    }

    /**
     * 关闭服务器并释放资源。
     */
    public void close() {
        if (group != null) {
            group.shutdownGracefully();
        }
        if (channel != null) {
            channel.close();
        }
    }

    /**
     * 设置 JSON 消息处理器。
     *
     * @param handler 消息处理回调
     */
    public void setMessageHandler(BiConsumer<DhtMessage, InetSocketAddress> handler) {
        this.messageHandler = handler;
    }

    /**
     * 设置原始字节消息处理器，用于 KRPC/Bencode 格式。
     *
     * @param handler 原始字节消息处理回调
     */
    public void setRawMessageHandler(BiConsumer<byte[], InetSocketAddress> handler) {
        this.rawMessageHandler = handler;
    }

    /**
     * 设置自定义响应编码器。
     * <p>
     * 如果设置，{@link #sendNoResponse(DhtMessage, InetSocketAddress)} 将优先使用此编码器而不是 JSON。
     * 用于 KRPC 协议响应编码。
     * </p>
     *
     * @param encoder 响应编码器，接收 DhtMessage 和目标地址
     */
    public void setResponseEncoder(BiConsumer<DhtMessage, InetSocketAddress> encoder) {
        this.responseEncoder = encoder;
    }

    /**
     * 发送 JSON 格式 DHT 消息并等待响应。
     *
     * @param message   要发送的 DHT 消息
     * @param target    目标地址
     * @param timeoutMs 超时时间（毫秒）
     * @return CompletableFuture，完成时返回响应消息
     */
    public CompletableFuture<DhtMessage> send(DhtMessage message, InetSocketAddress target, long timeoutMs) {
        CompletableFuture<DhtMessage> future = new CompletableFuture<>();
        String rpcKey = message.getTargetId() + "@" + target.getAddress().getHostAddress() + ":" + target.getPort();
        pendingRequests.put(rpcKey, future);
        scheduleTimeout(future, rpcKey, timeoutMs);
        String json = message.toJson();
        ByteBuf buf = Unpooled.wrappedBuffer(json.getBytes(StandardCharsets.UTF_8));
        channel.writeAndFlush(new DatagramPacket(buf, target));
        return future;
    }

    /**
     * 发送 DHT 消息（不等待响应）。
     * <p>
     * 如果设置了 {@link #responseEncoder}，则优先使用编码器而不是 JSON。
     * </p>
     *
     * @param message 要发送的 DHT 消息
     * @param target  目标地址
     */
    public void sendNoResponse(DhtMessage message, InetSocketAddress target) {
        if (responseEncoder != null) {
            responseEncoder.accept(message, target);
            return;
        }
        String json = message.toJson();
        ByteBuf buf = Unpooled.wrappedBuffer(json.getBytes(StandardCharsets.UTF_8));
        channel.writeAndFlush(new DatagramPacket(buf, target));
    }

    /**
     * 发送原始字节数据并等待响应。
     * <p>
     * 用于 KRPC 等非 JSON 格式的消息，pendingKey 通常为 "{txId}@{host}:{port}"。
     * </p>
     *
     * @param data      原始字节数据
     * @param target    目标地址
     * @param pendingKey 挂起请求的键（用于响应匹配）
     * @param timeoutMs 超时时间（毫秒）
     * @return CompletableFuture，完成时返回响应字节数据
     */
    public CompletableFuture<byte[]> sendRaw(byte[] data, InetSocketAddress target, String pendingKey, long timeoutMs) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingRaw.put(pendingKey, future);
        scheduleRawTimeout(future, pendingKey, timeoutMs);
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        channel.writeAndFlush(new DatagramPacket(buf, target));
        return future;
    }

    /**
     * 发送原始字节数据（不等待响应）。
     *
     * @param data   原始字节数据
     * @param target 目标地址
     */
    public void sendNoResponseRaw(byte[] data, InetSocketAddress target) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        channel.writeAndFlush(new DatagramPacket(buf, target));
    }

    /**
     * 移除并返回挂起的原始请求。
     *
     * @param pendingKey 挂起请求的键
     * @return CompletableFuture，未找到返回 null
     */
    public CompletableFuture<byte[]> removePendingRaw(String pendingKey) {
        return pendingRaw.remove(pendingKey);
    }

    /**
     * 为 JSON 挂起的请求注册超时任务。
     *
     * @param future  CompletableFuture
     * @param reqId   请求标识
     * @param timeoutMs 超时时间（毫秒）
     */
    private void scheduleTimeout(CompletableFuture<DhtMessage> future, String reqId, long timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }
        TIMEOUT_SCHEDULER.schedule(() -> {
            pendingRequests.remove(reqId);
            future.completeExceptionally(new TimeoutException("DHT RPC timeout after " + timeoutMs + "ms"));
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 为原始字节挂起的请求注册超时任务。
     *
     * @param future  CompletableFuture
     * @param reqId   请求标识
     * @param timeoutMs 超时时间（毫秒）
     */
    private void scheduleRawTimeout(CompletableFuture<byte[]> future, String reqId, long timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }
        TIMEOUT_SCHEDULER.schedule(() -> {
            pendingRaw.remove(reqId);
            future.completeExceptionally(new TimeoutException("DHT raw RPC timeout after " + timeoutMs + "ms"));
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Netty 数据包处理器，负责接收并分发消息。
     */
    private class DhtPacketHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            SocketAddress sender = packet.sender();
            ByteBuf buf = packet.content();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();

            if (data.length > 0 && data[0] == '{') {
                handleJsonMessage(data, (InetSocketAddress) sender);
            } else {
                handleKrpcMessage(data, (InetSocketAddress) sender);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("DHT Netty server error", cause);
            ctx.close();
        }
    }

    /**
     * 处理 JSON 格式消息。
     *
     * @param data   消息字节数据
     * @param sender 发送者地址
     */
    private void handleJsonMessage(byte[] data, InetSocketAddress sender) {
        String content = new String(data, StandardCharsets.UTF_8);
        try {
            DhtMessage message = DhtMessage.fromJson(content);
            InetSocketAddress recipient = new InetSocketAddress(
                    channel.localAddress().toString().split(":")[0].replace("/", ""),
                    ((InetSocketAddress) channel.localAddress()).getPort()
            );
            message.setRecipientHost(recipient.getHostString());
            message.setRecipientPort(recipient.getPort());
            CompletableFuture<DhtMessage> pending = pendingRequests.remove(
                    message.getTargetId() + "@" + sender.getAddress().getHostAddress() + ":" + sender.getPort());
            if (pending == null && message.getTargetId() != null) {
                String suffix = ":" + sender.getPort();
                for (String key : pendingRequests.keySet()) {
                    if (key.startsWith(message.getTargetId() + "@") && key.endsWith(suffix)) {
                        pending = pendingRequests.remove(key);
                        break;
                    }
                }
            }
            if (pending != null) {
                pending.complete(message);
            } else if (messageHandler != null) {
                messageHandler.accept(message, sender);
            }
        } catch (Exception e) {
            log.debug("Failed to parse JSON DHT message from {}: {}", sender, e.getMessage());
        }
    }

    /**
     * 处理 KRPC (Bencode) 格式消息。
     *
     * @param data   消息字节数据
     * @param sender 发送者地址
     */
    private void handleKrpcMessage(byte[] data, InetSocketAddress sender) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = (Map<String, Object>) com.chua.common.support.network.server.dht.krpc.BencodeCodec.decode(data);

            Object tObj = decoded.get("t");
            String txId = null;
            if (tObj instanceof String) {
                txId = (String) tObj;
            } else if (tObj instanceof byte[]) {
                txId = new String((byte[]) tObj, StandardCharsets.ISO_8859_1);
            }

            if (txId != null) {
                String pendingKey = txId + "@" + sender.getAddress().getHostAddress() + ":" + sender.getPort();
                CompletableFuture<byte[]> pending = pendingRaw.remove(pendingKey);
                if (pending != null) {
                    log.debug("KRPC response matched pendingKey={} from {}", pendingKey, sender);
                    pending.complete(data);
                    return;
                }
                log.debug("KRPC response unmatched txId={} from {}", txId, sender);
            }

            if (rawMessageHandler != null) {
                rawMessageHandler.accept(data, sender);
            }
        } catch (Exception ignored) {
        }
    }
}
