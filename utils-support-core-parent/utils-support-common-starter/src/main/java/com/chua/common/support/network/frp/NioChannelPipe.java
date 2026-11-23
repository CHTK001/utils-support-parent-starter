package com.chua.common.support.network.frp;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单共享 Selector 的 NIO 字节管道：为 FRP 数据面提供「阻塞式 API + 非阻塞内核 IO」的桥接层。
 *
 * <p><b>为什么需要它</b>：Windows 的虚拟线程阻塞流实现（NioSocketImpl）为读、写各注册一个
 * AFD 实例（读 Poller + 写 Poller）。同一连接的两个 AFD 实例之间存在窗口：写方向就绪事件
 * 可能丢失，写方虚拟线程被永久 park，整条转发链互等冻结（loom-dev 2026-02
 * 《Potential lost write IO notifications in Windows when using virtual threads》）。
 * 大数据量回环转发在高水位下偶发触发，表现为 FRP 隧道「第一枪」后停摆。</p>
 *
 * <p><b>修复方式</b>：每条连接只保留一个 {@link SocketChannel}（一个 AFD 实例），注册到
 * <b>全局唯一的共享 Selector</b> 上；读、写就绪都由同一个分发线程驱动——读就绪把字节
 * drain 进有界读队列并唤醒读者；写就绪把写队列排进内核并唤醒写者。虚拟线程按就绪事件
 * park/unpark，写路径带背压与超时（超时关流抛 {@link IOException} 自愈），彻底消除
 * 双 Poller 丢唤醒的结构性风险。</p>
 *
 * <p>对外提供 {@link InputStream} / {@link OutputStream} 阻塞视图，既有流式代码
 * （桥接循环、握手解析）无需改动即可迁移。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
final class NioChannelPipe {

    /**
     * 单次读事件 drain 的缓冲大小
     */
    private static final int DRAIN_BUFFER_SIZE = 64 * 1024;

    /**
     * 单次读事件最多 drain 的字节数（避免单条快连接饿死同 Selector 上的其他连接）
     */
    private static final int DRAIN_LIMIT_PER_EVENT = 512 * 1024;

    /**
     * 读队列字节上限：达到后摘除读兴趣（读侧背压），消费回落到恢复水位再挂回
     */
    private static final int READ_QUEUE_LIMIT = 8 * 1024 * 1024;

    /**
     * 读队列恢复水位：低于该值时重新挂读兴趣（滞后区间防止兴趣位抖动）
     */
    private static final int READ_QUEUE_RESUME = 4 * 1024 * 1024;

    /**
     * 写队列字节上限：达到后写者等待排空（有界背压，防内存无界堆积）
     */
    private static final int WRITE_QUEUE_LIMIT = 4 * 1024 * 1024;

    /**
     * 写超时（毫秒）：超过仍未写尽判定链路坏死，关流抛 IOException 自愈，
     * 替代阻塞流「写方永久 park」的失效形态
     */
    private static final long WRITE_TIMEOUT_MS = 120_000L;

    /**
     * 共享 Selector 单例（懒加载，全部管道共用一个分发线程）
     */
    private static volatile Selector sharedSelector;

    /**
     * Selector 懒加载锁
     */
    private static final Object SELECTOR_INIT_LOCK = new Object();

    /**
     * 底层通道（唯一 AFD 实例：读、写就绪都经这一个注册键通知）
     */
    private final SocketChannel channel;

    /**
     * 注册键（构造后不变）
     */
    private final SelectionKey key;

    /**
     * 状态锁（保护队列、水位、EOF/错误标志，以及兴趣位修改的判定）
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 读者等待条件：读队列有新数据 / EOF / 错误 / 关闭
     */
    private final Condition dataArrived = lock.newCondition();

    /**
     * 写者等待条件：背压排空 / 自己的字节写尽 / 错误 / 关闭
     */
    private final Condition writeProgress = lock.newCondition();

    /**
     * 读队列（drain 缓冲依序排队，读者从队头拷贝）
     */
    private final ArrayDeque<ByteBuffer> readQueue = new ArrayDeque<>();

    /**
     * 写队列（零拷贝包装调用方数组，分发线程按序排入内核）
     */
    private final ArrayDeque<ByteBuffer> writeQueue = new ArrayDeque<>();

    /**
     * 读队列当前字节数
     */
    private int readQueueBytes;

    /**
     * 写队列当前字节数
     */
    private int writeQueueBytes;

    /**
     * 已确认写尽的总字节数（写者以此判断自己的数据是否已全部落入内核）
     */
    private long writtenBytes;

    /**
     * 对端关闭读方向（EOF）
     */
    private boolean eof;

