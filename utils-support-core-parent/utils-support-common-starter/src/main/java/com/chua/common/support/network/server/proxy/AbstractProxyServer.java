// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.FirewallServerFilter;
import com.chua.common.support.network.server.filter.IpServerFilter;
import com.chua.common.support.network.server.filter.ServerFilter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP 代理服务器抽象基类。
 *
 * <p>提供代理服务器的公共基础设施：</p>
 * <ul>
 *   <li>accept 用虚拟线程池、数据转发用平台线程池，均在 start 创建、stop 关闭</li>
 *   <li>ServerSocket 监听与接受循环</li>
 *   <li>双向数据转发</li>
 *   <li>活跃连接计数（{@link com.chua.common.support.network.server.ServerMetrics}
 *       的连接预算，与 {@code maxConnections} 一次 CAS 判定）</li>
 * </ul>
 *
 * <p>子类只需实现 {@link #handleConnection(Socket)} 即可。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractProxyServer extends AbstractServer {

    /**
     * 虚拟线程池（start 时创建，stop 时关闭）。
     * <p>accept 循环、连接处理（{@link #handleConnection(Socket)}）等短任务由此 executor 管理，
     * 确保 {@code shutdownNow()} 可取消全部活跃任务。数据转发不在此池，见 {@link #pumpPool}。</p>
     */
    protected ExecutorService proxyPool;

    /**
     * 数据转发平台线程池（start 时创建，stop 时关闭）。
     * <p>双向转发的两条阻塞泵各占一个平台线程，绕开虚拟线程阻塞 I/O 的共享 poller
     * （Windows 上 128 并发时 park/unpark 竞争会腰斩吞吐）。代价是每连接常驻 2 个平台线程，
     * 并发连接数建议上限约 2k；更大连接数请用 {@link AioTcpProxyServer}。</p>
     */
    protected ExecutorService pumpPool;

    /**
     * JDK 服务端监听Socket。
     */
    protected ServerSocket serverSocket;

    /**
     * 是否使用非阻塞事件循环批量 accept（默认 false = 阻塞 accept）。
     * <p>设为 true 时改用 {@link ServerSocketChannel} + Selector，每次 select 后
     * 循环 accept 全部就绪连接（批量 drain），瞬时接纳吞吐显著高于阻塞 accept
     * 一次一个。子类（如 TcpProxyServer）可覆写置为 true，Socks5 等保持默认。</p>
     */
    protected volatile boolean preferNonBlockingAccept = false;

    /**
     * 非阻塞 accept 用的服务端通道（仅 {@link #preferNonBlockingAccept} 为 true 时使用）
     */
    protected ServerSocketChannel acceptChannel;

    /**
     * 非阻塞 accept 用的选择器（仅 {@link #preferNonBlockingAccept} 为 true 时使用）
     */
    protected Selector acceptSelector;

    /**
     * 挂在代理节点上的 IP 名单过滤器（含防火墙实现）。
     *
     * <p>代理转发走的是原始 Socket，不经过 {@code AbstractServer} 的过滤器链，
     * 所以这里单独持有一份引用，在建立后端连接前做一次判定。</p>
     */
    private final java.util.List<IpServerFilter> ipFilters = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<FirewallServerFilter> firewalls = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 添加过滤器：IP / 防火墙过滤器额外登记，供连接阶段判定。
     *
     * <p>注册表可能把插件包一层 {@code DelegatingServerFilter}（监控侧的启停包装），
     * 所以这里先按 delegate 拆包再判类型，否则代理侧永远拿不到真正的过滤器。</p>
     *
     * @param filter 过滤器
     * @return 当前服务器
     */
    @Override
    public Server addFilter(ServerFilter filter) {
        super.addFilter(filter);
        ServerFilter real = unwrapFilter(filter);
        if (real instanceof IpServerFilter && !ipFilters.contains(real)) {
            ipFilters.add((IpServerFilter) real);
        }
        if (real instanceof FirewallServerFilter && !firewalls.contains(real)) {
            firewalls.add((FirewallServerFilter) real);
        }
        log.info("[proxy-gate] addFilter class={} ip={} firewall={}", filter.getClass().getName(),
                ipFilters.size(), firewalls.size());
        return this;
    }

    /**
     * 拆掉可能存在的包装层（最多 3 层），拿到真正的过滤器。
     *
     * @param filter 原始过滤器
     * @return 拆包后的过滤器
     */
    private ServerFilter unwrapFilter(ServerFilter filter) {
        ServerFilter current = filter;
        for (int depth = 0; depth < 3 && current != null; depth++) {
            if (current instanceof IpServerFilter || current instanceof FirewallServerFilter) {
                return current;
            }
            ServerFilter next = delegateOf(current);
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /**
     * 反射取包装的 delegate。
     *
     * @param filter 包装对象
     * @return 被包装的过滤器；没有则 null
     */
    private ServerFilter delegateOf(ServerFilter filter) {
        for (String name : new String[]{"getDelegate", "delegate", "getTarget", "unwrap"}) {
            try {
                java.lang.reflect.Method method = filter.getClass().getMethod(name);
                if (!ServerFilter.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Object value = method.invoke(filter);
                if (value instanceof ServerFilter) {
                    return (ServerFilter) value;
                }
            } catch (ReflectiveOperationException ignored) {
                // 没有这个 delegate 方法就继续尝试下一个名字
            }
        }
        return null;
    }

    /**
     * 客户端 IP 是否放行（没有名单类过滤器时恒为 true）。
     *
     * <p>直接问 {@code filterManager} 里已合并的过滤器：注册表在启动/热更新两条路径上都把
     * 过滤器加进 manager，而包装层是监控侧的 {@code DelegatingServerFilter}，所以这里统一
     * 拆包后判定，避免依赖 addFilter 形参的具体类型。</p>
     *
     * @param clientSocket 客户端连接
     * @return true 表示放行
     */
    protected boolean allowClient(Socket clientSocket) {
        if (clientSocket == null) {
            return true;
        }
        String ip = clientSocket.getInetAddress() == null
                ? "" : clientSocket.getInetAddress().getHostAddress();
        for (ServerFilter filter : filterManager.getMergedFilters()) {
            ServerFilter real = unwrapFilter(filter);
            if (real instanceof IpServerFilter && !((IpServerFilter) real).allows(ip)) {
                log.info("[proxy-gate] deny ip={} by IpServerFilter", ip);
                return false;
            }
            if (real instanceof FirewallServerFilter && !((FirewallServerFilter) real).allows(ip)) {
                log.info("[proxy-gate] deny ip={} by FirewallServerFilter", ip);
                return false;
            }
        }
        for (IpServerFilter filter : ipFilters) {
            if (!filter.allows(ip)) {
                log.info("[proxy-gate] deny ip={} by IpServerFilter(list)", ip);
                return false;
            }
        }
        for (FirewallServerFilter filter : firewalls) {
            if (!filter.allows(ip)) {
                log.info("[proxy-gate] deny ip={} by FirewallServerFilter(list)", ip);
                return false;
            }
        }
        return true;
    }

    /**
     * 构造代理服务器。
     *
     * @param setting 服务器配置
     */
    protected AbstractProxyServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 获取当前活跃连接数。
     *
     * @return 活跃连接数
     */
    @Override
    public int getActiveConnections() {
        return metrics.getActiveConnections();
    }

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        rejectStreamEncryption("字节透传代理，加解密必须由端点完成");
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            int backlog = setting.resolveBacklog(MIN_BACKLOG);
            running = true;
// [P3C C1 豁免] 场景化创建线程，需文档化
            proxyPool = Executors.newVirtualThreadPerTaskExecutor();
// [P3C C1 豁免] 转发泵用平台线程：绕开虚拟线程阻塞 I/O 的共享 poller；空闲 60s 回收
            pumpPool = Executors.newCachedThreadPool(r -> {
                // [P3C C1 豁免] 线程工厂必须返回 Thread 实例
                Thread t = new Thread(r, "proxy-pump");
                t.setDaemon(true);
                return t;
            });
            if (preferNonBlockingAccept) {
                // 非阻塞批量 accept：Selector 事件循环驱动，每次 select 后循环 accept 全部就绪连接，
                // 显著提升瞬时接纳吞吐（对标 NioHttpServer 事件循环 accept）
                acceptChannel = ServerSocketChannel.open();
                acceptChannel.configureBlocking(false);
                acceptChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
                acceptChannel.setOption(java.net.StandardSocketOptions.SO_RCVBUF,
                                        Math.max(setting.getBufferSize(), 16384));
                acceptChannel.bind(addr, backlog);
                setting.setPort(((InetSocketAddress) acceptChannel.getLocalAddress()).getPort());
                acceptSelector = Selector.open();
                acceptChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);
                // 单事件循环批量 drain：与其多 acceptor 各自阻塞 accept 一次一个，
                // 不如单一 Selector 循环批量接纳，吞吐由 drain 密度决定
                proxyPool.submit(this::nonBlockingAcceptLoop);
                log.info("{} 启动成功（非阻塞批量 accept）：{}://{}:{} (backlog={}, maxConn={})",
                        getClass().getSimpleName(), setting.getProtocol(), setting.getHost(),
                        setting.getPort(), backlog, Math.max(setting.getMaxConnections(), 0));
                return;
            }
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            // backlog 统一由 ServerSetting#resolveBacklog 收敛（内核会按 somaxconn 再截断一次）
            serverSocket.bind(addr, backlog);
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            // 多 acceptor：使用 bossThreads 控制并行 accept 线程数（ServerSetting.auto() 已按平台生成最优值，
            // 高并发下支撑百万级连接建立;同一 ServerSocket 多线程 accept 为 JDK 支持用法）
            int acceptors = Math.max(1, setting.getBossThreads());
            for (int i = 0; i < acceptors; i++) {
                proxyPool.submit(this::acceptLoop);
            }
            log.info("{} 启动成功：{}://{}:{} (acceptors={}, maxConn={}, backlog={})",
                    getClass().getSimpleName(), setting.getProtocol(), setting.getHost(),
                    setting.getPort(), acceptors, Math.max(setting.getMaxConnections(), 0), backlog);
        } catch (IOException e) {
            throw new RuntimeException(getClass().getSimpleName() + " 启动失败", e);
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        running = false;
        if (preferNonBlockingAccept && acceptChannel != null) {
            try {
                if (acceptSelector != null) {
                    acceptSelector.close();
                }
                acceptChannel.close();
            } catch (IOException e) {
                log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
            }
        } else if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
            }
        }
        if (proxyPool != null) {
            proxyPool.shutdownNow();
            proxyPool = null;
        }
        if (pumpPool != null) {
            pumpPool.shutdownNow();
            pumpPool = null;
        }
        log.info("{} 已停止：{}://{}:{} (peak connections: {})",
                getClass().getSimpleName(), setting.getProtocol(), setting.getHost(),
                setting.getPort(), metrics.getPeakActive());
    }

    /**
     * 接受连接循环。
     * <p>支持多 acceptor 并行（bossThreads > 1 时），
     * 超过 maxConnections 时直接关闭连接并发送 503。</p>
     */
    protected void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // 连接限流：超出上限直接拒绝（一次 CAS 完成判定与登记，无排队、无线程阻塞）
                if (!tryAcquireConnection()) {
                    log.warn("{} 连接数超限 (max={})，拒绝 {}", getClass().getSimpleName(),
                            setting.getMaxConnections(), clientSocket.getRemoteSocketAddress());
                    closeSocket(clientSocket);
                    continue;
                }
                try {
                    proxyPool.submit(() -> {
                        try {
                            // TCP_NODELAY 移到连接处理线程:accept 热路径只做 accept+限流+submit,
                            // 提升瞬时连接接纳能力
                            try {
                                clientSocket.setTcpNoDelay(setting.isTcpNoDelay());
                            } catch (IOException e) {
                                log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
                            }
                            applySocketOptions(clientSocket);
                            handleConnection(clientSocket);
                        } finally {
                            READ_BUFFER.remove();
                            releaseConnection();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    // 停止中任务被拒：连接不会被处理，必须在这里归还预算
                    closeSocket(clientSocket);
                    releaseConnection();
                }
            } catch (IOException e) {
                if (running) {
                    log.error("{} 接受连接异常", getClass().getSimpleName(), e);
                }
            }
        }
    }

    /**
     * 非阻塞事件循环批量 accept（{@link #preferNonBlockingAccept} 为 true 时使用）。
     * <p>每次 select 后循环 accept 全部就绪连接并批量提交到连接处理线程，
     * 瞬时接纳吞吐显著高于阻塞 accept 一次一个，可在突发接入(每秒数千连接)下
     * 避免内核 accept 队列积压导致连接被拒。握手后的 {@link SocketChannel} 通过
     * {@link SocketChannel#socket()} 包装为 {@link Socket}，复用
     * {@link #handleConnection(Socket)} 子类契约。</p>
     */
    protected void nonBlockingAcceptLoop() {
        log.info("{} nonBlockingAcceptLoop started", getClass().getSimpleName());
        while (running) {
            try {
                int n = acceptSelector.select(5000L);
                if (!running) {
                    break;
                }
                java.util.Iterator<SelectionKey> it = acceptSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    // 批量 drain：一次 select 就绪后把内核 accept 队列抽空，
                    // 但必须有上限（else 单次事件循环长时间停在 accept 上，拖慢已建立连接的处理）
                    int batch = Math.max(setting.getAcceptBatch(), 1);
                    int drained = 0;
                    while (running && drained++ < batch) {
                        SocketChannel ch;
                        try {
                            ch = acceptChannel.accept();
                        } catch (java.nio.channels.ClosedChannelException e2) {
                            log.info("{} nonBlockingAcceptLoop: ClosedChannelException, exiting",
                                     getClass().getSimpleName());
                            return;
                        }
                        if (ch == null) {
                            break;
                        }
                        acceptOne(ch);
                    }
                }
            } catch (java.nio.channels.ClosedSelectorException e2) {
                log.info("{} nonBlockingAcceptLoop: ClosedSelectorException, exiting", getClass().getSimpleName());
                return;
            } catch (IOException e) {
                if (running) {
                    log.warn("{} 非阻塞 accept 异常: {}", getClass().getSimpleName(), e.getMessage(), e);
                }
            } catch (Throwable t) {
                // 事件循环线程绝不能死：接收线程一死，服务就不再接纳任何新连接
                if (running) {
                    log.warn("{} 非阻塞 accept 异常: {} {}", getClass().getSimpleName(),
                            t.getClass().getSimpleName(), t.getMessage(), t);
                }
            }
        }
        log.info("{} nonBlockingAcceptLoop ended", getClass().getSimpleName());
    }

    /**
     * 处理一个已 accept 的通道：限流 → 转阻塞 → 提交连接处理任务。
     *
     * @param ch 已接入的非阻塞通道
     */
    private void acceptOne(SocketChannel ch) {
        try {
            ch.configureBlocking(true);
            Socket clientSocket = ch.socket();
            if (!tryAcquireConnection()) {
                log.warn("{} 连接数超限 (max={})，拒绝 {}", getClass().getSimpleName(),
                        setting.getMaxConnections(), clientSocket.getRemoteSocketAddress());
                closeSocket(clientSocket);
                return;
            }
            try {
                proxyPool.submit(() -> {
                    try {
                        try {
                            clientSocket.setTcpNoDelay(setting.isTcpNoDelay());
                        } catch (IOException e) {
                            log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
                        }
                        applySocketOptions(clientSocket);
                        handleConnection(clientSocket);
                    } finally {
                        READ_BUFFER.remove();
                        releaseConnection();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                closeSocket(clientSocket);
                releaseConnection();
            }
        } catch (Exception e) {
            log.warn("{} accept handler exception: {} {}", getClass().getSimpleName(),
                    e.getClass().getSimpleName(), e.getMessage(), e);
            closeSocket(ch.socket());
        }
    }

    /**
     * 静默关闭Socket。
     * @param s 方法入参 s
     */
    private void closeSocket(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * 处理单个客户端连接。子类实现具体协议逻辑。
     *
     * @param clientSocket 客户端Socket
     */
    protected abstract void handleConnection(Socket clientSocket);

    /**
     * 双向转发：客户端 ↔ 后端。
     *
     * <p>两个方向各占一个 {@link #pumpPool} 平台线程（阻塞泵）。平台线程的阻塞读不走
     * 虚拟线程的共享 poller，Windows 上 128 并发吞吐比虚拟线程泵约高一倍；
     * 代价是每连接常驻 2 个平台线程（空闲 60s 回收），并发连接数建议上限约 2k，
     * 更大连接数请用 {@link AioTcpProxyServer}。</p>
     *
     * <p>任一方向结束时，通过关闭双方 Socket 解除另一方向的阻塞。
     * 线程由 pumpPool 管理，shutdownNow() 可中断全部转发。</p>
     *
     * @param clientSocket  客户端Socket
     * @param backendSocket 后端Socket
     */
    protected void forwardBidirectional(Socket clientSocket, Socket backendSocket) {
        // 后端 Socket 也启用 TCP_NODELAY
        try {
            backendSocket.setTcpNoDelay(setting.isTcpNoDelay());
        } catch (IOException e) {
            log.debug("[ProxyServer] 设置后端 Socket 的 TCP_NODELAY 失败: {}", e.getMessage());
        }
        // 两个方向均提交到 pumpPool，通过 CompletableFuture 阻塞调用者
        // 确保 handleConnection() 的 try-finally 正确跟踪连接生命周期
        CompletableFuture<Void> c2b = CompletableFuture.runAsync(() -> {
            try {
                forward(clientSocket.getInputStream(), backendSocket.getOutputStream());
            } catch (IOException e) {
                log.debug("[proxy] c2b 流获取失败: {}", e.getMessage());
            } finally {
                closeQuietly(clientSocket);
                closeQuietly(backendSocket);
            }
        }, pumpPool);
        CompletableFuture<Void> b2c = CompletableFuture.runAsync(() -> {
            try {
                forward(backendSocket.getInputStream(), clientSocket.getOutputStream());
            } catch (IOException e) {
                log.debug("[proxy] b2c 流获取失败: {}", e.getMessage());
            } finally {
                closeQuietly(clientSocket);
                closeQuietly(backendSocket);
            }
        }, pumpPool);
        // 阻塞等待两个方向都完成（shutdownNow 会关闭 Socket 解除阻塞）
        try {
            c2b.join();
        } catch (Exception e) {
            log.warn("[AbstractProxyServer] c2b join interrupted", e);
        }
        try {
            b2c.join();
        } catch (Exception e) {
            log.warn("[AbstractProxyServer] b2c join interrupted", e);
        }
    }

    /**
     * 转发缓冲区大小（64KB）。
     * <p>泵线程是平台线程且会被池复用,ThreadLocal 缓冲可长期复用,适当增大 buffer 提升吞吐量。
     * 相比 8KB,大文件转发场景吞吐量提升约 2x;相比 32KB,大报文场景
     * read/write 系统调用进一步减半。</p>
     */
    private static final int FORWARD_BUFFER_SIZE = 64 * 1024;

    /**
     * 握手队列最小长度（等价于「用平台允许的最大队列」，内核按 somaxconn 截断）。
     */
    private static final int MIN_BACKLOG = 65536;

    /**
     * 转发用缓冲区，每泵线程独立缓存（平台线程池复用后长期有效）。
     */
    private static final ThreadLocal<byte[]> FORWARD_BUFFER =
            ThreadLocal.withInitial(() -> new byte[FORWARD_BUFFER_SIZE]);

    /**
     * 单向数据转发。
     *
     * @param in  源输入流
     * @param out 目标输出流
     */
    protected void forward(InputStream in, OutputStream out) {
        try {
            byte[] buffer = FORWARD_BUFFER.get();
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (Exception e) {
            if (running) {
                log.debug("[proxy] 转发结束: {}", e.getMessage());
            }
        } finally {
            FORWARD_BUFFER.remove();
        }
    }

    /**
     * 安全关闭 Socket。
     * @param socket 方法入参 socket
     */
    protected static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * 读取指定字节数。
     *
     * @param in    输入流
     * @param count 字节数
     * @return 字节数组（长度恰好为 {@code count}）
     * @throws IOException IO 异常
     *
     * 内部仍复用线程内缓冲以减少中间分配，但返回值是精确长度的副本：
     * 早先直接返回复用缓冲，导致 SOCKS5 的 IPv4/IPv6 目标地址长度非法
     * （getByAddress 抛异常 → target 为空 → 回 4 host unreachable），
     * 域名也会带上缓冲里的残留字节。
     */
    private static final ThreadLocal<byte[]> READ_BUFFER =
            ThreadLocal.withInitial(() -> new byte[64]);

    /**
     * 从输入流精确读取 {@code count} 字节。
     *
     * @param in    输入流
     * @param count 字节数
     * @return 读取的字节（复用缓冲；调用方须在下次调用前消费完）
     * @throws IOException IO 异常
     */
    protected static byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] bytes = READ_BUFFER.get();
        if (bytes.length < count) {
            bytes = new byte[Math.max(count, 64)];
            READ_BUFFER.set(bytes);
        }
        int offset = 0;
        while (offset < count) {
            int n = in.read(bytes, offset, count - offset);
            if (n == -1) {
                throw new IOException("连接提前关闭");
            }
            offset += n;
        }
        // 必须返回精确长度的副本：缓冲是线程内复用的，直接把它交出去会让调用方拿到 64 字节，
        // 于是 InetAddress.getByAddress(...) 因长度非法抛 UnknownHostException，
        // new String(...) 也会把缓冲里的残留字节解进结果（SOCKS5 域名就是这样被污染的）。
        // 代价是每次握手多几次小对象分配，与转发热路径无关。
        return java.util.Arrays.copyOf(bytes, count);
    }

    /**
     * 读取 2 字节端口号（网络字节序）。
     *
     * @param in 输入流
     * @return 端口号
     * @throws IOException IO 异常
     */
    protected static int readPort(InputStream in) throws IOException {
        byte[] port = readBytes(in, 2);
        return ((port[0] & 0xFF) << 8) | (port[1] & 0xFF);
    }}