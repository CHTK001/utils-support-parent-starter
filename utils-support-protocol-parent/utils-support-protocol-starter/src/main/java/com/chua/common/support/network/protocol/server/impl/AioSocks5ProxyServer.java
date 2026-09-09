package com.chua.common.support.network.protocol.server.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于 AIO 的 SOCKS5 代理服务器
 * <p>
 * 纯 JDK 实现，使用 AsynchronousSocketChannel 实现异步 IO
 * 相比 NIO 更适合高并发场景，操作系统级别的异步
 * 
 * @author CH
 * @since 2025/12/06
 */
@Slf4j
@Spi({"aio-socks5", "socks5-aio"})
@SpiDescribe("AIO SOCKS5代理服务器")
public class AioSocks5ProxyServer extends AbstractProtocolServer {

    private AsynchronousServerSocketChannel serverChannel;
    private AsynchronousChannelGroup channelGroup;
    private volatile boolean running = false;

    // 连接统计
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong connectionIdCounter = new AtomicLong(0);

    // 高并发配置
    private int effectiveWorkerThreads;
    private int effectiveBufferSize;
    private int effectiveMaxConnections;

    // SOCKS5 常量
    private static final byte SOCKS_VERSION = 0x05;
    private static final byte NO_AUTH = 0x00;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ADDR_TYPE_IPV4 = 0x01;
    private static final byte ADDR_TYPE_DOMAIN = 0x03;
    private static final byte ADDR_TYPE_IPV6 = 0x04;
    private static final byte SUCCESS = 0x00;

    public AioSocks5ProxyServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected void doStart() throws Exception {
        log.info("启动 AIO SOCKS5 代理服务器 - 地址: {}:{}", serverSetting.getHost(), serverSetting.getPort());

        // 配置高并发参数
        configureHighConcurrency();

        // 创建线程池和通道组
        ExecutorService executor = Executors.newFixedThreadPool(effectiveWorkerThreads, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "aio-socks5-worker-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        
        channelGroup = AsynchronousChannelGroup.withThreadPool(executor);
        
        // 创建异步服务器通道
        serverChannel = AsynchronousServerSocketChannel.open(channelGroup);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, effectiveBufferSize);
        int backlog = serverSetting.isAutomaticOptimization() 
                ? Math.max(1024, Runtime.getRuntime().availableProcessors() * 256)
                : (serverSetting.getBacklog() > 0 ? serverSetting.getBacklog() : 1024);
        serverChannel.bind(new InetSocketAddress(serverSetting.getHost(), serverSetting.getPort()), backlog);

        running = true;

        // 开始接受连接
        acceptConnection();

        log.info("✅ AIO SOCKS5 代理服务器启动 - 地址: {}:{} - 自动优化: {} - Workers: {} - Buffer: {}KB - 最大连接: {}",
                serverSetting.getHost(), serverSetting.getPort(), 
                serverSetting.isAutomaticOptimization(),
                effectiveWorkerThreads, effectiveBufferSize / 1024, effectiveMaxConnections);
    }

    /**
     * 配置高并发参数。
     * <p>
     * 如果启用automaticOptimization，将自动计算最优参数。
     *
     * @author CH
     * @since 2025/12/20
     */
    private void configureHighConcurrency() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();

