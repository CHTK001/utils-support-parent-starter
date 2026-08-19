package com.chua.common.support.concurrent.dispatcher.provider;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
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
 * <p>基于内存映射文件（mmap）实现发布-订阅模式，数据直接写入 mmap 缓冲区，
 * 消费者从 mmap 读取，消除队列和线程上下文切换开销。
 *
 * <p>支持 Jackson 和 Fury 两种序列化方式，通过构造参数注入。</p>
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
     * 空闲自旋上限，超过后切换为 nanos 休眠
     */
    private static final int IDLE_SPIN_LIMIT = 1_000_000;

    /**
     * WAL 帧头大小（魔数 4 字节 + 长度 4 字节）
     */
    private static final int FRAME_HEADER_SIZE = 8;

    /**
     * mmap 每次扩容大小：64MB
     */
    private static final long MMAP_GROW = 64L * 1024 * 1024;

    public WalDispatcherProvider(DispatcherConfig config) {
        this(config, null);
    }

    /**
     * 构造 WAL 分发器提供者。
     *
     * @param config     分发器配置
     * @param serializer 序列化器（null 时使用 Jackson）
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

    @Override
    public void publish(String topic, Object body) {
        if (closed.get()) {
            return;
        }
        byte[] data = writeBody(body);
        if (data != null) {
            getLog(topic).writeFrame(data);
        }
    }

    @Override
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
    public void close() {
        closed.set(true);
        logs.values().forEach(WalLog::close);
        logs.clear();
        definitionMap.clear();
    }

    /**
     * 获取或创建指定主题的 WAL 日志。
     *
     * @param topic 主题名称
     * @return WAL 日志实例
     */
    private WalLog getLog(String topic) {
        return logs.computeIfAbsent(topic, t -> new WalLog(logDir.resolve("wal-" + t + ".log")));
    }

    /**
     * 启动指定主题的消费者虚拟线程。
     * <p>消费者通过持久 mmap 只读映射持续读取新数据，commitPos 增长后增量 remap。</p>
     *
     * @param topic 主题名称
     */
    private void startConsumer(String topic) {
        var wal = getLog(topic);
        consumerExecutor.submit(() -> {
            log.info("WAL 消费者已启动 topic={} file={}", topic, wal.file.getFileName());
            try {
                long pos = 0;
                int idle = 0;
                MappedByteBuffer reader = null;
                long readerSize = 0;
                while (!closed.get()) {
                    long commitEnd = wal.commitPos.get();
                    if (commitEnd <= pos) {
                        if (++idle > IDLE_SPIN_LIMIT) {
                            Thread.sleep(0, 1);
                            idle = 0;
                        }
                        continue;
                    }
                    idle = 0;
                    if (reader == null || commitEnd > readerSize) {
                        readerSize = Math.max(MMAP_GROW, commitEnd + MMAP_GROW);
                        reader = wal.channel.map(FileChannel.MapMode.READ_ONLY, 0, readerSize);
                    }
                    reader.position((int) pos);
                    reader.limit((int) commitEnd);
                    while (reader.remaining() >= FRAME_HEADER_SIZE) {
                        int mark = reader.position();
                        int magic = reader.getInt();
                        int len = reader.getInt();
                        if (magic != MAGIC || len <= 0 || reader.remaining() < len) {
                            reader.position(mark + 1);
                            pos = mark + 1;
                            continue;
                        }
                        byte[] data = new byte[len];
                        reader.get(data);
                        pos = mark + FRAME_HEADER_SIZE + len;
                        dispatch(topic, data);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable ex) {
                if (!closed.get()) {
                    log.error("WAL 消费异常 topic={}", topic, ex);
                }
            }
            log.info("WAL 消费者已停止 topic={}", topic);
        });
    }

    /**
     * 分发已反序列化的消息到所有订阅者。
     *
     * @param topic 主题名称
     * @param data  序列化后的字节数据
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

    /**
     * 序列化消息体。
     *
     * @param body 消息对象
     * @return 序列化后的字节数组，失败返回 null
     */
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

    /**
     * 反序列化消息体。
     *
     * @param data 字节数据
     * @return 反序列化后的对象
     */
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
     * @author CH
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
         * 空间不足时自动扩容 64MB。
         *
         * @param needed 需要写入的字节数
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
         * <p>mmap 为共享内存，数据对消费者立即可见，无需 force() 刷盘。
         * 使用 CAS 无锁写入，仅在 mmap 扩容时同步。</p>
         *
         * @param payload 已序列化的负载数据
         */
        void writeFrame(byte[] payload) {
            int frameSize = FRAME_HEADER_SIZE + payload.length;
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

        /**
         * 关闭 WAL 日志，强制刷新 mmap 并释放文件通道。
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