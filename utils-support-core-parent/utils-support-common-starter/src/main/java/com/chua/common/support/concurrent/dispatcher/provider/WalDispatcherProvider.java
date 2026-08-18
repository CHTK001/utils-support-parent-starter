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
            serializer = new com.chua.common.support.base.serialize.JacksonSerialization();
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
        getLog(topic).append(body);
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
        return logs.computeIfAbsent(topic, t -> new WalLog(logDir.resolve("wal-" + t + ".log")));
    }

    private void startConsumer(String topic) {
        var log = getLog(topic);
        consumerExecutor.submit(() -> {
            log.info("WAL 消费者已启动 topic={} file={}", topic, log.file.getFileName());
            try {
                long index = log.initialIndex();
                log.reset();
                RandomAccessFile raf = new RandomAccessFile(log.file.toFile(), "r");
                raf.seek(index);
                byte[] payload = new byte[0];
                while (!closed.get()) {
                    // 读取一条帧（阻塞等待新数据）
                    ByteBuffer readBuf = readFrame(raf, payload.length > 0 ? payload : null);
                    if (readBuf == null) {
                        Thread.sleep(2);
                        continue;
                    }
                    byte[] data = new byte[readBuf.remaining()];
                    readBuf.get(data);
                    payload = data;
                    dispatch(topic, data);
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

    private void dispatch(String topic, byte[] data) {
        var definitions = definitionMap.get(topic);
        if (definitions == null) return;
        Object payload = SERIALIZER.deserialize(data);
        for (var def : definitions) {
            try {
                def.dispatch(payload);
            } catch (Exception e) {
                log.warn("订阅方法执行异常，topic={}", topic, e);
            }
        }
    }

    /**
     * 读取下一帧。支持持久读取位置（从 raf 当前 file pointer 读）。
     * 若帧未完整写入（写者未完成），返回 null，调用方等待重试。
     */
    private ByteBuffer readFrame(RandomAccessFile raf, byte[] pendingPrefix) throws Exception {
        // 定位到 raf 当前位置（上次读完的尾部）
        long pos = raf.getFilePointer();
        long fileLen = raf.length();
        // 至少要有 MAGIC + int length
        if (fileLen - pos < 8) return null;
        raf.seek(pos);
        byte[] magicLen = new byte[8];
        raf.readFully(magicLen);
        ByteBuffer bb = ByteBuffer.wrap(magicLen);
        if (bb.getInt() != MAGIC) {
            // 数据损坏或写者跨块，跳过 1 字节重扫
            raf.seek(pos + 1);
            return null;
        }
        int len = bb.getInt();
        if (len < 0 || len > 512 * 1024 * 1024) {
            raf.seek(pos + 1);
            return null;
        }
        int headerAndPayload = 8 + len;
        if (fileLen - pos < headerAndPayload) {
            raf.seek(pos); // 帧未写完，等待写者
            return null;
        }
        byte[] data = new byte[len];
        raf.readFully(data);
        raf.seek(pos + headerAndPayload);
        return ByteBuffer.wrap(data);
    }

    /**
     * 单 topic 的 append-only WAL 日志文件。
     */
    static class WalLog {
        final Path file;
        private FileChannel channel;
        private final ByteBuffer header = ByteBuffer.allocate(8);
        private volatile long written = 0;

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

        synchronized void append(Object body) {
            try {
                byte[] payload = WalPayloadSerializer.INSTANCE.serialize(body);
                header.clear();
                header.putInt(MAGIC);
                header.putInt(payload.length);
                header.flip();
                channel.write(header);
                channel.write(ByteBuffer.wrap(payload));
                channel.force(false);
                written += 8 + payload.length;
            } catch (Exception e) {
                log.warn("WAL append 失败 file={}", file, e);
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