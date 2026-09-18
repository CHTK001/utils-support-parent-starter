package com.chua.log.support.file.impl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * 异步日志写入器，业务线程不阻塞，后台线程批量刷盘。
 *
 * <p><b>双模式架构：</b></p>
 * <ul>
 *   <li><b>mmap 模式（默认）</b> — 采用<b>分段 + 双缓冲（ping-pong）</b>环形写入：
 *       <ul>
 *         <li><b>分段</b>：按线程 {@code threadId} 哈希到固定段（默认 4 段，2 的幂），
 *             每段独立原子指针，消除单一计数器的多核缓存行争用。</li>
 *         <li><b>双缓冲</b>：每段含 A/B 两块映射区，当前区写满即切换到另一区，
 *             旧区交由后台虚拟线程异步 {@code force()}，业务线程<strong>永不阻塞</strong>。</li>
 *         <li><b>在途计数</b>：每区维护在途写入计数，刷盘前等待归零，
 *             杜绝"旧位置迟到写入"导致的环形覆盖竞态。</li>
 *         <li><b>ThreadLocal 视图</b>：每个线程缓存各缓冲区的 {@link MappedByteBuffer#duplicate()} 视图，
 *             避免逐行创建临时对象。</li>
 *       </ul>
 *   </li>
 *   <li><b>降级模式</b> — 文件映射创建失败（平台限制、空间不足等）时，
 *       自动降级为队列 + 后台虚拟线程批量 {@code FileChannel} 追加写入。</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * try (AsyncLogWriter writer = new AsyncLogWriter.Builder(new File("app.log"))
 *         .preallocateSize(64 * 1024 * 1024)
 *         .segmentCount(4)
 *         .flushIntervalMs(100)
 *         .build()) {
 *     writer.writeLine("业务线程立即返回");
 *     writer.writeLine("另一条日志");
 * } // close() 时停止线程并刷盘
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AsyncLogWriter implements AutoCloseable {

    /**
    * 默认分段数（必须为 2 的幂）
    */
    private static final int DEFAULT_SEGMENT_COUNT = 4;

    /**
    * 默认 mmap 总预分配大小（64MB）
    */
    private static final long DEFAULT_PREALLOCATE_SIZE = 64L * 1024 * 1024;

    /**
    * 默认刷盘间隔（毫秒）
    */
    private static final long DEFAULT_FLUSH_INTERVAL_MS = 100;

    /**
    * 每段单区最小容量（1MB），避免分段过多导致单区过小
    */
    private static final long MIN_SEGMENT_CAPACITY = 1024L * 1024;

    /**
    * 目标日志文件
    */
    private final File file;

    /**
    * 字符集
    */
    private final Charset charset;

    /**
    * 换行符字节（预编码，避免逐行拼接字符串）
    */
    private final byte[] lineSeparatorBytes;

    /**
    * 刷盘间隔（毫秒）
    */
    private final long flushIntervalMs;

    /**
    * 分段数（2 的幂）
    */
    private final int segmentCount;

    /**
    * 各 mmap 写入段，线程按 {@code threadId} 哈希路由
    */
    private final Segment[] segments;

    /**
    * 日志文件通道（mmap 模式持有）
    */
    private final FileChannel channel;

    /**
    * 是否 mmap 模式（false 表示降级模式）
    */
    private final boolean mmapMode;

    /**
    * 降级模式的消息队列（线程安全，支持批量取出）
    */
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

    /**
    * 降级模式常驻追加通道，按需惰性打开
    */
    private volatile FileChannel queueChannel;

    /**
    * 每线程的映射视图缓存，索引 = 段索引 * 2 + 缓冲区索引
    */
    private final ThreadLocal<MappedByteBuffer[]> threadViews;

    /**
    * 后台刷盘线程（虚拟线程）
    */
    private final Thread flushThread;

    /**
    * 后台刷盘线程存活标记
    */
    private volatile boolean running = true;

    /**
    * 私有构造，通过 {@link Builder} 创建。
    *
    * @param builder 构建器
    */
    private AsyncLogWriter(Builder builder) {
        this.file = builder.file;
        this.charset = builder.charset;
        this.lineSeparatorBytes = builder.lineSeparator.getBytes(builder.charset);
        this.flushIntervalMs = builder.flushIntervalMs;
        this.segmentCount = builder.segmentCount;

        MmapResult mmap = initMmap(builder.preallocateSize);
        this.mmapMode = mmap.ok;
        this.segments = mmap.segments;
        this.channel = mmap.channel;
        this.threadViews = ThreadLocal.withInitial(() -> new MappedByteBuffer[segmentCount * 2]);
        this.flushThread = Thread.ofVirtual()
                .name("async-log-" + file.getName())
                .start(this::runFlushLoop);
    }

    /**
    * 初始化分段内存映射，失败则返回降级模式。
    *
    * @param preallocateSize 预分配映射总大小
    * @return 映射结果，{@link MmapResult#ok} 为 false 表示降级
    */
    private MmapResult initMmap(long preallocateSize) {
        ensureParentDir();
        long segmentSize = Math.max(preallocateSize / (2L * segmentCount), MIN_SEGMENT_CAPACITY);
        FileChannel ch = null;
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            ch = raf.getChannel();
            raf.setLength(segmentSize * 2L * segmentCount);
            Segment[] segs = new Segment[segmentCount];
            for (int i = 0; i < segmentCount; i++) {
                long base = i * 2L * segmentSize;
                Segment segment = new Segment(i, (int) segmentSize);
                segment.buffers[0] = ch.map(FileChannel.MapMode.READ_WRITE, base, segmentSize);
                segment.buffers[1] = ch.map(FileChannel.MapMode.READ_WRITE, base + segmentSize, segmentSize);
                segs[i] = segment;
            }
            return new MmapResult(true, ch, segs);
        } catch (IOException | UnsupportedOperationException e) {
            // 平台不支持映射或空间不足，降级为队列 + 批量追加
            closeChannelQuietly(ch);
            return new MmapResult(false, null, null);
        }
    }

    /**
    * 异步写入一行日志，业务线程立即返回。
    *
    * @param line 日志内容
    */
    public void writeLine(String line) {
        if (line == null) {
            return;
        }
        byte[] lineBytes = line.getBytes(charset);
        if (mmapMode) {
            // 按线程哈希路由到固定段，避免单计数器争用
            int shard = (int) (Thread.currentThread().threadId() & (segmentCount - 1));
            writeToSegment(segments[shard], lineBytes);
        } else {
            queue.add(concat(lineBytes));
        }
    }

    /**
    * 写入指定 mmap 段（双缓冲环形写入）。
    *
    * <p>通过原子指针预留空间后写入，预留前先判断是否会越过容量边界，
    * 越界则触发回绕切换（先加锁换区，再重试），保证环形写入不互相覆盖。</p>
    *
    * @param segment 目标段
    * @param bytes   日志字节
    */
    private void writeToSegment(Segment segment, byte[] bytes) {
        int len = bytes.length + lineSeparatorBytes.length;
        if (len > segment.capacity) {
            throw new RuntimeException("日志行超过 mmap 段容量: " + file);
        }
        for (;;) {
            int index = segment.activeIndex;
            long cursor = segment.cursors[index].get();
            if (cursor + len > segment.capacity) {
                // 当前区已满，尝试切换到另一区；若无可用区则等待刷盘完成
                if (!tryRoll(segment, index)) {
                    awaitCleanBuffer(segment);
                }
                continue;
            }
            // 预留空间前先登记在途，刷盘时据此等待迟到写入
            segment.inFlight[index].incrementAndGet();
            long pos = segment.cursors[index].getAndAdd(len);
            if (pos + len > segment.capacity) {
                // 预留期间已被其他线程回绕，释放登记后重试
                segment.inFlight[index].decrementAndGet();
                continue;
            }
            try {
                MappedByteBuffer view = viewFor(segment, index);
                int p = (int) pos;
                view.position(p);
                view.put(bytes);
                view.put(lineSeparatorBytes);
            } finally {
                segment.inFlight[index].decrementAndGet();
            }
            return;
        }
    }

    /**
    * 尝试将写入从 {@code fromIndex} 区切换到另一区。
    *
    * <p>切换前必须保证目标区已完成上一次刷盘（{@code clean}），
    * 源区标记为脏后交由后台线程刷盘。</p>
    *
    * @param segment   目标段
    * @param fromIndex 当前活动区索引
    * @return true 表示已切换（或他人已切换），false 表示无可用区需等待
    */
    private boolean tryRoll(Segment segment, int fromIndex) {
        synchronized (segment.switchLock) {
            if (segment.activeIndex != fromIndex) {
                // 已有其他线程完成切换，直接重试即可
                return true;
            }
            int other = 1 - fromIndex;
            if (!segment.clean[other]) {
                // 另一区仍在刷盘，暂无可用区
                return false;
            }
            segment.clean[fromIndex] = false;
            segment.cursors[other].set(0);
            segment.lastForced[other] = 0;
            segment.activeIndex = other;
            return true;
        }
    }

    /**
    * 等待至少一个缓冲区可用（双缓冲都脏时防御性阻塞，正常流程不会发生）。
    *
    * @param segment 目标段
    */
    private void awaitCleanBuffer(Segment segment) {
        boolean interrupted = false;
        synchronized (segment.switchLock) {
            while (running && !segment.clean[0] && !segment.clean[1]) {
                try {
                    segment.switchLock.wait(flushIntervalMs);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
    * 获取当前线程针对指定缓冲区的映射视图（惰性创建并缓存）。
    *
    * @param segment 目标段
    * @param index   缓冲区索引
    * @return 独立于共享缓冲的重复视图
    */
    private MappedByteBuffer viewFor(Segment segment, int index) {
        MappedByteBuffer[] views = threadViews.get();
        int slot = segment.index * 2 + index;
        MappedByteBuffer view = views[slot];
        if (view == null) {
            view = segment.buffers[index].duplicate();
            views[slot] = view;
        }
        return view;
    }

    /**
    * 拼接日志内容与换行符字节（降级模式使用）。
    *
    * @param lineBytes 日志字节
    * @return 拼接后的完整字节
    */
    private byte[] concat(byte[] lineBytes) {
        byte[] data = new byte[lineBytes.length + lineSeparatorBytes.length];
        System.arraycopy(lineBytes, 0, data, 0, lineBytes.length);
        System.arraycopy(lineSeparatorBytes, 0, data, lineBytes.length, lineSeparatorBytes.length);
        return data;
    }

    /**
    * 后台刷盘循环，按固定间隔执行直到关闭。
    */
    private void runFlushLoop() {
        while (running) {
            try {
                flushOnce();
                Thread.sleep(flushIntervalMs);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            } catch (RuntimeException e) {
                // 单次刷盘异常不终止后台线程，记录后等待下次尝试
                log.warn("[AsyncLogWriter] 刷盘异常: file={} msg={}", file, e.getMessage(), e);
            }
        }
        // 退出前刷盘，避免数据丢失
        finalFlush();
    }

    /**
    * 执行一次刷盘：mmap 模式刷已切换区与活动区，降级模式批量追加。
    */
    private void flushOnce() {
        if (!mmapMode) {
            ensureQueueChannel();
            drainQueue();
            return;
        }
        for (Segment segment : segments) {
            for (int i = 0; i < 2; i++) {
                if (!segment.clean[i]) {
                    forceBuffer(segment, i);
                }
            }
            // 活动区定时持久化，仅刷新增写入区域
            int active = segment.activeIndex;
            forceActive(segment, active);
        }
    }

    /**
    * 刷盘前等待在途写入全部完成（保证环形覆盖安全）。
    *
    * @param segment 目标段
    * @param index   缓冲区索引
    */
    private void forceBuffer(Segment segment, int index) {
        while (segment.inFlight[index].get() != 0) {
            Thread.onSpinWait();
        }
        segment.buffers[index].force();
        synchronized (segment.switchLock) {
            segment.clean[index] = true;
            segment.switchLock.notifyAll();
        }
    }

    /**
    * 持久化活动区的新增写入（按上次刷盘位置去重，只刷新增区间）。
    *
    * @param segment 目标段
    * @param index   缓冲区索引
    */
    private void forceActive(Segment segment, int index) {
        long cursor = segment.cursors[index].get();
        // 临时指针可能因回绕前一步超出容量，跳过本轮即可（下轮刷盘或封区时补刷）
        if (cursor > segment.capacity) {
            return;
        }
        long lastForced = segment.lastForced[index];
        if (cursor <= lastForced) {
            return;
        }
        int from = (int) lastForced;
        int length = (int) (cursor - lastForced);
        // 裁剪至缓冲区范围，防御性防越界
        if (from + length > segment.capacity) {
            length = segment.capacity - from;
        }
        if (length > 0) {
            segment.buffers[index].force(from, length);
        }
        segment.lastForced[index] = cursor;
    }

    /**
    * 确保降级模式追加通道已打开。
    */
    private void ensureQueueChannel() {
        if (queueChannel == null) {
            try {
                queueChannel = FileChannel.open(
                        file.toPath(),
                        StandardOpenOption.APPEND,
                        StandardOpenOption.CREATE);
            } catch (IOException e) {
                log.error("[AsyncLogWriter] 打开日志文件失败: file={} msg={}", file, e.getMessage(), e);
            }
        }
    }

    /**
    * 将队列中的日志批量追加到文件（降级模式）。
    */
    private void drainQueue() {
        FileChannel ch = queueChannel;
        if (ch == null) {
            return;
        }
        List<byte[]> batch = new ArrayList<>(1024);
        queue.drainTo(batch, 1024);
        if (batch.isEmpty()) {
            return;
        }
        try {
            for (byte[] data : batch) {
                ch.write(ByteBuffer.wrap(data));
            }
        } catch (IOException e) {
            // 批量追加失败，记录错误到 stderr 并关闭通道以便下次重开
            log.error("[AsyncLogWriter] 日志写入失败: file={} msg={}", file, e.getMessage(), e);
            closeChannelQuietly(ch);
            queueChannel = null;
        }
    }

    /**
    * 最终刷盘（关闭或线程退出时调用）。
    */
    private void finalFlush() {
        if (!mmapMode) {
            ensureQueueChannel();
            drainQueue();
            return;
        }
        for (Segment segment : segments) {
            for (int i = 0; i < 2; i++) {
                forceBuffer(segment, i);
            }
        }
    }

    /**
    * 确保日志文件父目录存在。
    */
    private void ensureParentDir() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    /**
    * 安静关闭文件通道。
    *
    * @param ch 待关闭通道，可能为 null
    */
    private static void closeChannelQuietly(FileChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // 关闭失败忽略
            }
        }
    }

    @Override
    /** 关闭写入器，刷盘数据并释放当前线程的 ThreadLocal 映射视图缓存 */
    public void close() {
        running = false;
        threadViews.remove();
        flushThread.interrupt();
        try {
            flushThread.join(flushIntervalMs * 2 + 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 最终刷盘并关闭资源
        finalFlush();
        closeChannelQuietly(channel);
        closeChannelQuietly(queueChannel);
    }

    /**
    * mmap 初始化结果（含成功标记、通道与分段）。
    */
    private static final class MmapResult {

        /**
        * 是否初始化成功
        */
        private final boolean ok;

        /**
        * 日志文件通道
        */
        private final FileChannel channel;

        /**
        * 分段数组
        */
        private final Segment[] segments;

        /**
        * 构造结果。
        *
        * @param ok       是否成功
        * @param channel  通道，失败为 null
        * @param segments 分段，失败为 null
        */
        private MmapResult(boolean ok, FileChannel channel, Segment[] segments) {
            this.ok = ok;
            this.channel = channel;
            this.segments = segments;
        }
    }

    /**
    * 单个 mmap 写入段，包含 A/B 双缓冲区、独立指针与在途计数。
    */
    private static final class Segment {

        /**
        * 段索引
        */
        private final int index;

        /**
        * 单区容量（字节）
        */
        private final int capacity;

        /**
        * A/B 两块映射区
        */
        private final MappedByteBuffer[] buffers = new MappedByteBuffer[2];

        /**
        * 各缓冲区写入指针（原子）
        */
        private final AtomicLong[] cursors = new AtomicLong[]{new AtomicLong(), new AtomicLong()};

        /**
        * 各缓冲区在途写入计数（原子），刷盘前等待归零
        */
        private final AtomicLong[] inFlight = new AtomicLong[]{new AtomicLong(), new AtomicLong()};

        /**
        * 各缓冲区是否已刷盘可用（访问需持有 {@link #switchLock}）
        */
        private final boolean[] clean = new boolean[]{true, true};

        /**
        * 各缓冲区上次刷盘位置（去重，减少无谓 force）
        */
        private final long[] lastForced = new long[]{0, 0};

        /**
        * 回绕切换锁，保护活动区索引与干净标记
        */
        private final Object switchLock = new Object();

        /**
        * 当前活动缓冲区索引（0 或 1）
        */
        private volatile int activeIndex;

        /**
        * 构造写入段。
        *
        * @param index    段索引
        * @param capacity 单区容量
        */
        private Segment(int index, int capacity) {
            this.index = index;
            this.capacity = capacity;
        }
    }

    /**
    * {@link AsyncLogWriter} 的构建器。
    *
    * @since 4.0.0.42
    */
    public static final class Builder {

        /**
        * 目标日志文件
        */
        private final File file;

        /**
        * 字符集，默认 UTF-8
        */
        private Charset charset = StandardCharsets.UTF_8;

        /**
        * 换行符，默认系统换行符
        */
        private String lineSeparator = System.lineSeparator();

        /**
        * mmap 总预分配大小，默认 64MB
        */
        private long preallocateSize = DEFAULT_PREALLOCATE_SIZE;

        /**
        * 分段数，默认 4（自动向上取整为 2 的幂）
        */
        private int segmentCount = DEFAULT_SEGMENT_COUNT;

        /**
        * 刷盘间隔毫秒，默认 100ms
        */
        private long flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;

        /**
        * 构造构建器。
        *
        * @param file 目标日志文件
        */
        public Builder(File file) {
            this.file = file;
        }

        /**
        * 设置字符集。
        *
        * @param charset 字符集
        * @return 当前构建器
        */
        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        /**
        * 设置换行符。
        *
        * @param lineSeparator 换行符
        * @return 当前构建器
        */
        public Builder lineSeparator(String lineSeparator) {
            this.lineSeparator = lineSeparator;
            return this;
        }

        /**
        * 设置 mmap 总预分配大小。
        *
        * @param preallocateSize 预分配字节数
        * @return 当前构建器
        */
        public Builder preallocateSize(long preallocateSize) {
            this.preallocateSize = preallocateSize;
            return this;
        }

        /**
        * 设置分段数（自动向上取整为 2 的幂）。
        *
        * @param segmentCount 分段数
        * @return 当前构建器
        */
        public Builder segmentCount(int segmentCount) {
            this.segmentCount = roundUpToPowerOfTwo(Math.max(1, segmentCount));
            return this;
        }

        /**
        * 设置刷盘间隔。
        *
        * @param flushIntervalMs 刷盘间隔毫秒
        * @return 当前构建器
        */
        public Builder flushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
            return this;
        }

        /**
        * 将数值向上取整为 2 的幂。
        *
        * @param value 原始数值
        * @return 不小于原值的 2 的幂
        */
        private static int roundUpToPowerOfTwo(int value) {
            return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
        }

        /**
        * 构建异步日志写入器。
        *
        * @return {@link AsyncLogWriter} 实例
        */
        public AsyncLogWriter build() {
            return new AsyncLogWriter(this);
        }
    }
}
