package com.chua.common.support.network.protocol.server.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.request.TelnetServletRequest;
import com.chua.common.support.network.protocol.request.TelnetServletResponse;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import com.chua.common.support.network.protocol.session.TelnetSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于NIO的Telnet协议服务器实现
 * <p>
 * 提供Telnet协议的服务器实现，使用Java NIO：
 * 1. 支持ServletFilter过滤器链处理
 * 2. 支持ConfigureObjectContext上下文管理
 * 3. 支持多客户端并发连接
 * 4. 支持命令行交互
 * 5. 支持文本协议通信
 * 6. 支持连接状态管理
 * 7. 支持自定义命令处理
 * 8. 支持会话管理
 * 9. 支持异步非阻塞I/O
 * 10. 支持连接超时控制
 * 11. 支持automaticOptimization自动优化模式
 * 12. 支持Java 21虚拟线程
 * 13. 支持直接内存缓冲区(Zero Copy)
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
@Spi("telnet")
@SpiDescribe(value = "Telnet协议服务器")
public class TelnetProtocolServer extends AbstractProtocolServer {

    private ServerSocketChannel serverSocketChannel;
    private Selector selector;
    private volatile boolean running = false;
    private Thread selectorThread;
    
    // 连接管理
    private final ConcurrentHashMap<SocketChannel, TelnetSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong connectionCount = new AtomicLong(0);
    
    // 高并发配置
    private int effectiveMaxConnections;
    private int effectiveBufferSize;
    private boolean useDirectBuffer;
    
    // 虚拟线程执行器（处理命令）
    private ExecutorService commandExecutor;
    
    // Telnet协议常量
    private static final String WELCOME_MESSAGE = "欢迎连接到Telnet服务器!\r\n> ";
    private static final String PROMPT = "> ";
    private static final String GOODBYE_MESSAGE = "再见!\r\n";

    public TelnetProtocolServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected void doStart() throws Exception {
        log.info("启动Telnet协议服务器 - 地址: {}:{}", serverSetting.getHost(), serverSetting.getPort());

        try {
            // 配置高并发参数
            configureHighConcurrency();
            
            // 创建命令处理执行器（使用虚拟线程）
            commandExecutor = createCommandExecutor();
            
            // 创建选择器
            selector = Selector.open();
            
            // 创建服务器套接字通道
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            
            // 配置 Socket 选项
            configureServerSocketOptions();
            
            // 绑定地址和端口
            InetSocketAddress address = new InetSocketAddress(serverSetting.getHost(), serverSetting.getPort());
            serverSocketChannel.bind(address, getEffectiveBacklog());
            
            // 注册接受连接事件
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            
            running = true;
            
            // 启动选择器线程（使用虚拟线程）
            selectorThread = Thread.ofVirtual()
                    .name("telnet-selector")
                    .unstarted(this::runSelector);
            selectorThread.start();
            
            log.info("✅ Telnet协议服务器启动成功 - 地址: {}:{} - 自动优化: {} - 最大连接: {} - 缓冲区: {}KB - 直接内存: {}",
                    serverSetting.getHost(), serverSetting.getPort(),
                    serverSetting.isAutomaticOptimization(),
                    effectiveMaxConnections,
                    effectiveBufferSize / 1024,
                    useDirectBuffer);
            
        } catch (Exception e) {
            log.error("Telnet协议服务器启动失败", e);
            throw e;
        }
    }
    
    /**
     * 配置高并发参数。
     * <p>
     * 如果启用automaticOptimization，将自动计算最优参数：
     * - 根据可用内存计算最大连接数
     * - 使用直接内存缓冲区实现Zero Copy
     * - 优化缓冲区大小
     *
     * @author CH
     * @since 2025/12/20
     */
    private void configureHighConcurrency() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();
        