        if (serverSetting.isAutomaticOptimization()) {
            // 自动优化模式
            // Worker线程数：CPU核心数 * 2
            effectiveWorkerThreads = Math.max(4, cpuCores * 2);
            // 缓冲区大小根据CPU调整
            effectiveBufferSize = Math.max(8192, cpuCores * 2048);
            // 根据可用内存计算最大连接数（代理连接约占用16KB）
            long memoryPerConnection = 16 * 1024L;
            long availableForConnections = (long) (maxMemory * 0.5);
            effectiveMaxConnections = (int) Math.min(100000, availableForConnections / memoryPerConnection);

            log.info("AIO SOCKS5自动优化模式 - CPU核心: {}, 最大内存: {}MB, Worker线程: {}, Buffer: {}KB, 最大连接: {}",
                    cpuCores, maxMemory / 1024 / 1024,
                    effectiveWorkerThreads, effectiveBufferSize / 1024, effectiveMaxConnections);
        } else {
            // 使用用户配置或默认值
            effectiveWorkerThreads = serverSetting.getWorkerThreads() > 0
                    ? serverSetting.getWorkerThreads()
                    : Math.max(4, cpuCores * 2);
            effectiveBufferSize = serverSetting.getReceiveBufferSize() > 0
                    ? serverSetting.getReceiveBufferSize()
                    : Math.max(8192, cpuCores * 2048);
            effectiveMaxConnections = serverSetting.getMaxConnections() > 0
                    ? serverSetting.getMaxConnections()
                    : 10000;
        }
    }

    /**
     * 异步接受连接
     */
    private void acceptConnection() {
        if (!running) {
            return;
        }
        
        serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel client, Void attachment) {
                // 继续接受下一个连接
                acceptConnection();
                
                // 处理当前连接
                handleClient(client);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                if (running) {
                    log.error("接受连接失败", exc);
                    acceptConnection();
                }
            }
        });
    }

    /**
     * 处理客户端连接
     */
    private void handleClient(AsynchronousSocketChannel client) {
        String clientId = "aio-socks5-" + connectionIdCounter.incrementAndGet();
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
        
        try {
            if (log.isDebugEnabled()) {
                log.debug("新连接: {} from {}", clientId, client.getRemoteAddress());
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("新连接: {}", clientId);
            }
        }
        
        // 触发 OnOpen 事件
        fireOnOpen(clientId, null);
        
        // 开始 SOCKS5 握手
        Socks5Session session = new Socks5Session(client, clientId);
        session.startHandshake();
    }

    /**
     * SOCKS5 会话处理
     */
    private class Socks5Session {
        private final AsynchronousSocketChannel client;
        private final String clientId;
        private AsynchronousSocketChannel target;
        private final ByteBuffer buffer = ByteBuffer.allocate(512);

        public Socks5Session(AsynchronousSocketChannel client, String clientId) {
            this.client = client;
            this.clientId = clientId;
        }

        /**
         * 开始握手
         */
        public void startHandshake() {
            buffer.clear();
            client.read(buffer, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesRead, Void attachment) {
                    if (bytesRead <= 0) {
                        closeSession();
                        return;
                    }
                    
                    buffer.flip();
                    if (buffer.remaining() < 2) {
                        closeSession();
                        return;
                    }
                    
                    byte version = buffer.get();
                    if (version != SOCKS_VERSION) {
                        if (log.isDebugEnabled()) {
                            log.debug("不支持的 SOCKS 版本: {}", version);
                        }
                        closeSession();
                        return;
                    }
                    
                    byte nmethods = buffer.get();
                    byte[] methods = new byte[nmethods];
                    if (buffer.remaining() >= nmethods) {
                        buffer.get(methods);
                    }
                    
                    // 检查无认证支持
                    boolean noAuthSupported = false;
                    for (byte method : methods) {
                        if (method == NO_AUTH) {
                            noAuthSupported = true;
                            break;
                        }
                    }
                    
                    if (!noAuthSupported) {
                        sendHandshakeResponse((byte) 0xFF);
                        return;
                    }
                    
                    sendHandshakeResponse(NO_AUTH);
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    closeSession();
                }
            });
        }

        /**
         * 发送握手响应
         */
        private void sendHandshakeResponse(byte method) {
            ByteBuffer response = ByteBuffer.allocate(2);
            response.put(SOCKS_VERSION);
            response.put(method);
            response.flip();
            
            client.write(response, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesWritten, Void attachment) {
                    if (method == NO_AUTH) {
                        readConnectRequest();
                    } else {
                        closeSession();
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    closeSession();
                }
            });
        }

        /**
         * 读取连接请求
         */
        private void readConnectRequest() {
            buffer.clear();
            client.read(buffer, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesRead, Void attachment) {
                    if (bytesRead <= 0) {
                        closeSession();
                        return;
                    }
                    
                    buffer.flip();
                    if (buffer.remaining() < 4) {
                        closeSession();
                        return;
                    }
                    
                    byte version = buffer.get();
                    byte cmd = buffer.get();
                    buffer.get(); // RSV
                    byte addrType = buffer.get();
                    
                    if (version != SOCKS_VERSION || cmd != CMD_CONNECT) {
                        sendConnectResponse((byte) 0x07);
                        return;
                    }
                    
                    // 解析目标地址
                    String targetHost;
                    int targetPort;
                    
                    try {
                        switch (addrType) {
                            case ADDR_TYPE_IPV4:
                                byte[] ipv4 = new byte[4];
                                buffer.get(ipv4);
                                targetHost = String.format("%d.%d.%d.%d", 
                                        ipv4[0] & 0xFF, ipv4[1] & 0xFF, ipv4[2] & 0xFF, ipv4[3] & 0xFF);
                                break;
                            case ADDR_TYPE_DOMAIN:
                                int domainLen = buffer.get() & 0xFF;
                                byte[] domain = new byte[domainLen];
                                buffer.get(domain);
                                targetHost = new String(domain);
                                break;
                            case ADDR_TYPE_IPV6:
                                byte[] ipv6 = new byte[16];
                                buffer.get(ipv6);
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < 16; i += 2) {
                                    if (i > 0) sb.append(":");
                                    sb.append(String.format("%02x%02x", ipv6[i] & 0xFF, ipv6[i + 1] & 0xFF));
                                }
                                targetHost = sb.toString();
                                break;
                            default:
                                sendConnectResponse((byte) 0x08);
                                return;
                        }
                        
                        targetPort = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
                        
                        if (log.isDebugEnabled()) {
                            log.debug("连接目标: {}:{}", targetHost, targetPort);
                        }
                        
                        // 异步连接目标
                        connectToTarget(targetHost, targetPort);
                        
                    } catch (Exception e) {
                        sendConnectResponse((byte) 0x01);
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    closeSession();
                }
            });
        }

        /**
         * 连接到目标服务器
         */
        private void connectToTarget(String host, int port) {
            try {
                target = AsynchronousSocketChannel.open(channelGroup);
                target.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                target.setOption(StandardSocketOptions.TCP_NODELAY, true);
                target.setOption(StandardSocketOptions.SO_SNDBUF, effectiveBufferSize);
                target.setOption(StandardSocketOptions.SO_RCVBUF, effectiveBufferSize);
                
                target.connect(new InetSocketAddress(host, port), null, new CompletionHandler<Void, Void>() {
                    @Override
                    public void completed(Void result, Void attachment) {
                        if (log.isDebugEnabled()) {
                            log.debug("连接到目标: {}:{}", host, port);
                        }
                        
                        // 触发连接事件
                        fireOnEvent("connect", clientId, host + ":" + port, null);
                        
                        sendConnectResponse(SUCCESS);
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        if (log.isDebugEnabled()) {
                            log.debug("连接目标失败: {}:{} - {}", host, port, exc.getMessage());
                        }
                        sendConnectResponse((byte) 0x04);
                    }
                });
            } catch (IOException e) {
                sendConnectResponse((byte) 0x01);
            }
        }

        /**
         * 发送连接响应
         */
        private void sendConnectResponse(byte status) {
            ByteBuffer response = ByteBuffer.allocate(10);
            response.put(SOCKS_VERSION);
            response.put(status);
            response.put((byte) 0x00);
            response.put(ADDR_TYPE_IPV4);
            response.put(new byte[4]);
            response.putShort((short) 0);
            response.flip();
            
            client.write(response, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesWritten, Void attachment) {
                    if (status == SUCCESS && target != null) {
                        startRelay();
                    } else {
                        closeSession();
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    closeSession();
                }
            });
        }

        /**
         * 开始双向转发
         */
        private void startRelay() {
            // 客户端 -> 目标
            relayData(client, target, "C->T", ByteBuffer.allocate(effectiveBufferSize));
            // 目标 -> 客户端
            relayData(target, client, "T->C", ByteBuffer.allocate(effectiveBufferSize));
        }

        /**
         * 异步数据转发
         */
        private void relayData(AsynchronousSocketChannel from, AsynchronousSocketChannel to, 
                               String direction, ByteBuffer buf) {
            buf.clear();
            from.read(buf, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesRead, Void attachment) {
                    if (bytesRead <= 0) {
                        closeSession();
                        return;
                    }
                    
                    buf.flip();
                    to.write(buf, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            // 继续转发
                            relayData(from, to, direction, buf);
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            closeSession();
                        }
                    });
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    closeSession();
                }
            });
        }

        /**
         * 关闭会话
         */
        private void closeSession() {
            closeQuietly(client);
            closeQuietly(target);
            activeConnections.decrementAndGet();
            
            // 触发 OnClose 事件
            fireOnClose(clientId, null);
            if (log.isDebugEnabled()) {
                log.debug("连接关闭: {}", clientId);
            }
        }
    }

    /**
     * 静默关闭通道
     */
    private void closeQuietly(AsynchronousSocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void doStop() throws Exception {
        running = false;
        
        if (serverChannel != null) {
            serverChannel.close();
        }
        
        if (channelGroup != null) {
            channelGroup.shutdown();
            if (!channelGroup.awaitTermination(5, TimeUnit.SECONDS)) {
                channelGroup.shutdownNow();
            }
        }
        
        log.info("AIO SOCKS5 代理服务器停止");
    }

    @Override
    public String getProtocolName() {
        return "AIO-SOCKS5";
    }

    @Override
    public String getServerInfo() {
        return String.format("AioSocks5ProxyServer[%s:%d, active=%d, total=%d]",
                serverSetting.getHost(), serverSetting.getPort(),
                activeConnections.get(), totalConnections.get());
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalConnections() {
        return totalConnections.get();
    }
}