    /**
     * 通道读写发生 IO 错误
     */
    private boolean ioError;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 创建管道并注册到共享 Selector。
     *
     * @param channel 已连接（或刚 accept）的 SocketChannel，将被切为非阻塞
     * @throws IOException 注册失败
     */
    NioChannelPipe(SocketChannel channel) throws IOException {
        this.channel = channel;
        channel.configureBlocking(false);
        Selector selector = sharedSelector();
        this.key = channel.register(selector, SelectionKey.OP_READ, this);
        // 新键必须打断当前 select，确保本轮就绪扫描能看到它
        selector.wakeup();
    }

    /**
     * 获取全局共享 Selector（懒加载）。
     *
     * @return 共享 Selector
     * @throws IOException 打开失败
     */
    private static Selector sharedSelector() throws IOException {
        Selector selector = sharedSelector;
        if (selector != null && selector.isOpen()) {
            return selector;
        }
        synchronized (SELECTOR_INIT_LOCK) {
            if (sharedSelector == null || !sharedSelector.isOpen()) {
                Selector created = Selector.open();
                Thread dispatcher = new Thread(() -> dispatchLoop(created), "frp-nio-dispatcher");
                dispatcher.setDaemon(true);
                dispatcher.start();
                sharedSelector = created;
            }
            return sharedSelector;
        }
    }

