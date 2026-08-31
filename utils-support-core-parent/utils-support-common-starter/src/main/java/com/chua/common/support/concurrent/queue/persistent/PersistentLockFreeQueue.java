package com.chua.common.support.concurrent.queue.persistent;

import com.chua.common.support.concurrent.queue.LockFreeQueue;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.concurrent.queue.LockFreeQueueFlow;
import com.chua.common.support.concurrent.queue.QueueType;
import com.chua.common.support.wal.WalConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 带 WAL（Write-Ahead Log）的持久化无锁队列，crash 后可通过日志恢复队列状态。
 *
 * <p>核心机制：
 * <ul>
 *   <li>入队 {@link #offer}：先将操作写入 WAL 日志，再写入内存无锁队列</li>
 *   <li>出队 {@link #poll}：先写 WAL 日志（poll 标记），再从内存无锁队列取出</li>
 *   <li>构造时自动恢复：扫描 WAL 文件，回放所有 offer/poll 操作，重建内存队列状态</li>
 *   <li>WAL 写入支持 mmap（内存映射）或 FileChannel 两种模式，由 {@link WalConfig#mmap} 控制</li>
 *   <li>刷盘策略：{@link WalConfig#sync}=true 每次写入后 force 落盘；
 *       false 时降级为异步，由后台线程周期 flush</li>
 * </ul>
 * </p>
 *
 * <p>WAL 记录格式（定长头 + 变长体）：
 * <pre>
 *   [1 byte opType] [4 byte dataLength] [dataLength byte data]
 *   opType: 0=offer, 1=poll, 2=checkpoint(保留)
 * </pre>
 * </p>
 *
 * <p>约束：
 * <ul>
 *   <li>仅支持 {@link QueueType#UNBOUNDED}（无界），保证 offer 永远成功，避免 WAL 与内存状态不一致</li>
 *   <li>泛型元素需通过序列化函数 {@code Function<E,byte[]>} 转为字节</li>
 * </ul>
 * </p>
 *
 * @param <E> 队列元素类型
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PersistentLockFreeQueue<E> implements LockFreeQueue<E>, Closeable {

    /**
     * 操作类型：写入（offer）
     */
    private static final byte OP_OFFER = 0;

    /**
     * 操作类型：读取（poll）
     */
    private static final byte OP_POLL = 1;

    /**
     * WAL 记录头部长度：1 byte opType + 4 byte dataLength
     */
    private static final int HEADER_SIZE = 5;

    /**
     * poll 操作的空数据负载
     */
    private static final byte[] EMPTY_DATA = new byte[0];

    /**
     * 底层无锁内存队列
     */
    private final LockFreeQueue<E> delegate;

    /**
     * 序列化函数：将元素转为字节数组
     */
    private final Function<E, byte[]> serializer;

    /**
     * 反序列化函数：将字节数组转为元素
     */
    private final Function<byte[], E> deserializer;

    /**
     * WAL 配置
     */
    private final WalConfig config;

    /**
     * WAL 文件路径
     */
    private final Path walPath;

    /**
     * meta 文件路径，记录有效写入长度（WAL 之外独立持久化，避免扫描尾部脏数据）
     */
    private final Path metaPath;

    /**
     * 是否使用 mmap 模式
     */
    private final boolean useMmap;

    /**
     * FileChannel 引用（mmap 模式下也用于扩展文件）
     */
    private FileChannel channel;

    /**
     * mmap 缓冲区
     */
    private MappedByteBuffer mmapBuffer;

    /**
     * 写入互斥锁，保证 WAL 写入顺序
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * 当前写入位置偏移量
     */
    private final AtomicLong writePosition = new AtomicLong(0);

    /**
     * mmap 当前映射区大小
     */
    private int mappedSize;

    /**
     * 异步刷盘线程
     */
    private Thread flushThread;

    /**
     * 运行标志位，控制异步刷盘线程
     */
    private volatile boolean running = true;

    /**
     * 创建持久化无锁队列并自动恢复。
     *
     * @param queueType    队列类型，仅支持 UNBOUNDED
     * @param config       WAL 配置
     * @param serializer   元素序列化函数
     * @param deserializer 元素反序列化函数
     * @throws IOException              文件操作失败时抛出
     * @throws IllegalArgumentException 队列类型非 UNBOUNDED 时抛出
     */
    public PersistentLockFreeQueue(QueueType queueType,
                                   WalConfig config,
                                   Function<E, byte[]> serializer,
                                   Function<byte[], E> deserializer) throws IOException {
        if (queueType != QueueType.UNBOUNDED) {
            throw new IllegalArgumentException("PersistentLockFreeQueue 仅支持 UNBOUNDED 队列类型");
        }
        this.config = config;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.useMmap = config.useMemoryMap();
        this.delegate = LockFreeQueueFlow.create(QueueType.UNBOUNDED, 0);
        this.walPath = config.walDir().resolve(config.namespace());
        this.metaPath = walPath.resolveSibling(walPath.getFileName() + ".meta");

        // 创建 WAL 目录
        if (walPath.getParent() != null) {
            Files.createDirectories(walPath.getParent());
        }

        // 先恢复已有日志
        if (Files.exists(walPath)) {
            recover();
        }

        // 打开/创建 WAL 文件用于追加写入
        openForWrite();

        // 启动异步刷盘线程
        if (!config.syncOnWrite() && config.fsyncBatchIntervalMs() > 0) {
            startFlushThread();
        }
    }

    /**
     * 从 WAL 文件恢复队列状态，回放所有 offer/poll 操作。
     *
     * @throws IOException 读取失败时抛出
     */
    private void recover() throws IOException {
        log.info("从 WAL 恢复队列状态: {}", walPath);

        // 检查 meta 文件是否存在，若不存在则视为无有效数据，直接备份并删除 wal
        if (!Files.exists(metaPath)) {
            log.warn("meta 文件不存在，跳过恢复，备份 WAL 文件");
            backupAndCleanup();
            return;
        }

        long validLength = readMetaLength();
        log.info("meta 记录有效长度: {}", validLength);
        if (validLength <= 0) {
            log.warn("meta 文件记录有效长度为 0，跳过恢复，备份 WAL 文件并清理 meta");
            backupAndCleanup();
            Files.deleteIfExists(metaPath);
            return;
        }

        try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize == 0 || validLength > fileSize) {
                log.warn("WAL 文件为空或有效长度超过文件大小，备份并清理");
                backupAndCleanup();
                Files.deleteIfExists(metaPath);
                return;
            }

            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, validLength);
            int recoveredOffers = 0;
            int recoveredPolls = 0;

            while (buf.position() < validLength) {
                if (buf.remaining() < HEADER_SIZE) {
                    break;
                }
                int pos = buf.position();
                byte opType = buf.get();
                int dataLen = buf.getInt();

                // 数据长度非法或超出剩余有效区域，停止恢复
                if (dataLen < 0 || dataLen > buf.remaining()) {
                    buf.position(pos);
                    break;
                }

                byte[] data = new byte[dataLen];
                buf.get(data);

                if (opType == OP_OFFER) {
                    E element = deserializer.apply(data);
                    delegate.offer(element);
                    recoveredOffers++;
                } else if (opType == OP_POLL) {
                    delegate.poll();
                    recoveredPolls++;
                } else {
                    // 未知操作类型，停止恢复
                    buf.position(pos);
                    break;
                }
            }
            log.info("WAL 恢复完成: offer={}, poll={}, 队列剩余={}", recoveredOffers, recoveredPolls, delegate.size());
        }

        backupAndCleanup();
        Files.deleteIfExists(metaPath);
    }

    /**
     * 备份当前 WAL 文件并删除原文件。
     *
     * @throws IOException 文件操作失败时抛出
     */
    private void backupAndCleanup() throws IOException {
        if (Files.exists(walPath)) {
            Path backup = walPath.resolveSibling(walPath.getFileName() + ".bak." + System.currentTimeMillis());
            Files.move(walPath, backup);
            log.info("旧 WAL 已备份至: {}", backup);
        }
    }

    /**
     * 打开 WAL 文件并初始化写入缓冲区。
     *
     * @throws IOException 文件操作失败时抛出
     */
    private void openForWrite() throws IOException {
        this.channel = FileChannel.open(walPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        long existingSize = channel.size();
        this.writePosition.set(existingSize);

        if (useMmap) {
            this.mappedSize = (int) existingSize;
            this.mmapBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, mappedSize);
        }
    }

    /**
     * 扩展 mmap 映射区域（当前映射区写满时调用）。
     *
     * @param requiredSize 需要容纳的记录总大小
     * @throws IOException 映射失败时抛出
     */
    private void expandMmap(int requiredSize) throws IOException {
        long newPos = writePosition.get();
        int newSize = mappedSize;
        while (newPos + requiredSize > newSize) {
            newSize = newSize * 2;
        }
        channel.truncate(newSize);
        mmapBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, newSize);
        mappedSize = newSize;
    }

    /**
     * 入队一个元素：先写 WAL 日志，再写入内存无锁队列。
     *
     * @param element 待入队元素，禁止为 null
     * @return 入队成功返回 true（无界队列恒为 true）
     * @throws NullPointerException     元素为 null 时抛出
     * @throws RuntimeException         WAL 写入失败时抛出
     */
    @Override
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }
        byte[] data = serializer.apply(element);
        writeWal(OP_OFFER, data);
        delegate.offer(element);
        return true;
    }

    /**
     * 出队并移除队首元素：先写 WAL 日志，再从内存无锁队列取出。
     *
     * @return 队首元素；队列为空时返回 null
     * @throws RuntimeException WAL 写入失败时抛出
     */
    @Override
    public E poll() {
        writeWal(OP_POLL, EMPTY_DATA);
        return delegate.poll();
    }

    /**
     * 查看队首元素但不移除，直接委托内存队列。
     *
     * @return 队首元素；队列为空时返回 null
     */
    @Override
    public E peek() {
        return delegate.peek();
    }

    /**
     * 判断队列是否为空。
     *
     * @return 队列为空返回 true
     */
    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    /**
     * 返回队列中的元素数量（近似值）。
     *
     * @return 元素数量
     */
    @Override
    public int size() {
        return delegate.size();
    }

    /**
     * 清空队列，同时清空 WAL。
     */
    @Override
    public void clear() {
        writeLock.lock();
        try {
            delegate.clear();
            // 截断 WAL 文件并重新映射
            if (channel != null) {
                channel.truncate(0);
            }
            writePosition.set(0);
            if (useMmap && channel != null) {
                mmapBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, mappedSize);
            }
        } catch (IOException e) {
            log.warn("清空 WAL 失败", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 无界队列容量返回 Integer.MAX_VALUE。
     *
     * @return {@link Integer#MAX_VALUE}
     */
    @Override
    public int capacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * 关闭队列，刷盘并释放资源。
     *
     * @throws IOException 刷盘或关闭文件失败时抛出
     */
    @Override
    public void close() throws IOException {
        running = false;
        if (flushThread != null) {
            flushThread.interrupt();
        }
        writeLock.lock();
        try {
            if (useMmap && mmapBuffer != null) {
                mmapBuffer.force();
            }
            if (channel != null) {
                if (!useMmap) {
                    channel.force(true);
                }
                channel.close();
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 写入一条 WAL 记录。
     *
     * @param opType 操作类型（OP_OFFER / OP_POLL）
     * @param data   数据负载（poll 时为空数组）
     */
    private void writeWal(byte opType, byte[] data) {
        int recordSize = HEADER_SIZE + data.length;
        writeLock.lock();
        try {
            if (useMmap) {
                long pos = writePosition.get();
                if (pos + recordSize > mappedSize) {
                    expandMmap(recordSize);
                    pos = writePosition.get();
                }
                mmapBuffer.position((int) pos);
                mmapBuffer.put(opType);
                mmapBuffer.putInt(data.length);
                if (data.length > 0) {
                    mmapBuffer.put(data);
                }
                long newPos = pos + recordSize;
                writePosition.set(newPos);
                writeMetaLength(newPos);
                if (config.syncOnWrite()) {
                    mmapBuffer.force();
                }
            } else {
                ByteBuffer buf = ByteBuffer.allocate(recordSize);
                buf.put(opType);
                buf.putInt(data.length);
                if (data.length > 0) {
                    buf.put(data);
                }
                buf.flip();
                channel.write(buf);
                long newPos = writePosition.addAndGet(recordSize);
                writeMetaLength(newPos);
                if (config.syncOnWrite()) {
                    channel.force(false);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("WAL 写入失败", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 启动异步刷盘后台线程。
     */
    private void startFlushThread() {
        flushThread = new Thread(() -> {
            while (running) {
                try {
                    try {
                        ThreadUtils.sleep(config.fsyncBatchIntervalMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    writeLock.lock();
                    try {
                        if (useMmap && mmapBuffer != null) {
                            mmapBuffer.force();
                        } else if (channel != null) {
                            channel.force(false);
                        }
                    } finally {
                        writeLock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("异步刷盘异常", e);
                }
            }
        }, "wal-flush");
        flushThread.setDaemon(true);
        flushThread.start();
    }

    /**
     * 读取 meta 文件中记录的有效写入长度，文件不存在或读取失败时返回 0。
     *
     * @return 有效写入长度（字节），无效时返回 0
     * @throws IOException 读取失败时抛出
     */
    private long readMetaLength() throws IOException {
        if (!Files.exists(metaPath)) {
            return 0L;
        }
        try (FileChannel ch = FileChannel.open(metaPath, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(Long.BYTES);
            while (buf.hasRemaining() && ch.read(buf) != -1) {
                // 循环读取直至读满 8 字节或 EOF
            }
            buf.flip();
            return buf.remaining() == Long.BYTES ? buf.getLong() : 0L;
        }
    }

    /**
     * 写入有效写入长度到 meta 文件（覆盖写入 8 字节 long）。
     *
     * @param length 有效写入长度（字节）
     * @throws IOException 写入失败时抛出
     */
    private void writeMetaLength(long length) throws IOException {
        try (FileChannel ch = FileChannel.open(metaPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(Long.BYTES);
            buf.putLong(length);
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.truncate(Long.BYTES);
            ch.force(false);
        }
    }
}
