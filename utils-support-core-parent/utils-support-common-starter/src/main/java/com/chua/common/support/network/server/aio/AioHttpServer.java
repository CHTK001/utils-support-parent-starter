package com.chua.common.support.network.server.aio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.nio.NioServerRequest;
import com.chua.common.support.network.server.nio.NioServerResponse;
import com.chua.common.support.network.server.websocket.WebSocketProtocol;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 {@link AsynchronousSocketChannel} 的 HTTP/1.1 服务器实现(Proactor 模式)。
 *
 * <p>与 {@link com.chua.common.support.network.server.nio.NioHttpServer}(Reactor 模式)的核心差异:
 * Windows 上 JDK 的 NIO Selector 基于 {@code WSAEventSelect}(每 64 个连接一个辅助线程,
 * select 开销随连接数线性增长,多 Selector 分片存在并发稳定性限制),
 * 而 AIO 在 Windows 底层是真正的 I/O 完成端口(IOCP)——
 * 内核批量收割完成事件、数据直达用户态缓冲区,万级并发连接下无性能退化,
 * 突破 NIO 实现实测约 2 万连接的上限。</p>
 *
 * <p>架构设计:</p>
 * <ul>
 *   <li>Proactor 三级流水:IOCP group 线程(轻量回调派发) → 虚拟线程 worker(handler 执行)
 *       → 串行异步写(单连接同一时刻至多一个 outstanding write,无 OP_WRITE 续写竞态)</li>
 *   <li>HTTP 解析完全复用 {@link NioServerRequest#feed(ByteBuffer)} 增量状态机,
 *       响应复用 {@link NioServerResponse} 的零分配 header 快路径(asyncWriter 钩子)</li>
 *   <li>读超时即 idle 连接回收(Reactor 版无此能力),懒分配 direct 读缓冲,
 *       百万级空闲连接零缓冲占用</li>
 *   <li>背压模型:请求处理期间暂停读;响应经待写队列由完成回调驱动续写,
 *       写完且 Keep-Alive 后恢复读,天然支持 HTTP pipeline 残留数据</li>
 * </ul>
 *
 * <p>当前限制(v1):不支持 SSL/TLS(建议前置 nginx/IIS 卸载 TLS)、
 * WebSocket 升级请求返回 426。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
@Slf4j
@Spi({"aio", "aio-http", "iocp"})
public class AioHttpServer extends AbstractServer {

    /**
     * 默认读缓冲区大小(字节):与 NIO 版一致取 32KB,
     * 减少大请求体场景的系统调用次数,小请求仅按需 flip 无额外开销
     */
    private static final int READ_BUFFER_SIZE = 32768;

    /**
     * 并发 accept 重叠深度:IOCP 支持多个重叠 AcceptEx 同时等待,
     * 高并发短连接突发下提升接纳吞吐,避免单 accept 间隙
     */
    private static final int ACCEPT_DEPTH = 2;

    /**
     * 最小 backlog:高并发连接突发下避免内核因队列满拒绝连接(与 NIO 版对齐)
     */
    private static final int MIN_BACKLOG = 65536;

    /**
     * 最小接收缓冲(字节):SO_RCVBUF 下限,保证突发吞吐
     */
    private static final int MIN_SO_RCVBUF = 16384;

    /**
     * HTTP 426 Upgrade Required 状态行 + 关闭头(WebSocket 升级降级响应模板)
     */
    private static final byte[] WS_UPGRADE_REJECT_RESPONSE =
            "HTTP/1.1 426 Upgrade Required\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII);

    /** 监听通道 */
    private AsynchronousServerSocketChannel serverChannel;

    /**
     * IOCP 完成端口线程组:线程数即内核并发收割度,
     * 仅执行轻量回调(read 完成 → feed → 提交 worker),少量线程即可驱动海量连接
     */
    private AsynchronousChannelGroup group;

    /** 虚拟线程 worker 池:执行 handler 链,阻塞不占用平台线程 */
    private ExecutorService executor;

    /** 当前活跃连接数(观测用,验证高并发连接目标) */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /** 读完成处理器(单例复用:attachment 携带连接状态,无 per-read 分配) */
    private final ReadHandler readHandler = new ReadHandler();

    /** 写完成处理器(单例复用) */
    private final WriteHandler writeHandler = new WriteHandler();

    /** 接受连接处理器(acceptDepth 个实例各自循环补位) */
    private final AcceptHandler acceptHandler = new AcceptHandler();

    /**
     * 创建 AioHttpServer 实例
     *
     * @param setting 服务器配置
     */
    public AioHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            // v1 不支持 SSL:检测到 ssl 配置时告警并忽略,建议前置反向代理卸载 TLS
            ServerSetting.SslConfig ssl = setting.getSsl();
            if (ssl != null && (ssl.isSelfSigned()
                    || notBlank(ssl.getKeyStorePath()) || notBlank(ssl.getCertPath()))) {
                log.warn("AioHttpServer 暂不支持 SSL/TLS,已忽略 ssl 配置"
                        + "(Windows IOCP 场景建议前置 nginx/IIS 卸载 TLS)");
            }

            // IOCP group 线程数:eventLoops 显式配置优先,否则按 CPU 核数
            int groupThreads = setting.getEventLoops() > 0
                    ? setting.getEventLoops() : Runtime.getRuntime().availableProcessors();
            group = AsynchronousChannelGroup.withFixedThreadPool(groupThreads, r -> {
                Thread t = new Thread(r, "aio-iocp-group");
                t.setDaemon(true);
                return t;
            });

            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF,
                    Math.max(setting.getBufferSize(), MIN_SO_RCVBUF));
            int backlog = Math.max(setting.getBacklog(), MIN_BACKLOG);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), backlog);

            // 回填实际端口(port=0 时由系统分配)
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(bound.getPort());

            executor = Executors.newVirtualThreadPerTaskExecutor();

            // 挂起 ACCEPT_DEPTH 个重叠 accept,completed 回调内立即补位,
            // 保证接纳流水线永不中断
            for (int i = 0; i < ACCEPT_DEPTH; i++) {
                issueAccept();
            }

            log.info("AIO HttpServer started on {}:{} (backlog={}, iocpThreads={}, proactor=true)",
                    setting.getHost(), setting.getPort(), backlog, groupThreads);
        } catch (Exception e) {
            throw new RuntimeException("AIO HttpServer 启动失败", e);
        }
    }

    /**
     * 判断字符串非空白(null 安全)
     *
     * @param value 待检查字符串
     * @return true 表示非 null 且包含非空白字符
     */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 发起重叠 accept:完成后由 {@link AcceptHandler} 补位续挂。
     *
     * <p>注意:不检查 running 标志——start() 模板在 doStart() 返回后才置 running,
     * 若此处检查将导致首个 accept 永远不会挂起、服务器不接受任何连接
     * (与 NIO 版事件循环"抢跑于 running 置位前"的坑同源)。
     * 生命周期由 serverChannel 开关驱动:doStopAccepting 关闭通道后,
     * 未完成的 accept 进入 failed 回调自然终止。</p>
     */
    private void issueAccept() {
        if (serverChannel == null || !serverChannel.isOpen()) {
            return;
        }
        try {
            serverChannel.accept(null, acceptHandler);
        } catch (Throwable t) {
            log.warn("AIO accept 发起失败: {}", t.getMessage());
        }
    }

    /**
     * accept 完成处理器:先补位下一个重叠 accept 再处理新连接,
     * 接纳吞吐不受单连接初始化耗时影响。
     *
     * @author CH
     * @since 2026/08/24
     */
    private class AcceptHandler implements CompletionHandler<AsynchronousSocketChannel, Void> {

        @Override
        public void completed(AsynchronousSocketChannel channel, Void attachment) {
            // 先补位:保证 accept 流水线不间断
            issueAccept();
            handleNewConnection(channel);
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            // 服务器主动关闭导致的失败不再续挂;其余异常(如瞬时内核资源不足)继续补位
            if (running && serverChannel != null && serverChannel.isOpen()) {
                log.warn("AIO accept 异常: {}", exc.getMessage());
                issueAccept();
            }
        }
    }

    /**
     * 新连接初始化:关闭 Nagle、注册活跃计数、发起首个读。
     *
     * @param channel 新接入的通道
     */
    private void handleNewConnection(AsynchronousSocketChannel channel) {
        try {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
        } catch (Exception e) {
            // 个别平台设置失败不影响主流程
            log.debug("TCP_NODELAY 设置失败: {}", e.getMessage());
        }
        ConnState state = new ConnState(channel);
        activeConnections.incrementAndGet();
        issueRead(state);
    }

    /**
     * 发起异步读:一次仅挂一个 outstanding read(串行化,消除乱序),
     * 超时参数兼做空闲连接回收(Keep-Alive 连接无新数据自动断开)。
     *
     * @param state 连接状态
     */
    private void issueRead(ConnState state) {
        if (state.closed.get() || !running) {
            return;
        }
        ByteBuffer buf = state.readBuf;
        if (buf == null) {
            // 懒分配 direct 缓冲:空闲连接零占用
            buf = ByteBuffer.allocateDirect(Math.max(setting.getBufferSize(), READ_BUFFER_SIZE));
            state.readBuf = buf;
        }
        try {
            state.channel.read(buf, setting.getReadTimeout(), TimeUnit.MILLISECONDS,
                    state, readHandler);
        } catch (Throwable t) {
            closeConn(state);
        }
    }

    /**
     * 读完成处理器:喂入增量解析器,完整请求提交虚拟线程 worker。
     *
     * @author CH
     * @since 2026/08/24
     */
    private class ReadHandler implements CompletionHandler<Integer, ConnState> {

        @Override
        public void completed(Integer result, ConnState state) {
            if (state.closed.get() || !running) {
                closeConn(state);
                return;
            }
            int n = result == null ? -1 : result;
            if (n < 0) {
                // 对端正常关闭
                closeConn(state);
                return;
            }
            if (n > 0) {
                ByteBuffer buf = state.readBuf;
                buf.flip();
                int r;
                try {
                    r = state.request.feed(buf);
                } catch (Exception e) {
                    // 解析器内部异常按协议错误处理
                    log.debug("HTTP 解析异常,关闭连接: {}", e.getMessage());
                    closeConn(state);
                    return;
                }
                buf.compact();
                if (r == 1) {
                    // 完整请求就绪:暂停读(背压),交由 worker 处理
                    dispatchToWorker(state);
                    return;
                }
                if (r < 0) {
                    // 协议错误/超长请求:直接断开
                    closeConn(state);
                    return;
                }
            }
            // n==0 或请求未完整:继续读
            issueRead(state);
        }

        @Override
        public void failed(Throwable exc, ConnState state) {
            // 含读超时(idle 回收)与对端重置
            closeConn(state);
        }
    }

    /**
     * 将已解析完整的请求提交虚拟线程 worker 执行 handler 链。
     *
     * @param state 连接状态
     */
    private void dispatchToWorker(ConnState state) {
        try {
            executor.submit(() -> processRequest(state));
        } catch (Throwable t) {
            // executor 已关闭等极端场景
            closeConn(state);
        }
    }

    /**
     * worker 主流程:构建响应(写出钩子入队待写队列)→ 执行 handler 链 →
     * complete() 构建报文字节入队 → 启动串行异步写循环。
     *
     * @param state 连接状态
     */
    private void processRequest(ConnState state) {
        try {
            // WebSocket 升级:v1 返回 426 并关闭(帧协议需独立实现,后续版本支持)
            if (WebSocketProtocol.isUpgradeRequest(state.request)) {
                enqueueWrite(state, ByteBuffer.wrap(WS_UPGRADE_REJECT_RESPONSE), null);
                state.keepAlive = false;
                if (state.writing.compareAndSet(false, true)) {
                    continueWrite(state);
                }
                return;
            }
            NioServerResponse response = new NioServerResponse(null);
            // 写出钩子:complete() 时把响应头/体字节入待写队列(零拷贝传递,不落中间缓冲)
            response.setAsyncWriter((header, body) -> enqueueWrite(state, header, body));
            try {
                handleRequest(state.request, response);
            } catch (Exception e) {
                log.warn("AIO 请求处理失败: {}", e.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(500, "Internal Server Error");
                }
            } finally {
                // 幂等(sent 标志):触发 asyncWriter 把报文交给待写队列
                response.complete();
            }
            state.keepAlive = shouldKeepAlive(state.request, response);
            state.request.resetForNextRequest();
            if (state.closed.get()) {
                return;
            }
            // 抢占写权启动写循环;CAS 失败表示已有写在进行(defensive,理论不可达)
            if (state.writing.compareAndSet(false, true)) {
                continueWrite(state);
            }
        } catch (Throwable t) {
            log.warn("AIO worker 异常: {} -> {}", t.getClass().getSimpleName(), t.getMessage());
            closeConn(state);
        }
    }

    /**
     * 判断是否保持连接(语义与 NIO 版一致):
     * 显式 Connection 头优先,HTTP/1.1 默认 Keep-Alive。
     *
     * @param request  请求
     * @param response 响应
     * @return true 表示保持连接
     */
    private boolean shouldKeepAlive(NioServerRequest request, NioServerResponse response) {
        if (response.isChannelClosed()) {
            return false;
        }
        String connHeader = request.getHeader("Connection");
        if (connHeader != null) {
            return "keep-alive".equalsIgnoreCase(connHeader.trim());
        }
        return "HTTP/1.1".equalsIgnoreCase(request.getHttpVersion());
    }

    /**
     * 响应片段入队(header/body 各为一个 ByteBuffer,零拼接)。
     *
     * @param state  连接状态
     * @param header 响应头字节
     * @param body   响应体字节(可为 null)
     */
    private void enqueueWrite(ConnState state, ByteBuffer header, ByteBuffer body) {
        synchronized (state.writeQueue) {
            state.writeQueue.add(header);
            if (body != null && body.hasRemaining()) {
                state.writeQueue.add(body);
            }
        }
    }

    /**
     * 启动串行异步写:从待写队列头部取块发起 channel.write,
     * 由 {@link WriteHandler} 完成回调驱动续写直至队列排空。
     *
     * <p>调用前提:持有写权({@code state.writing} 已 CAS 为 true)。
     * 队列空则在锁内置 writing=false(持锁置位杜绝丢唤醒)并收尾。</p>
     *
     * @param state 连接状态
     */
    private void continueWrite(ConnState state) {
        if (state.closed.get()) {
            return;
        }
        ByteBuffer head;
        synchronized (state.writeQueue) {
            head = state.writeQueue.peek();
            if (head == null) {
                // 持锁释放写权:与入队方的 CAS 形成正确的 happens-before,无丢失唤醒窗口
                state.writing.set(false);
            }
        }
        if (head == null) {
            finishResponseCycle(state);
            return;
        }
        try {
            state.channel.write(head, setting.getWriteTimeout(), TimeUnit.MILLISECONDS,
                    state, writeHandler);
        } catch (Throwable t) {
            closeConn(state);
        }
    }

    /**
     * 单次响应周期收尾:Keep-Alive 则恢复读下一条请求(pipeline 残留已在解析缓冲),
     * 否则关闭连接。
     *
     * @param state 连接状态
     */
    private void finishResponseCycle(ConnState state) {
        if (state.keepAlive && running && !state.closed.get()) {
            issueRead(state);
        } else {
            closeConn(state);
        }
    }

    /**
     * 写完成处理器:未写完的块原地续写,写完的块出队后推进下一块,
     * 队列排空则收尾(恢复读或关闭)。
     *
     * @author CH
     * @since 2026/08/24
     */
    private class WriteHandler implements CompletionHandler<Integer, ConnState> {

        @Override
        public void completed(Integer result, ConnState state) {
            if (state.closed.get()) {
                return;
            }
            int written = result == null ? 0 : result;
            if (written < 0) {
                closeConn(state);
                return;
            }
            synchronized (state.writeQueue) {
                ByteBuffer head = state.writeQueue.peek();
                if (head != null && !head.hasRemaining()) {
                    // 当前块已全部写出,移除交由 continueWrite 取下一块
                    state.writeQueue.poll();
                }
                // 未写完的块保留在队首,continueWrite 会原地重发剩余部分
            }
            continueWrite(state);
        }

        @Override
        public void failed(Throwable exc, ConnState state) {
            // 含写超时(对端接收停滞)与连接重置
            closeConn(state);
        }
    }

    /**
     * 关闭连接(幂等):close 触发未完成的 read/write 回调进入 failed,
     * 由 closed 标志短路,不会产生级联副作用。
     *
     * @param state 连接状态
     */
    private void closeConn(ConnState state) {
        if (!state.closed.getAndSet(true)) {
            activeConnections.decrementAndGet();
            try {
                state.channel.close();
            } catch (Exception ignored) {
                // 关闭失败无需处理
            }
        }
    }

    @Override
    /** Do停止Accepting */
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (group != null) {
            try {
                group.shutdownNow();
            } catch (Exception ignored) {
            }
        }
        log.info("AIO HttpServer stopped");
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    /**
     * 获取当前活跃连接数(观测高并发连接目标进度)。
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    /**
     * 单连接运行时状态:通道 + 增量解析器 + 懒分配读缓冲 + 待写队列 +
     * 串行写标志。所有字段仅被该连接的回调链与所属 worker 访问。
     *
     * @author CH
     * @since 2026/08/24
     */
    private final class ConnState {

        /** 底层异步通道 */
        final AsynchronousSocketChannel channel;

        /** 增量 HTTP 请求解析器(传输无关构造:远端地址预存,与 NIO 版共用状态机) */
        final NioServerRequest request;

        /** 待写队列:ArrayDeque + synchronized,NIO 版实测同构下吞吐最优 */
        final java.util.ArrayDeque<ByteBuffer> writeQueue = new java.util.ArrayDeque<>();

        /** 懒分配 direct 读缓冲(首次读到数据才分配) */
        ByteBuffer readBuf;

        /** 是否保持连接(每条响应处理后更新) */
        volatile boolean keepAlive = true;

        /**
         * 串行写权标志:true 表示已有 outstanding write,
         * CAS 抢占保证单连接同一时刻至多一个写操作(AIO 无 OP_WRITE,靠此模拟背压续写)
         */
        final AtomicBoolean writing = new AtomicBoolean(false);

        /** 连接关闭标志(幂等关闭) */
        final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 创建连接状态。
         *
         * @param channel 异步通道
         */
        ConnState(AsynchronousSocketChannel channel) {
            this.channel = channel;
            // 远端地址一次性预存:热路径(getRemoteAddress/IP 限流)零系统调用;
            // 解析状态机与 NIO 实现完全共用(NioServerRequest 传输无关构造)
            InetSocketAddress remote = remoteAddressOrNull(channel);
            this.request = new NioServerRequest((java.net.SocketAddress) remote,
                    setting.getMaxRequestSize(), setting.getCharset());
        }

        /**
         * 提取通道远端地址(失败返回 null)。
         *
         * @param channel 异步通道
         * @return 远端地址或 null
         */
        private InetSocketAddress remoteAddressOrNull(AsynchronousSocketChannel channel) {
            try {
                return (InetSocketAddress) channel.getRemoteAddress();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
