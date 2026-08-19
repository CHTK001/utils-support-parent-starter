package com.chua.common.support.concurrent.dispatcher.provider;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.concurrent.queue.LockFreeQueue;
import com.chua.common.support.concurrent.queue.LockFreeQueueFlow;
import com.chua.common.support.concurrent.queue.QueueType;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WAL（Write-Ahead Log）分发器提供者。
 *
 * <p>双支杆架构：
 * <ul>
 *   <li><b>主支杆</b>：无锁队列（MpmcArrayQueue），数据直达消费者，零系统调用</li>
 *   <li><b>副支杆</b>：WAL + mmap 持久化，用于崩溃恢复</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WalDispatcherProvider extends AbstractDispatcherProvider implements DispatcherProvider {

    /**
     * WAL 帧头魔数：WAL1
     */
    private static final int MAGIC = 0x57414C31;

    /**
     * 序列化器实例
     */
    private volatile com.chua.common.support.base.serialize.Serialization serializer;

    /**
     * 主题 -> WAL 日志文件映射
     */
    private final Map<String, WalLog> logs = new ConcurrentHashMap<>();

    /**
     * 主题 -> 订阅者列表映射
     */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
     * 主题 -> 无锁快速队列映射（主支杆）
     */
    private final Map<String, LockFreeQueue<byte[]>> fastQueues = new ConcurrentHashMap<>();

    /**
     * 消费者线程池（虚拟线程）
     */
    private final ExecutorService consumerExecutor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("wal-consumer-", 0).factory());

    /**
     * WAL 日志文件存储目录
     */
    private final Path logDir;

    /**
     * 关闭标志
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 快速队列类型（SPSC 单生产者单消费者，吞吐最高）
     */
    private static final QueueType FAST_QUEUE_TYPE = QueueType.SPSC;

    /**
     * 快速队列容量（2 的幂）
     */
    private static final int FAST_QUEUE_CAPACITY = 65536;

    /**
     * WAL 帧头大小（魔数 4 字节 + 长度 4 字节）
     */
    private static final int FRAME_HEADER_SIZE = 8;

    /**
     * mmap 每次扩容大小：64MB
     */
    private static final long MMAP_GROW = 64L * 1024 * 1024;

    /**
     * 创建 WalDispatcherProvider 实例
     * @param config config
     */
    public WalDispatcherProvider(DispatcherConfig config) {
        this(config, (String) null);
    }

    /**
     * 构造 WAL 分发器提供者，按 SPI 名称加载序列化器。
     *
     * @param config        分发器配置
     * @param serializerName 序列化器 SPI 名称（如 {@code fury}/{@code fory}/{@code jackson}），
     *                       为空时回退使用 config 中的序列化名称，再为空使用默认 Jackson
     */
    public WalDispatcherProvider(DispatcherConfig config, String serializerName) {
        this(config, resolveSerializer(
                serializerName != null && !serializerName.isBlank()
                        ? serializerName
                        : config.getSerializer()));
    }

    /**
     * 构造 WAL 分发器提供者，序列化器 SPI 名称统一从 {@link DispatcherConfig#getSerializer()} 读取。
     *
     * @param config 分发器配置（可含 serializer 序列化名称）
     */
    public WalDispatcherProvider(DispatcherConfig config,
                                 com.chua.common.support.base.serialize.Serialization serializer) {
        super(config);
        if (serializer == null) {
            serializer = new JacksonSerialization();
        }
        this.serializer = serializer;
        String dir = config.getDataPath() != null
                ? config.getDataPath()
                : System.getProperty("java.io.tmpdir") + "/wal-datasync";
        this.logDir = Paths.get(dir);
        try {
            Files.createDirectories(logDir);
        } catch (Exception e) {
            throw new RuntimeException("WAL 日志目录创建失败: " + logDir, e);
        }
    }

    /**
     * 按 SPI 名称解析序列化器。
     *
     * @param name 序列化器 SPI 名称（{@code fury}/{@code fory}/{@code jackson} 等），
     *             为空时使用 Jackson 默认实现
     * @return 解析出的序列化器，Spi 加载失败时回退 Jackson
     */
    private static com.chua.common.support.base.serialize.Serialization resolveSerializer(String name) {
        if (name == null || name.isBlank()) {
            return new JacksonSerialization();
        }
        try {
            com.chua.common.support.base.serialize.Serialization serialization =
                    com.chua.common.support.spi.ServiceProvider.of(com.chua.common.support.base.serialize.Serialization.class)
                            .getNewExtension(name);
            if (serialization != null) {
                log.info("WAL 序列化器已加载: {} -> {}", name, serialization.getClass().getName());
                return serialization;
            }
        } catch (Exception e) {
            log.warn("WAL 序列化器 SPI 加载失败: {}，回退 Jackson", name, e);
        }
        return new JacksonSerialization();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object body) {
        if (closed.get()) {
            return;
        }
        byte[] data = writeBody(body);
        if (data == null) {
            return;
        }
        // 副支杆：写入 WAL（持久化）
        getLog(topic).writeFrame(data);
        // 主支杆：写入无锁队列（快速消费）
        fastQueue(topic).offer(data);
    }

    /**
     * 获取或创建主题的无锁快速队列。
     */
    private LockFreeQueue<byte[]> fastQueue(String topic) {
        return fastQueues.computeIfAbsent(topic, t -> LockFreeQueueFlow.create(FAST_QUEUE_TYPE, FAST_QUEUE_CAPACITY));
    }

    @Override
    /** 订阅 */
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var isFirst = definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).isEmpty();
            definitionMap.get(topic).add(definition);
            if (isFirst && !closed.get()) {
                startConsumer(topic);
            }
        }
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var list = definitionMap.get(topic);
            if (list != null) {
                list.remove(definition);
                if (list.isEmpty()) {
                    definitionMap.remove(topic);
                }
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        closed.set(true);
        logs.values().forEach(WalLog::close);
        logs.clear();
        definitionMap.clear();
        fastQueues.clear();
    }

    /**
     * 获取或创建指定主题的 WAL 日志。
     */
    private WalLog getLog(String topic) {
        return logs.computeIfAbsent(topic, t -> new WalLog(logDir.resolve("wal-" + t + ".log")));
    }

    /**
     * 启动消费者虚拟线程，从无锁快速队列读取并分发。
     */
    private void startConsumer(String topic) {
        var queue = fastQueue(topic);
        consumerExecutor.submit(() -> {
            log.info("WAL 消费者已启动 topic={}", topic);
            try {
                while (!closed.get()) {
                    byte[] data = queue.poll();
                    if (data == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    dispatch(topic, data);
                }
            } catch (Exception ex) {
                if (!closed.get()) {
                    log.error("WAL 消费异常 topic={}", topic, ex);
                }
            }
            log.info("WAL 消费者已停止 topic={}", topic);
        });
    }

    /**
     * 分发已反序列化的消息到所有订阅者。
     */
    private void dispatch(String topic, byte[] data) {
        var definitions = definitionMap.get(topic);
        if (definitions == null) {
            return;
        }
        Object payload = readBody(data);
        for (var def : definitions) {
            try {
                def.dispatch(payload);
            } catch (Exception e) {
                log.warn("订阅方法执行异常，topic={}", topic, e);
            }
        }
    }

    /** 写入Body */
    private byte[] writeBody(Object body) {
        try {
            if (serializer == null) {
                return JacksonSerialization.INSTANCE.serialize(body);
            }
            synchronized (serializer) {
                return serializer.serialize(body);
            }
        } catch (Exception e) {
            log.warn("WAL 序列化失败", e);
            return null;
        }
    }

    /** 读取Body */
    private Object readBody(byte[] data) {
        try {
            if (serializer == null) {
                return JacksonSerialization.INSTANCE.deserialize(data, Object.class);
            }
            synchronized (serializer) {
                return serializer.deserialize(data, Object.class);
            }
        } catch (Exception e) {
            log.warn("WAL 反序列化失败，回退 Jackson", e);
            try {
                return JacksonSerialization.INSTANCE.deserialize(data, Object.class);
            } catch (Exception ex) {
                throw new RuntimeException("WAL 反序列化失败", ex);
            }
        }
    }

    /**
     * WAL 日志文件，封装 mmap 写入和提交位置追踪。
     *
     * @since 4.0.0.42
     */
    static class WalLog {

        /**
         * WAL 日志文件路径
         */
        final Path file;

        /**
         * 文件通道
         */
        private FileChannel channel;

        /**
         * mmap 读写缓冲区
         */
        private MappedByteBuffer mappedBuf;

        /**
         * 当前 mmap 映射大小
         */
        private long mappedSize = 0;

        /**
         * 是否启用 mmap
         */
        private volatile boolean useMmap = true;

        /**
         * 日志记录器
         */
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WalLog.class);

        /**
         * 写入端已确认的提交位置，消费者仅读到该位置
         */
        final AtomicLong commitPos = new AtomicLong(0);

        /**
         * 写入锁，保证多线程并发 publish 时 mmap 写入原子性
         */
        private final Object writeLock = new Object();

        WalLog(Path file) {
            this.file = file;
            try {
                if (!Files.exists(file)) {
                    Files.createFile(file);
                }
                channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.READ);
                tryMmap();
            } catch (Exception e) {
                throw new RuntimeException("WAL 文件打开失败: " + file, e);
            }
        }

        /**
         * 尝试初始化 mmap 映射。
         */
        private void tryMmap() {
            try {
                long size = MMAP_GROW;
                mappedBuf = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
                mappedSize = size;
                LOG.info("WAL mmap 已启用 file={}", file.getFileName());
            } catch (Throwable t) {
                LOG.warn("WAL mmap 不可用，降级 I/O cause={}", t.getMessage());
                useMmap = false;
                mappedBuf = null;
            }
        }

        /**
         * 确保 mmap 缓冲区有足够空间写入指定大小的数据。
         */
        private void ensureMmap(int needed) {
            if (!useMmap || mappedBuf == null) {
                return;
            }
            int pos = mappedBuf.position();
            if (pos + needed <= mappedSize) {
                return;
            }
            try {
                long newSize = mappedSize + Math.max(MMAP_GROW, needed);
                mappedBuf = channel.map(FileChannel.MapMode.READ_WRITE, 0, newSize);
                mappedBuf.position(pos);
                mappedSize = newSize;
            } catch (Throwable t) {
                LOG.warn("WAL mmap 扩容失败，降级 I/O cause={}", t.getMessage());
                useMmap = false;
                mappedBuf = null;
            }
        }

        /**
         * 写入一帧数据到 mmap。
         * <p>帧格式：魔数(4B) + 负载长度(4B) + 负载数据。</p>
         */
        void writeFrame(byte[] payload) {
            int frameSize = FRAME_HEADER_SIZE + payload.length;
            if (useMmap && mappedBuf != null) {
                synchronized (writeLock) {
                    if (useMmap && mappedBuf != null) {
                        ensureMmap(frameSize);
                        if (useMmap) {
                            int pos = mappedBuf.position();
                            int endPos = pos + frameSize;
                            mappedBuf.putInt(pos, MAGIC);
                            mappedBuf.putInt(pos + 4, payload.length);
                            mappedBuf.position(pos + FRAME_HEADER_SIZE);
                            mappedBuf.put(payload);
                            mappedBuf.position(endPos);
                            commitPos.set(endPos);
                            return;
                        }
                    }
                }
            }
            synchronized (writeLock) {
                try {
                    long pos = channel.position();
                    ByteBuffer hdr = ByteBuffer.allocate(FRAME_HEADER_SIZE);
                    hdr.putInt(MAGIC).putInt(payload.length).flip();
                    channel.write(hdr);
                    channel.write(ByteBuffer.wrap(payload));
                    commitPos.set(pos + FRAME_HEADER_SIZE + payload.length);
                } catch (Exception e) {
                    LOG.warn("WAL 写入失败 file={}", file, e);
                }
            }
        }

        /**
         * 关闭 WAL 日志，释放文件通道。
         */
        void close() {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}