package com.chua.common.support.network.protocol.server.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.filter.ServletFilter;
import com.chua.common.support.network.protocol.request.BadServletResponse;
import com.chua.common.support.network.protocol.request.ProxyServletRequest;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于 NIO 的 SOCKS5 代理服务器
 * <p>
 * 纯 JDK 实现，无第三方依赖，使用 Selector 实现非阻塞 IO
 * 
 * @author CH
 * @since 2025/12/06
 */
@Slf4j
@Spi({"socks5", "socks5-proxy"})
@SpiDescribe("SOCKS5代理服务器")
public class Socks5ProxyServer extends AbstractProtocolServer {

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private volatile boolean running = false;
    private ExecutorService workerPool;
    private Thread acceptThread;

    /**
     * 运行模式: netty, nio, etc
     */
    private String runMode = "unknown";

    /**
     * 委托的代理服务器
     */
    private ProtocolServer delegateServer;


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

    /**
     * 是否有过滤器
     */
    private boolean hasFilters = false;

    public Socks5ProxyServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected void doStart() throws Exception {
        // 检查是否有过滤器
        List<ServletFilter> filters = getFilters();
        hasFilters = filters != null && !filters.isEmpty();

        // 1. 优先使用纯 Java ServiceProvider 实现
        if (tryLoadServiceProviderProxy()) {
            log.info("SOCKS5代理服务器启动 [{}] - {}:{}", runMode, serverSetting.getHost(), serverSetting.getPort());
            return;
        }

        // 2. 回退到当前模块内置的 NIO 实现
        runMode = "nio";
        startNioProxy();
    }

