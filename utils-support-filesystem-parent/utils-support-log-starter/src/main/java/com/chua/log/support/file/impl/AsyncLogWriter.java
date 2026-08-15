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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步日志写入器，业务线程不阻塞，后台线程批量刷盘。
 *
 * <p><b>双模式架构：</b></p>
 * <ul>
 *   <li><b>mmap 模式（默认）</b> — 通过 {@link FileChannel#map} 将文件映射到内存，
 *       业务线程直接写入 {@link MappedByteBuffer}，后台虚拟线程定时 {@code force()} 刷盘，
 *       避免系统调用和业务线程阻塞，性能最高。</li>
 *   <li><b>降级模式</b> — 文件映射创建失败（平台限制、空间不足等）时，
 *       自动降级为队列 + 后台虚拟线程批量 {@code FileChannel} 追加写入。</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * try (AsyncLogWriter writer = new AsyncLogWriter.Builder(new File("app.log"))
 *         .preallocateSize(64 * 1024 * 1024)
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
public class AsyncLogWriter implements AutoCloseable {

    /**
     * 默认 mmap 预分配大小（64MB）
     */
    private static final long DEFAULT_PREALLOCATE_SIZE = 64L * 1024 * 1024;

    /**
     * 默认刷盘间隔（毫秒）
     */
    private static final long DEFAULT_FLUSH_INTERVAL_MS = 100;

    /**
     * 目标日志文件
     */
    private final File file;

    /**
     * 字符集
     */
    private final Charset charset;

    /**
     * 换行符
     */
    private final String lineSeparator;

    /**
     * 刷盘间隔（毫秒）
     */
    private final long flushIntervalMs;

    /**
     * 当前写入位置（原子）
     */
    private final AtomicLong writePosition = new AtomicLong();

    /**
     * 降级模式的消息队列（线程安全）
     */
    private final ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();

    /**
     * 后台刷盘线程存活标记
     */
    private volatile boolean running = true;

    /**
     * 内存映射文件通道
     */
    private FileChannel mmapChannel;

    /**
     * 内存映射缓冲
     */
    private MappedByteBuffer mappedBuffer;

    /**
     * 是否 mmap 模式（false 表示降级模式）
     */
    private final boolean mmapMode;

    /**
     * 后台刷盘线程（虚拟线程）
     */
    private final Thread flushThread;

    /**
     * 私有构造，通过 {@link Builder} 创建。
     *
     * @param builder 构建器
     */
    private AsyncLogWriter(Builder builder) {
        this.file = builder.file;
        this.charset = builder.charset;
        this.lineSeparator = builder.lineSeparator;
        this.flushIntervalMs = builder.flushIntervalMs;

        this.mmapMode = initMmap(builder.preallocateSize);
        this.flushThread = Thread.ofVirtual()
                .name("async-log-" + file.getName())
                .start(this::runFlushLoop);
    }

    /**
     * 初始化内存映射，失败则返回 false 表示降级模式。
     *
     * @param preallocateSize 预分配映射大小
     * @return 成功创建 mmap 返回 true
     */
    private boolean initMmap(long preallocateSize) {
        ensureParentDir();
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            mmapChannel = raf.getChannel();
            mappedBuffer = mmapChannel.map(FileChannel.MapMode.READ_WRITE, 0, preallocateSize);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            // 平台不支持映射或空间不足，降级为队列 + 批量追加
            closeChannelQuietly(mmapChannel);
            mmapChannel = null;
            mappedBuffer = null;
            return false;
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
        byte[] bytes = (line + lineSeparator).getBytes(charset);
        if (mmapMode) {
            writeToMmap(bytes);
        } else {
            queue.add(bytes);
        }
    }

    /**
     * 写入内存映射缓冲（mmap 模式）。
     *
     * <p>通过原子指针预留空间，写入映射缓冲的副本，避免线程间读写竞争。</p>
     *
     * @param bytes 日志字节
     */
    private void writeToMmap(byte[] bytes) {
        long pos = writePosition.getAndAdd(bytes.length);
        if (pos + bytes.length > mappedBuffer.capacity()) {
            // 缓冲已满，先刷盘并清理写入位置
            writePosition.set(0);
            mappedBuffer.force();
            pos = writePosition.getAndAdd(bytes.length);
            if (pos + bytes.length > mappedBuffer.capacity()) {
                throw new RuntimeException("日志行超过 mmap 缓冲容量: " + file);
            }
        }
        MappedByteBuffer dup = mappedBuffer.duplicate();
        dup.position((int) pos);
        dup.put(bytes);
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
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 退出前刷盘，避免数据丢失
        flushOnce();
    }

    /**
     * 执行一次刷盘：mmap 模式 force，降级模式批量追加。
     */
    private void flushOnce() {
        try {
            if (mmapMode) {
                if (writePosition.get() > 0) {
                    mappedBuffer.force();
                }
            } else {
                drainQueue();
            }
        } catch (Exception e) {
            // 刷盘失败不中断后台线程，等待下次尝试
        }
    }

    /**
     * 将队列中的日志批量追加到文件（降级模式）。
     */
    private void drainQueue() {
        if (queue.isEmpty()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.APPEND,
                StandardOpenOption.CREATE)) {
            byte[] data;
            while ((data = queue.poll()) != null) {
                channel.write(ByteBuffer.wrap(data));
            }
        } catch (IOException e) {
            // 批量追加失败，记录错误到 stderr 避免影响业务
            System.err.println("[AsyncLogWriter] 日志写入失败: " + file + " - " + e.getMessage());
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
     * @param channel 待关闭通道，可能为 null
     */
    private static void closeChannelQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭失败忽略
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            flushThread.join(flushIntervalMs * 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 最终刷盘
        flushOnce();
        closeChannelQuietly(mmapChannel);
    }

    /**
     * {@link AsyncLogWriter} 的构建器。
     *
     * @author CH
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
         * mmap 预分配大小，默认 64MB
         */
        private long preallocateSize = DEFAULT_PREALLOCATE_SIZE;

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
         * 设置 mmap 预分配大小。
         *
         * @param preallocateSize 预分配字节数
         * @return 当前构建器
         */
        public Builder preallocateSize(long preallocateSize) {
            this.preallocateSize = preallocateSize;
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
         * 构建异步日志写入器。
         *
         * @return {@link AsyncLogWriter} 实例
         */
        public AsyncLogWriter build() {
            return new AsyncLogWriter(this);
        }
    }
}