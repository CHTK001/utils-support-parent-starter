package com.chua.common.support.network.server.nio;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Set;

/**
 * 基于 {@link SSLEngine} 的 SSL 包装通道。
 *
 * <p>将普通 {@link SocketChannel} 包装为 TLS 通道：构造时完成阻塞式 TLS 握手，
 * 之后 {@link #read(ByteBuffer)} / {@link #write(ByteBuffer)} 自动完成
 * 密文 <-> 明文的加解密转换。上层（如 {@link NioServerRequest} / {@link NioServerResponse}）
 * 无需感知 SSL 的存在，直接按普通通道使用即可。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>SSLEngine 阻塞模式——握手与读写均在当前线程内完成，配合虚拟线程开销极低</li>
 *   <li>单连接单引擎，无共享状态，线程安全由连接隔离保证</li>
 *   <li>关闭时尽力发送 close_notify 通知对端</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/15
 */
public class SslSocketChannel extends SocketChannel {

    /**
     * 底层原始通道（密文通道）。
     */
    private final SocketChannel delegate;

    /**
     * TLS 引擎，负责握手与加解密。
     */
    private final SSLEngine engine;

    /**
     * 网络密文输入缓冲（从 socket 读入的密文，flip 状态，等待 unwrap）。
     */
    private final ByteBuffer netIn;

    /**
     * 网络密文输出缓冲（wrap 产生的密文，等待写入 socket）。
     */
    private final ByteBuffer netOut;

    /**
     * 应用明文缓冲（unwrap 产生的明文，等待上层读取）。
     */
    private final ByteBuffer appOut;

    /**
     * 握手是否已完成。
     */
    private boolean handshakeDone;

    /**
     * 通道是否已关闭。
     */
    private boolean closed;

    /**
     * 构造 SSL 通道并立即执行阻塞式 TLS 握手。
     *
     * @param delegate 底层已连接的原生通道
     * @param engine   TLS 引擎（需已配置服务端模式）
     * @throws IOException 握手失败或底层 IO 异常
     */
    public SslSocketChannel(SocketChannel delegate, SSLEngine engine) throws IOException {
        super(delegate.provider());
        this.delegate = delegate;
        // 服务端模式，不要求客户端证书
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(false);
        this.engine = engine;
        // 64KB 缓冲足以容纳任意单条 TLS 记录（最大 16KB + 附加开销）
        this.netIn = ByteBuffer.allocate(64 * 1024);
        this.netOut = ByteBuffer.allocate(64 * 1024);
        this.appOut = ByteBuffer.allocate(64 * 1024);
        handshake();
    }

    // ==================== TLS 握手 ====================

    /**
     * 阻塞式 TLS 握手，直至 {@link SSLEngineResult.HandshakeStatus#FINISHED}。
     */
    private void handshake() throws IOException {
        engine.beginHandshake();
        while (true) {
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            switch (hs) {
                case NEED_WRAP -> {
                    ByteBuffer empty = ByteBuffer.allocate(0);
                    check(engine.wrap(empty, netOut), "握手 wrap");
                    flushNetOut();
                }
                case NEED_UNWRAP -> {
                    if (!netIn.hasRemaining() && !readNet()) {
                        throw new SSLException("TLS 握手期间连接被对端关闭");
                    }
                    check(engine.unwrap(netIn, appOut), "握手 unwrap");
                }
                case NEED_TASK -> runTasks();
                case FINISHED, NOT_HANDSHAKING -> {
                    handshakeDone = true;
                    return;
                }
            }
        }
    }

