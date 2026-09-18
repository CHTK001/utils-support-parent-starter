package com.chua.common.support.concurrent.offset.provider;

import com.chua.common.support.concurrent.offset.Offset;
import com.chua.common.support.concurrent.offset.OffsetConfig;
import com.chua.common.support.concurrent.offset.OffsetStore;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
* 文件 offset 存储实现。
*
* @author CH
* @since 4.0.0.43
 */
@Slf4j
@Spi("file")
public class FileOffsetStore implements OffsetStore {

    /**
    * offset 数据长度（字节）：8 字节 long
    */
    private static final int OFFSET_BYTE_LENGTH = 8;

    /**
    * offset 缓存
    */
    private final Map<String, Offset> cache = new ConcurrentHashMap<>();

    /**
    * 是否已启动
    */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
    * 配置
    */
    private final OffsetConfig config;

    /**
    * 创建 FileOffsetStore 实例
    * @param config config
    */
    public FileOffsetStore(OffsetConfig config) {
        this.config = config;
    }

    @Override
    /** 获取Offset */
    public Offset getOffset(String subscriberId) {
        return cache.computeIfAbsent(subscriberId, this::createOffset);
    }

    @Override
    /** 移除Offset */
    public Offset removeOffset(String subscriberId) {
        Offset removed = cache.remove(subscriberId);
        if (removed != null) {
            removed.close();
        }
        return removed;
    }

    @Override
    /** Truncate */
    public void truncate() {
        cache.values().forEach(Offset::close);
        cache.clear();
        if (config.isPersistent()) {
            try {
                Path dir = config.getBasePath();
                if (Files.exists(dir)) {
                    try (var stream = Files.list(dir)) {
                        stream.forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("删除 offset 文件失败: {}", p, e);
                            }
                        });
                    }
                }
            } catch (IOException e) {
                log.warn("删除 offset 目录文件失败", e);
            }
        }
    }

    @Override
    /** 开始 */
    public void start() {
        if (running.compareAndSet(false, true)) {
            if (config.isPersistent()) {
                try {
                    Files.createDirectories(config.getBasePath());
                } catch (IOException e) {
                    log.error("创建 offset 目录失败: {}", config.getBasePath(), e);
                }
            }
            log.info("FileOffsetStore 已启动: basePath={}, persistent={}", config.getBasePath(), config.isPersistent());
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        running.set(false);
        cache.values().forEach(Offset::close);
        cache.clear();
    }

    /**
     * 创建Offset
     * @param subscriberId subscriberID，不允许为 null
     * @return 偏移量 对象
     */
    private Offset createOffset(String subscriberId) {
        return new FileOffset(subscriberId);
    }

    /**
    * 文件 offset 实现。
    */
    private class FileOffset implements Offset {

        /**
        * 订阅器 ID
        */
        private final String subscriberId;

        /**
        * offset 文件路径
        */
        private final Path offsetPath;

        /**
        * 当前 offset 值（内存副本）
        */
        private volatile long value;

        /**
        * 是否关闭
        */
        private volatile boolean closed;

        FileOffset(String subscriberId) {
            this.subscriberId = subscriberId;
            this.offsetPath = config.getBasePath().resolve(subscriberId + ".offset");
            this.value = loadFromFile();
        }

        @Override
        /** SubscriberId */
        public String subscriberId() {
            return subscriberId;
        }

        @Override
        /** Value */
        public long value() {
            return value;
        }

        @Override
        /** IncrementAnd获取 */
        public synchronized long incrementAndGet() {
            value++;
            flush();
            return value;
        }

        @Override
        /** 重置 */
        public synchronized void reset(long newValue) {
            value = newValue;
            flush();
        }

        @Override
        /** OffsetPath */
        public String offsetPath() {
            return offsetPath.toString();
        }

        @Override
        /** 刷新 */
        public void flush() {
            if (!config.isPersistent() || closed) {
                return;
            }
            try {
                Path parent = offsetPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                ByteBuffer buffer = ByteBuffer.allocate(OFFSET_BYTE_LENGTH);
                buffer.putLong(value);
                buffer.flip();
                try (FileChannel channel = FileChannel.open(
                        offsetPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(buffer);
                    channel.force(true);
                }
            } catch (IOException e) {
                log.warn("刷新 offset 失败: subscriberId={}, value={}", subscriberId, value, e);
            }
        }

        @Override
        /** 关闭 */
        public void close() {
            closed = true;
            flush();
        }

        /** 加载FromFile */
        private long loadFromFile() {
            if (!config.isPersistent()) {
                return 0L;
            }
            Path path = offsetPath;
            if (!Files.exists(path)) {
                return 0L;
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(OFFSET_BYTE_LENGTH);
                int read = channel.read(buffer);
                if (read == OFFSET_BYTE_LENGTH) {
                    buffer.flip();
                    return buffer.getLong();
                }
            } catch (IOException e) {
                log.warn("加载 offset 文件失败: {}", path, e);
            }
            return 0L;
        }
    }
}