    /**
     * 共享 Selector 分发循环：读就绪 drain、写就绪 flush，单线程驱动全部管道。
     *
     * @param selector 共享 Selector
     */
    private static void dispatchLoop(Selector selector) {
        while (selector.isOpen()) {
            try {
                if (selector.select() == 0) {
                    continue;
                }
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey readyKey = iterator.next();
                    iterator.remove();
                    if (!readyKey.isValid()) {
                        continue;
                    }
                    NioChannelPipe pipe = (NioChannelPipe) readyKey.attachment();
                    if ((readyKey.readyOps() & SelectionKey.OP_READ) != 0) {
                        pipe.drain();
                    }
                    if (readyKey.isValid() && (readyKey.readyOps() & SelectionKey.OP_WRITE) != 0) {
                        pipe.flush();
                    }
                }
            } catch (ClosedSelectorException e) {
                return;
            } catch (Throwable t) {
                log.debug("FRP NIO 分发循环异常，已忽略: {}", t.getMessage());
            }
        }
    }

    /**
     * 读就绪：把通道数据 drain 进读队列（单次事件限量，防止饿死其他连接），
     * 队列超限时摘除读兴趣形成读侧背压。
     */
    private void drain() {
        int drained = 0;
        boolean hitEof = false;
        IOException error = null;
        while (drained < DRAIN_LIMIT_PER_EVENT) {
            ByteBuffer buffer = ByteBuffer.allocate(DRAIN_BUFFER_SIZE);
            int n;
            try {
                n = channel.read(buffer);
            } catch (IOException e) {
                error = e;
                break;
            }
            if (n == -1) {
                hitEof = true;
                break;
            }
            if (n == 0) {
                break;
            }
            buffer.flip();
            lock.lock();
            try {
                readQueue.add(buffer);
                readQueueBytes += n;
            } finally {
                lock.unlock();
            }
            drained += n;
        }
        boolean pauseRead = false;
        lock.lock();
        try {
            if (hitEof) {
                eof = true;
            }
            if (error != null) {
                ioError = true;
            }
            dataArrived.signalAll();
            pauseRead = readQueueBytes >= READ_QUEUE_LIMIT;
            if (pauseRead && !eof && error == null) {
                setReadInterest(false);
            }
        } finally {
            lock.unlock();
        }
        if (error != null) {
            log.debug("FRP NIO 管道读错误，关闭管道: {}", error.getMessage());
            close();
        }
    }

    /**
     * 写就绪：把写队列按序排入内核，全部写尽后摘除写兴趣；唤醒等待的写者。
     */
    private void flush() {
        IOException error = null;
        lock.lock();
        try {
            while (!writeQueue.isEmpty()) {
                ByteBuffer head = writeQueue.peek();
                int n;
                try {
                    n = channel.write(head);
                } catch (IOException e) {
                    error = e;
                    break;
                }
                if (n == 0) {
                    // 内核发送缓冲已满，等待下一次写就绪
                    break;
                }
                writeQueueBytes -= n;
                writtenBytes += n;
                if (head.remaining() == 0) {
                    writeQueue.poll();
                }
            }
            if (error != null) {
                ioError = true;
            } else if (writeQueue.isEmpty()) {
                // 队列已排空：摘写兴趣，避免空转（写者入队时会重新挂上）
                setWriteInterest(false);
            }
            writeProgress.signalAll();
        } finally {
            lock.unlock();
        }
        if (error != null) {
            log.debug("FRP NIO 管道写错误，关闭管道: {}", error.getMessage());
            close();
        }
    }

    /**
     * 阻塞读：从读队列拷贝数据；队列空且 EOF 返回 -1；队列空则挂起等待。
     *
     * @param out 目标数组
     * @param off 起始偏移
     * @param len 最长读取字节数
     * @return 实际读取字节数；流结束返回 -1
     * @throws IOException 管道已关闭或连接错误
     */
    private int read(byte[] out, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        lock.lock();
        try {
            while (true) {
                if (closed) {
                    throw new IOException("NIO 管道已关闭");
                }
                ByteBuffer head = readQueue.peek();
                if (head != null) {
                    int n = Math.min(len, head.remaining());
                    head.get(out, off, n);
                    readQueueBytes -= n;
                    if (head.remaining() == 0) {
                        readQueue.poll();
                    }
                    boolean resumeRead = readQueueBytes < READ_QUEUE_RESUME;
                    if (resumeRead) {
                        setReadInterest(true);
                    }
                    return n;
                }
                if (ioError) {
                    throw new IOException("连接读写错误");
                }
                if (eof) {
                    return -1;
                }
                dataArrived.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞写：把字节切片入队（零拷贝），挂写兴趣后等待自己的数据全部写尽。
     * 写队列超限时先等待背压排空；超过写超时仍未能写尽则抛 IOException（由调用方关闭管道自愈）。
     *
     * @param data 源数组
     * @param off  起始偏移
     * @param len  写入字节数
     * @throws IOException 管道已关闭、连接错误或写超时
     */
    private void write(byte[] data, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }
        long target;
        lock.lock();
        try {
            while (true) {
                if (closed) {
                    throw new IOException("NIO 管道已关闭");
                }
                if (ioError) {
                    throw new IOException("连接写入错误");
                }
                if (writeQueueBytes < WRITE_QUEUE_LIMIT) {
                    break;
                }
                writeProgress.awaitUninterruptibly();
            }
            writeQueue.add(ByteBuffer.wrap(data, off, len));
            writeQueueBytes += len;
            target = writtenBytes + len;
            setWriteInterest(true);
        } finally {
            lock.unlock();
        }
        long deadline = System.nanoTime() + WRITE_TIMEOUT_MS * 1_000_000L;
        lock.lock();
        try {
            while (writtenBytes < target) {
                if (closed) {
                    throw new IOException("NIO 管道已关闭");
                }
                if (ioError) {
                    throw new IOException("连接写入错误");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("写超时（" + WRITE_TIMEOUT_MS + "ms 未写尽），链路可能已坏死");
                }
                try {
                    writeProgress.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("写入等待被中断", e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在持锁状态下设置/清除读兴趣（调用方必须已持有 {@link #lock}）。
     *
     * @param set true=挂读兴趣；false=摘读兴趣
     */
    private void setReadInterest(boolean set) {
        synchronized (key) {
            try {
                int ops = key.interestOps();
                int updated = set ? ops | SelectionKey.OP_READ : ops & ~SelectionKey.OP_READ;
                if (updated != ops) {
                    key.interestOps(updated);
                    key.selector().wakeup();
                }
            } catch (CancelledKeyException ignored) {
                // 键已随连接关闭注销：兴趣位无意义
            }
        }
    }

    /**
     * 在持锁状态下设置/清除写兴趣（调用方必须已持有 {@link #lock}）。
     *
     * @param set true=挂写兴趣；false=摘写兴趣
     */
    private void setWriteInterest(boolean set) {
        synchronized (key) {
            try {
                int ops = key.interestOps();
                int updated = set ? ops | SelectionKey.OP_WRITE : ops & ~SelectionKey.OP_WRITE;
                if (updated != ops) {
                    key.interestOps(updated);
                    key.selector().wakeup();
                }
            } catch (CancelledKeyException ignored) {
                // 键已随连接关闭注销：兴趣位无意义
            }
        }
    }

    /**
     * 关闭管道（幂等）：唤醒全部读写等待者并关闭底层通道。
     */
    void close() {
        if (closed) {
            return;
        }
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            dataArrived.signalAll();
            writeProgress.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("关闭 FRP NIO 管道失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * 获取输入流阻塞视图。
     *
     * @return 输入流
     */
    InputStream getInputStream() {
        return new InputStream() {

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = NioChannelPipe.this.read(one, 0, 1);
                return n == -1 ? -1 : (one[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return NioChannelPipe.this.read(b, off, len);
            }

            @Override
            public void close() {
                NioChannelPipe.this.close();
            }
        };
    }

    /**
     * 获取输出流阻塞视图（write 返回即代表数据已全部写入内核发送缓冲）。
     *
     * @return 输出流
     */
    OutputStream getOutputStream() {
        return new OutputStream() {

            @Override
            public void write(int b) throws IOException {
                NioChannelPipe.this.write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                NioChannelPipe.this.write(b, off, len);
            }

            @Override
            public void flush() {
                // 写尽即返回，无应用层缓冲可刷
            }

            @Override
            public void close() {
                NioChannelPipe.this.close();
            }
        };
    }
}