    // ==================== 读写 ====================

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (closed) {
            return -1;
        }
        if (!handshakeDone) {
            handshake();
        }
        while (true) {
            // 优先消费已解密的明文
            if (appOut.hasRemaining()) {
                return copy(appOut, dst);
            }
            // 密文耗尽则阻塞读取网络
            if (!netIn.hasRemaining() && !readNet()) {
                return -1;
            }
            SSLEngineResult r = engine.unwrap(netIn, appOut);
            switch (r.getStatus()) {
                case OK -> {
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        runTasks();
                    }
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                        // 重协商等场景需要向对端发送握手数据
                        flushNetOut();
                    }
                    if (appOut.hasRemaining()) {
                        return copy(appOut, dst);
                    }
                }
                case BUFFER_UNDERFLOW -> {
                    // 单条记录不完整，继续读网络
                    if (!readNet()) {
                        return -1;
                    }
                }
                case BUFFER_OVERFLOW -> {
                    // 防御性处理：明文缓冲满时先交付上层
                    if (appOut.hasRemaining()) {
                        return copy(appOut, dst);
                    }
                    return 0;
                }
                case CLOSED -> {
                    return -1;
                }
            }
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (closed) {
            throw new IOException("Channel is closed");
        }
        if (!handshakeDone) {
            handshake();
        }
        int total = 0;
        while (src.hasRemaining()) {
            SSLEngineResult r = engine.wrap(src, netOut);
            switch (r.getStatus()) {
                case OK -> {
                    flushNetOut();
                    total += r.bytesConsumed();
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        runTasks();
                    }
                }
                case BUFFER_OVERFLOW -> {
                    // 密文缓冲满，先写出
                    flushNetOut();
                }
                case BUFFER_UNDERFLOW ->
                        throw new SSLException("wrap 阶段不应出现 BUFFER_UNDERFLOW");
                case CLOSED -> throw new SSLException("TLS 会话已关闭，无法写入");
            }
        }
        return total;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            int n = read(dsts[i]);
            if (n < 0) {
                return total > 0 ? total : -1;
            }
            total += n;
            if (n == 0) {
                break;
            }
        }
        return total;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            total += write(srcs[i]);
        }
        return total;
    }

    // ==================== 内部工具 ====================

    /**
     * 从底层通道读取密文到 {@link #netIn}。
     *
     * @return true 表示读取成功（含 0 字节重试后的数据）；false 表示对端关闭（EOF）
     */
    private boolean readNet() throws IOException {
        netIn.clear();
        int n;
        do {
            n = delegate.read(netIn);
        } while (n == 0);
        if (n < 0) {
            return false;
        }
        netIn.flip();
        return true;
    }

    /**
     * 将 {@link #netOut} 中的密文全部写入底层通道。
     */
    private void flushNetOut() throws IOException {
        netOut.flip();
        while (netOut.hasRemaining()) {
            if (delegate.write(netOut) < 0) {
                throw new IOException("对端关闭连接，密文写入失败");
            }
        }
        netOut.clear();
    }

    /**
     * 执行引擎委托的任务（如 RSA 解密），阻塞当前线程。
     */
    private void runTasks() {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
    }

    /**
     * 校验 wrap/unwrap 结果，非 OK 状态抛出异常。
     */
    private static void check(SSLEngineResult result, String phase) throws SSLException {
        if (result.getStatus() != SSLEngineResult.Status.OK
                && result.getStatus() != SSLEngineResult.Status.CLOSED) {
            throw new SSLException(phase + " 失败: " + result.getStatus());
        }
    }

    /**
     * 将 src 中尽可能多的字节拷贝到 dst。
     *
     * @return 实际拷贝字节数
     */
    private static int copy(ByteBuffer src, ByteBuffer dst) {
        int n = Math.min(src.remaining(), dst.remaining());
        if (n == 0) {
            return 0;
        }
        ByteBuffer slice = src.slice();
        slice.limit(n);
        dst.put(slice);
        src.position(src.position() + n);
        return n;
    }

    // ==================== SocketChannel 委托 ====================

    @Override
    public SocketChannel bind(SocketAddress local) throws IOException {
        delegate.bind(local);
        return this;
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return delegate.getLocalAddress();
    }

    @Override
    public SocketChannel shutdownInput() throws IOException {
        delegate.shutdownInput();
        return this;
    }

    @Override
    public SocketChannel shutdownOutput() throws IOException {
        delegate.shutdownOutput();
        return this;
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public boolean isConnectionPending() {
        return delegate.isConnectionPending();
    }

    @Override
    public boolean connect(SocketAddress remote) throws IOException {
        return delegate.connect(remote);
    }

    @Override
    public boolean finishConnect() throws IOException {
        return delegate.finishConnect();
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return delegate.getRemoteAddress();
    }

    @Override
    public Socket socket() {
        return delegate.socket();
    }

    @Override
    public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException {
        delegate.setOption(name, value);
        return this;
    }

    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        return delegate.getOption(name);
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return delegate.supportedOptions();
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        delegate.configureBlocking(block);
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // 尽力发送 close_notify 通知对端，失败则忽略
            engine.closeOutbound();
            try {
                ByteBuffer empty = ByteBuffer.allocate(0);
                SSLEngineResult r = engine.wrap(empty, netOut);
                if (r.getStatus() == SSLEngineResult.Status.OK
                        || r.getStatus() == SSLEngineResult.Status.CLOSED) {
                    flushNetOut();
                }
            } catch (Exception ignored) {
            }
        } finally {
            delegate.close();
        }
    }
}
