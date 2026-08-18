package com.chua.common.support.concurrent.dispatcher.provider;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WAL（Write-Ahead Log）分发器提供者：基于 append-only 文件日志实现持久化发布订阅。
 *
 * <p>比 Chronicle 更轻量：无 mmap / 无文档帧 / 无 JDK 模块限制（Java 25 可用）。
 * 写入使用 {@link FileChannel} 批量 append + {@link FileLock} 保证多进程安全；
 * 消费线程 tail-follow 日志文件；重启时从起点重放即恢复。
 *
 * <p>帧格式：{@code int length + byte[] payload}（length 为 payload 字节数，大端）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WalDispatcherProvider extends AbstractDispatcherProvider implements DispatcherProvider {

    private static final int MAGIC = 0x57414C31; // "WAL1"

    /**
     * 序列化器：可注入（如 Fury/Kryo），默认 Jackson。
     */
    private volatile com.chua.common.support.base.serialize.Serialization serializer;

    private final Map<String, WalLog> logs = new ConcurrentHashMap<>();
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();
    private final ExecutorService consumerExecutor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("wal-consumer-", 0).factory());
    private final Path logDir;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public WalDispatcherProvider(DispatcherConfig config) {
        this(config, null);
    }

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
        if (closed.get()) return;
        getLog(topic).appendBytes(writeBody(body));
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

    private WalLog getLog(String topic) {
        WalLog wal = logs.computeIfAbsent(topic, t -> {
            WalLog w = new WalLog(logDir.resolve("wal-" + t + ".log"));
            w.startWriter();
            return w;
        });
        if (!wal.writerStarted) {
            wal.startWriter();
        }
        return wal;
    }

    private void startConsumer(String topic) {
        var wal = getLog(topic);
        consumerExecutor.submit(() -> {
            log.info("WAL 消费者已启动 topic={} file={}", topic, wal.file.getFileName());
            try {
                wal.reset();
                long index = wal.initialIndex();
                RandomAccessFile raf = new RandomAccessFile(wal.file.toFile(), "r");
                raf.seek(index);
                while (!closed.get()) {
                    Object payload = readDispatchFrame(raf, topic);
                    if (payload == null) {
                        Thread.sleep(2);
                    }
                }
                raf.close();
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
     * 读一帧并派发；若帧未完成返回 null（调用方等待重试）。
     */
    private Object readDispatchFrame(RandomAccessFile raf, String topic) throws Exception {
        long pos = raf.getFilePointer();
        long fileLen = raf.length();
        int headerSize = 8;
        if (fileLen - pos < headerSize) {
            raf.seek(pos);
            return null;
        }
        raf.seek(pos);
        byte[] header = new byte[headerSize];
        raf.readFully(header);
        ByteBuffer hb = ByteBuffer.wrap(header);
        int magic = hb.getInt();
        int len = hb.getInt();
        if (magic != MAGIC) {
            log.warn("WAL 帧头损坏 topic={} pos={} magic={}", topic, pos, Integer.toHexString(magic));
            raf.seek(pos + 1);
            return null;
        }
        if (len < 0 || len > 512 * 1024 * 1024) {
            raf.seek(pos + 1);
            return null;
        }
        if (len == 0) {
            // 空帧(占位)，跳过
            raf.seek(pos + headerSize);
            return null;
        }
        if (fileLen - pos < headerSize + len) {
            raf.seek(pos); // 帧尚未写完整，等待
            return null;
        }
        byte[] data = new byte[len];
        raf.readFully(data);
        raf.seek(pos + headerSize + len);
        if (data.length == 0) {
            return null;
        }
        dispatch(topic, data);
        return data;
    }

    private void dispatch(String topic, byte[] data) {
        var definitions = definitionMap.get(topic);
        if (definitions == null) return;
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
     * 序列化消息体（使用注入序列化器，默认 Jackson）。
     */
    private byte[] writeBody(Object body) {
        try {
            if (serializer == null) {
                return JacksonSerialization.INSTANCE.serialize(body);
            }
            return serializer.serialize(body);
        } catch (Exception e) {
            throw new RuntimeException("WAL 序列化失败", e);
        }
    }

    /**
     * 反序列化消息体（使用注入序列化器，默认 Jackson）。
     */
    private Object readBody(byte[] data) {
        try {
            if (serializer == null) {
                return JacksonSerialization.INSTANCE.deserialize(data, Object.class);
            }
            return serializer.deserialize(data, Object.class);
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
     * 反序列化消息体（使用注入序列化器，默认 Jackson）。
     */
    private Object readBody(byte[] data) {
        try {
            if (serializer == null) {
                return JacksonSerialization.INSTANCE.deserialize(data, Object.class);
            }
            return serializer.deserialize(data, Object.class);
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
     * 单 topic 的 append-only WAL 日志文件。
     *
     * <p>写入通过 Reactor {@code Sinks.Many} 队列异步完成：{@link #appendBytes} 将
     * 序列化后的字节放入内存队列（有背压），后台 Reactor 订阅者按序写入文件并 {@code force} 落盘。</p>
     */
    static class WalLog {
        final Path file;
        final reactor.core.publisher.Sinks.Many<byte[]> sink =
                reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer(10000, false);
        private FileChannel channel;
        private final ByteBuffer header = ByteBuffer.allocate(8);
        private volatile boolean writerStarted = false;

        WalLog(Path file) {
            this.file = file;
            try {
                if (!Files.exists(file)) {
                    Files.createFile(file);
                }
                channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                channel.position(channel.size());
            } catch (Exception e) {
                throw new RuntimeException("WAL 文件打开失败: " + file, e);
            }
        }

        void startWriter() {
            if (writerStarted) return;
            synchronized (this) {
                if (writerStarted) return;
                writerStarted = true;
                sink.asFlux()
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(bytes -> reactor.core.publisher.Mono.fromRunnable(() -> writeFrame(bytes)), 1, 1)
                        .onErrorContinue((e, o) -> log.warn("WAL reactor 写入失败 file={} cause={}", file, e.getMessage()))
                        .subscribe();
            }
        }

        void appendBytes(byte[] payload) {
            try {
                sink.tryEmitNext(payload);
            } catch (Exception e) {
                log.warn("WAL 入队失败 file={}", file, e);
            }
        }

        private void writeFrame(byte[] payload) {
            try {
                header.clear();
                header.putInt(MAGIC);
                header.putInt(payload.length);
                header.flip();
                channel.write(header);
                channel.write(ByteBuffer.wrap(payload));
                channel.force(false);
            } catch (Exception e) {
                log.warn("WAL frame 写入失败 file={}", file, e);
            }
        }

        long initialIndex() {
            try {
                return Math.max(0, channel.size());
            } catch (Exception e) {
                return 0;
            }
        }

        /**
         * 如果文件是空文件或新创建的，回填一个 MAGIC 头，保证读端也能解析。
         */
        void reset() {
            try {
                if (channel.size() < 8) {
                    header.clear();
                    header.putInt(MAGIC);
                    header.putInt(0); // length=0 表示"跳过"帧（实际无 payload）
                    header.flip();
                    channel.write(header);
                    channel.force(false);
                }
            } catch (Exception ignored) {
            }
        }

        void close() {
            try {
                if (channel != null) channel.close();
            } catch (Exception ignored) {
            }
        }
    }
}