        if (serverSetting.isAutomaticOptimization()) {
            // 自动优化模式
            // 每个连接约占用32KB内存
            long memoryPerConnection = 32 * 1024L;
            long availableForConnections = (long) (maxMemory * 0.5);
            effectiveMaxConnections = (int) Math.min(50000, availableForConnections / memoryPerConnection);
            
            // 使用直接内存缓冲区（Zero Copy）
            useDirectBuffer = true;
            
            // 优化缓冲区大小（8KB）
            effectiveBufferSize = 8 * 1024;
            
            log.info("Telnet自动优化模式 - CPU核心: {}, 最大内存: {}MB, 最大连接: {}, 缓冲区: {}KB, 直接内存: {}",
                    cpuCores, maxMemory / 1024 / 1024, effectiveMaxConnections, 
                    effectiveBufferSize / 1024, useDirectBuffer);
        } else {
            // 使用用户配置或默认值
            effectiveMaxConnections = serverSetting.getMaxConnections() > 0
                    ? serverSetting.getMaxConnections()
                    : 1000;
            useDirectBuffer = false;
            effectiveBufferSize = 1024;
        }
    }
    
    /**
     * 创建命令处理执行器。
     * <p>
     * 自动优化模式使用虚拟线程，否则使用平台线程池。
     *
     * @return ExecutorService
     */
    private ExecutorService createCommandExecutor() {
        if (serverSetting.isAutomaticOptimization()) {
            // 使用Java 21虚拟线程 - 高并发、低开销
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            // 使用平台线程池
            int threads = serverSetting.getWorkerThreads() > 0
                    ? serverSetting.getWorkerThreads()
                    : Runtime.getRuntime().availableProcessors() * 2;
            return Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "telnet-worker");
                t.setDaemon(true);
                return t;
            });
        }
    }
    
    /**
     * 配置ServerSocket选项。
     */
    private void configureServerSocketOptions() throws IOException {
        // 地址重用
        serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        
        // 接收缓冲区大小
        if (serverSetting.isAutomaticOptimization()) {
            serverSocketChannel.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);
        }
    }
    
    /**
     * 获取有效的backlog值。
     */
    private int getEffectiveBacklog() {
        if (serverSetting.isAutomaticOptimization()) {
            return Math.min(effectiveMaxConnections, 4096);
        }
        return serverSetting.getBacklog() > 0 ? serverSetting.getBacklog() : 128;
    }
    
    /**
     * 创建缓冲区。
     * <p>
     * 自动优化模式使用直接内存缓冲区（Zero Copy）。
     *
     * @return ByteBuffer
     */
    private ByteBuffer createBuffer() {
        if (useDirectBuffer) {
            return ByteBuffer.allocateDirect(effectiveBufferSize);
        }
        return ByteBuffer.allocate(effectiveBufferSize);
    }

    @Override
    protected void doStop() throws Exception {
        log.info("停止Telnet协议服务器");
        
        running = false;
        
        try {
            // 关闭所有客户端连接
            for (TelnetSession session : sessions.values()) {
                try {
                    session.close();
                } catch (Exception e) {
                    log.warn("关闭客户端连接失败", e);
                }
            }
            sessions.clear();
            
            // 唤醒选择器
            if (selector != null) {
                selector.wakeup();
            }
            
            // 等待选择器线程结束
            if (selectorThread != null && selectorThread.isAlive()) {
                selectorThread.join(5000);
            }
            
            // 关闭服务器套接字通道
            if (serverSocketChannel != null) {
                serverSocketChannel.close();
            }
            
            // 关闭选择器
            if (selector != null) {
                selector.close();
            }
            
            // 关闭命令执行器
            if (commandExecutor != null && !commandExecutor.isShutdown()) {
                commandExecutor.shutdown();
            }
            
            log.info("❌ Telnet协议服务器停止");
            
        } catch (Exception e) {
            log.error("停止Telnet协议服务器失败", e);
            throw e;
        }
    }

    /**
     * 选择器运行循环
     */
    private void runSelector() {
        log.info("Telnet选择器线程启动");
        
        while (running) {
            try {
                // 等待事件，超时时间1秒
                int readyChannels = selector.select(1000);
                
                if (readyChannels == 0) {
                    continue;
                }
                
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
                
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    
                    try {
                        if (key.isValid()) {
                            if (key.isAcceptable()) {
                                handleAccept(key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            }
                        }
                    } catch (Exception e) {
                        log.error("处理选择器事件失败", e);
                        closeKey(key);
                    }
                }
                
            } catch (Exception e) {
                if (running) {
                    log.error("选择器运行异常", e);
                }
            }
        }
        
        log.info("Telnet选择器线程结束");
    }

    /**
     * 处理接受连接事件
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        
        if (clientChannel != null) {
            // 检查最大连接数
            if (connectionCount.get() >= effectiveMaxConnections) {
                log.warn("达到最大连接数限制 {}，拒绝新连接: {}", 
                        effectiveMaxConnections, clientChannel.getRemoteAddress());
                clientChannel.close();
                return;
            }
            
            clientChannel.configureBlocking(false);
            
            // 配置客户端 Socket 选项
            configureClientSocketOptions(clientChannel);
            
            // 注册读事件
            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            
            // 创建会话
            TelnetSession session = new TelnetSession(clientChannel);
            sessions.put(clientChannel, session);
            clientKey.attach(session);
            
            connectionCount.incrementAndGet();
            
            log.info("新的Telnet客户端连接: {} (总连接数: {})", 
                    clientChannel.getRemoteAddress(), connectionCount.get());
            
            // 发送欢迎消息
            sendMessage(clientChannel, WELCOME_MESSAGE);
        }
    }
    
    /**
     * 配置客户端Socket选项。
     */
    private void configureClientSocketOptions(SocketChannel clientChannel) throws IOException {
        // 禁用Nagle算法，降低延迟
        clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        // Keep-alive
        clientChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        
        if (serverSetting.isAutomaticOptimization()) {
            // 发送缓冲区
            clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 32 * 1024);
            // 接收缓冲区
            clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, 32 * 1024);
        }
    }

    /**
     * 处理读事件
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        TelnetSession session = (TelnetSession) key.attachment();
        
        if (session == null) {
            closeKey(key);
            return;
        }
        
        // 使用配置的缓冲区（可能是直接内存）
        ByteBuffer buffer = createBuffer();
        int bytesRead = clientChannel.read(buffer);
        
        if (bytesRead == -1) {
            // 客户端断开连接
            log.info("Telnet客户端断开连接: {}", clientChannel.getRemoteAddress());
            closeKey(key);
            return;
        }
        
        if (bytesRead > 0) {
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            
            String command = new String(data, StandardCharsets.UTF_8).trim();
            
            // 使用虚拟线程异步处理命令
            if (commandExecutor != null && !commandExecutor.isShutdown()) {
                commandExecutor.execute(() -> handleCommand(clientChannel, session, command));
            } else {
                handleCommand(clientChannel, session, command);
            }
        }
    }

    /**
     * 处理Telnet命令
     */
    private void handleCommand(SocketChannel clientChannel, TelnetSession session, String command) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("收到Telnet命令: {} from {}", command, clientChannel.getRemoteAddress());
            }
            
            // 创建ServletRequest和ServletResponse
            TelnetServletRequest request = createTelnetRequest(clientChannel, session, command);
            TelnetServletResponse response = createTelnetResponse(clientChannel, session);
            
            // 通过doHandle处理请求
            doHandle(request, response);
            
            // 刷新缓冲区，确保 writer/outputStream 的内容同步到 body
            response.flushBuffer();
            
            // 发送响应：优先使用 body，其次 bodyString
            byte[] bodyData = response.getBody();
            String responseText;
            if (bodyData != null && bodyData.length > 0) {
                responseText = new String(bodyData, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                responseText = response.getBodyString();
            }
            if (responseText != null && !responseText.isEmpty()) {
                sendMessage(clientChannel, responseText + "\r\n");
            }
            
            // 发送提示符
            sendMessage(clientChannel, PROMPT);
            
        } catch (Exception e) {
            log.error("处理Telnet命令失败: {}", command, e);
            try {
                sendMessage(clientChannel, "错误: " + e.getMessage() + "\r\n" + PROMPT);
            } catch (IOException ioException) {
                log.error("发送错误消息失败", ioException);
                closeKey(clientChannel.keyFor(selector));
            }
        }
    }

    /**
     * 发送消息到客户端
     */
    private void sendMessage(SocketChannel clientChannel, String message) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            clientChannel.write(buffer);
        }
    }

    /**
     * 关闭连接键
     */
    private void closeKey(SelectionKey key) {
        try {
            SocketChannel clientChannel = (SocketChannel) key.channel();
            TelnetSession session = sessions.remove(clientChannel);
            
            if (session != null) {
                session.close();
            }
            
            key.cancel();
            clientChannel.close();
            
            connectionCount.decrementAndGet();
            
            if (log.isDebugEnabled()) {
                log.debug("Telnet连接关闭 (剩余连接数: {})", connectionCount.get());
            }
            
        } catch (Exception e) {
            log.warn("关闭连接失败", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running && serverSocketChannel != null && serverSocketChannel.isOpen();
    }

    @Override
    public int getConnectionCount() {
        return (int) connectionCount.get();
    }

    @Override
    public String getServerInfo() {
        return String.format("TelnetProtocolServer[%s:%d, running=%s, connections=%d, filters=%d]",
                serverSetting.getHost(),
                serverSetting.getPort(),
                isRunning(),
                getConnectionCount(),
                getFilterCount());
    }

    /**
     * Check if this server can handle the request
     *
     * @param request the request
     * @return true if can handle
     */
    public boolean canHandle(ServletRequest request) {
        return isRunning();
    }

    /**
     * 创建Telnet请求对象
     */
    private TelnetServletRequest createTelnetRequest(SocketChannel clientChannel, TelnetSession session, String command) {
        return new TelnetServletRequest(clientChannel, session, command);
    }

    /**
     * 创建Telnet响应对象
     */
    private TelnetServletResponse createTelnetResponse(SocketChannel clientChannel, TelnetSession session) {
        return new TelnetServletResponse(clientChannel, session);
    }


    @Override
    protected ServletResponse createServletResponse() {
        // 这个方法在Telnet服务器中不会被直接调用
        // 因为响应是在handleCommand中创建的
        throw new UnsupportedOperationException("Telnet服务器使用createTelnetResponse方法创建响应对象");
    }
}