    /**
     * 尝试通过 ServiceProvider 加载代理实现
     */
    private boolean tryLoadServiceProviderProxy() {
        try {
            delegateServer = ProtocolServer.create("netty-socks5", serverSetting);

            if (delegateServer != null && delegateServer.getClass() != this.getClass()) {
                delegateServer.start();
                if (delegateServer.isRunning()) {
                    runMode = delegateServer.getClass().getSimpleName();
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("加载 SOCKS5 代理失败: {}", e.getMessage());
        }
        return false;
    }

    // 以下保留原有的 NIO 实现代码作为参考，但不再使用
    @Deprecated
    @SuppressWarnings("unused")
    private void startNioProxy() throws Exception {
        log.info("启动 NIO SOCKS5 代理服务器 - 地址: {}:{}", serverSetting.getHost(), serverSetting.getPort());

        // 配置高并发参数
        configureHighConcurrency();

        // 创建工作线程池
        workerPool = new ThreadPoolExecutor(
                effectiveWorkerThreads, effectiveWorkerThreads * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10000),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "nio-socks5-worker-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 打开 Selector 和 ServerSocketChannel
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        int backlog = serverSetting.isAutomaticOptimization() 
                ? Math.max(1024, Runtime.getRuntime().availableProcessors() * 256)
                : (serverSetting.getBacklog() > 0 ? serverSetting.getBacklog() : 1024);
        serverChannel.socket().bind(new InetSocketAddress(serverSetting.getHost(), serverSetting.getPort()), backlog);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;

        // 启动接受连接线程
        acceptThread = new Thread(this::acceptLoop, "nio-socks5-acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();

        log.info("✅ NIO SOCKS5 代理服务器启动 [降级模式] - 地址: {}:{} - Workers: {} - Buffer: {}KB - 最大连接: {}",
                serverSetting.getHost(), serverSetting.getPort(),
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

            log.info("NIO SOCKS5自动优化模式 - CPU核心: {}, 最大内存: {}MB, Worker线程: {}, Buffer: {}KB, 最大连接: {}",
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
     * 接受连接循环
     */
    private void acceptLoop() {
        while (running) {
            try {
                if (selector.select(1000) == 0) {
                    continue;
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Selector 错误", e);
                }
            }
        }
    }

    /**
     * 处理新连接
     */
    private void handleAccept(SelectionKey key) {
        try {
            ServerSocketChannel server = (ServerSocketChannel) key.channel();
            SocketChannel client = server.accept();
            if (client != null) {
                client.configureBlocking(true); // 使用阻塞模式简化 SOCKS5 握手
                
                String clientId = "nio-socks5-" + connectionIdCounter.incrementAndGet();
                activeConnections.incrementAndGet();
                totalConnections.incrementAndGet();
                
                if (log.isDebugEnabled()) {
                    log.debug("新连接: {} from {}", clientId, client.getRemoteAddress());
                }
                
                // 触发 OnOpen 事件
                fireOnOpen(clientId, null);
                
                // 提交到工作线程池处理
                workerPool.submit(() -> handleClient(client, clientId));
            }
        } catch (IOException e) {
            log.error("接受连接失败", e);
        }
    }

    /**
     * 处理客户端连接
     */
    private void handleClient(SocketChannel client, String clientId) {
        SocketChannel target = null;
        try {
            // SOCKS5 握手
            if (!doHandshake(client)) {
                return;
            }

            // SOCKS5 连接请求
            target = doConnect(client);
            if (target == null) {
                return;
            }

            // 触发连接事件
            fireOnEvent("connect", clientId, target.getRemoteAddress().toString(), null);

            // 开始双向转发
            relay(client, target, clientId);

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("处理客户端错误: {}", e.getMessage());
            }
        } finally {
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
     * SOCKS5 握手
     */
    private boolean doHandshake(SocketChannel client) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(256);
        
        // 读取客户端认证方法
        int read = client.read(buffer);
        if (read < 2) {
            return false;
        }
        
        buffer.flip();
        byte version = buffer.get();
        if (version != SOCKS_VERSION) {
            if (log.isDebugEnabled()) {
                log.debug("不支持的 SOCKS 版本: {}", version);
            }
            return false;
        }
        
        byte nmethods = buffer.get();
        byte[] methods = new byte[nmethods];
        buffer.get(methods);
        
        // 检查是否支持无认证
        boolean noAuthSupported = false;
        for (byte method : methods) {
            if (method == NO_AUTH) {
                noAuthSupported = true;
                break;
            }
        }
        
        if (!noAuthSupported) {
            // 发送不支持的认证方法响应
            buffer.clear();
            buffer.put(SOCKS_VERSION);
            buffer.put((byte) 0xFF);
            buffer.flip();
            client.write(buffer);
            return false;
        }
        
        // 发送无认证响应
        buffer.clear();
        buffer.put(SOCKS_VERSION);
        buffer.put(NO_AUTH);
        buffer.flip();
        client.write(buffer);
        
        return true;
    }

    /**
     * SOCKS5 连接请求处理
     */
    private SocketChannel doConnect(SocketChannel client) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        
        // 读取连接请求
        int read = client.read(buffer);
        if (read < 4) {
            return null;
        }
        
        buffer.flip();
        byte version = buffer.get();
        byte cmd = buffer.get();
        buffer.get(); // RSV
        byte addrType = buffer.get();
        
        if (version != SOCKS_VERSION || cmd != CMD_CONNECT) {
            sendConnectResponse(client, (byte) 0x07); // 不支持的命令
            return null;
        }
        
        // 解析目标地址
        String targetHost;
        int targetPort;
        
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
                sendConnectResponse(client, (byte) 0x08); // 不支持的地址类型
                return null;
        }
        
        targetPort = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
        
        if (log.isDebugEnabled()) {
            log.debug("连接目标: {}:{}", targetHost, targetPort);
        }
        
        // 连接目标服务器
        try {
            SocketChannel target = SocketChannel.open();
            target.configureBlocking(true);
            target.socket().connect(new InetSocketAddress(targetHost, targetPort), 10000);
            
            // 发送成功响应
            sendConnectResponse(client, SUCCESS);
            
            return target;
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("连接目标失败: {}:{} - {}", targetHost, targetPort, e.getMessage());
            }
            sendConnectResponse(client, (byte) 0x04); // 主机不可达
            return null;
        }
    }

    /**
     * 发送连接响应
     */
    private void sendConnectResponse(SocketChannel client, byte status) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(10);
        buffer.put(SOCKS_VERSION);
        buffer.put(status);
        buffer.put((byte) 0x00); // RSV
        buffer.put(ADDR_TYPE_IPV4);
        buffer.put(new byte[4]); // 绑定地址 0.0.0.0
        buffer.putShort((short) 0); // 绑定端口 0
        buffer.flip();
        client.write(buffer);
    }

    /**
     * 双向数据转发
     */
    private void relay(SocketChannel client, SocketChannel target, String clientId) {
        // 使用两个线程进行双向转发
        CountDownLatch latch = new CountDownLatch(2);
        
        // 客户端 -> 目标
        Thread clientToTarget = new Thread(() -> {
            try {
                transfer(client, target, "C->T");
            } finally {
                latch.countDown();
            }
        }, "relay-c2t-" + clientId);
        
        // 目标 -> 客户端
        Thread targetToClient = new Thread(() -> {
            try {
                transfer(target, client, "T->C");
            } finally {
                latch.countDown();
            }
        }, "relay-t2c-" + clientId);
        
        clientToTarget.setDaemon(true);
        targetToClient.setDaemon(true);
        clientToTarget.start();
        targetToClient.start();
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 单向数据传输
     */
    private void transfer(SocketChannel from, SocketChannel to, String direction) {
        ByteBuffer buffer = ByteBuffer.allocate(effectiveBufferSize);
        try {
            while (from.isOpen() && to.isOpen()) {
                buffer.clear();
                int read = from.read(buffer);
                if (read <= 0) {
                    break;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    to.write(buffer);
                }
            }
        } catch (IOException e) {
            // 连接关闭
        }
    }

    /**
     * 静默关闭通道
     */
    private void closeQuietly(SocketChannel channel) {
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

        if (delegateServer != null) {
            delegateServer.stop();
            delegateServer = null;
        }
        
        if (selector != null) {
            selector.wakeup();
            selector.close();
        }
        
        if (serverChannel != null) {
            serverChannel.close();
        }
        
        if (workerPool != null) {
            workerPool.shutdown();
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        }
        
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        
        log.info("NIO SOCKS5 代理服务器停止");
    }

    @Override
    public String getProtocolName() {
        return "NIO-SOCKS5";
    }

    @Override
    public String getServerInfo() {
        return String.format("Socks5ProxyServer[%s:%d, mode=%s, running=%s]",
                serverSetting.getHost(), serverSetting.getPort(),
                runMode, isRunning());
    }

    public String getRunMode() {
        return runMode;
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalConnections() {
        return totalConnections.get();
    }
}
