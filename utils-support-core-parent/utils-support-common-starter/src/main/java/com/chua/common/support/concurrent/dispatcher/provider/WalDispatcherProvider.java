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
import java.nio.MappedByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class WalDispatcherProvider extends AbstractDispatcherProvider implements DispatcherProvider {

    private static final int MAGIC = 0x57414C31;

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
                    Object payload = readDispatchFrame(raf, topic, wal);
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

private Object readDispatchFrame(RandomAccessFile raf, String topic, WalLog wal) throws Exception {
        long pos = raf.getFilePointer();
        long commitEnd = wal.commitPos.get();
        int headerSize = 8;
        if (commitEnd - pos < headerSize) {
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
            raf.seek(pos + 1);
            return null;
        }
        if (len < 0 || len > 512 * 1024 * 1024) {
            raf.seek(pos + 1);
            return null;
        }
        if (len == 0) {
            raf.seek(pos + headerSize);
            return null;
        }
        if (commitEnd - pos < headerSize + len) {
            raf.seek(pos);
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

    static class WalLog {
        final Path file;
        final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(10000);
        private FileChannel channel;
        private MappedByteBuffer mappedBuf;
        private long mappedSize = 0;
        private volatile boolean useMmap = true;
        private static final long MMAP_GROW = 64L * 1024 * 1024;
        private final ByteBuffer header = ByteBuffer.allocate(8);
        private volatile boolean writerStarted = false;
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WalLog.class);
        final AtomicLong commitPos = new AtomicLong(0);

        WalLog(Path file) {
            this.file = file;
            try {
                if (!Files.exists(file)) Files.createFile(file);
                channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.READ);
                channel.position(channel.size());
                tryMmap();
            } catch (Exception e) {
                throw new RuntimeException("WAL 文件打开失败: " + file, e);
            }
        }

        private void tryMmap() {
            try {
                long pos = channel.size();
                long size = Math.max(MMAP_GROW, pos + MMAP_GROW);
                mappedBuf = channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, size);
                mappedBuf.position((int) pos);
                mappedSize = size;
                LOG.info("WAL mmap 已启用 file={}", file.getFileName());
            } catch (Throwable t) {
                LOG.warn("WAL mmap 不可用，降级 FileChannel I/O cause={}", t.getMessage());
                useMmap = false;
                mappedBuf = null;
            }
        }

        private void ensureMmap(long needed) {
            if (!useMmap || mappedBuf == null) return;
            long pos = (long) mappedBuf.position();
            if (pos + needed <= mappedSize) return;
            try {
                long newSize = mappedSize + Math.max(MMAP_GROW, needed);
                mappedBuf.force();
                mappedBuf = channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, newSize);
                mappedBuf.position((int) pos);
                mappedSize = newSize;
            } catch (Throwable t) {
                LOG.warn("WAL mmap 扩容失败，降级 FileChannel I/O cause={}", t.getMessage());
                useMmap = false;
                mappedBuf = null;
            }
        }

        void startWriter() {
            if (writerStarted) return;
            synchronized (this) {
                if (writerStarted) return;
                writerStarted = true;
                Thread.ofVirtual().name("wal-writer-" + file.getFileName()).start(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            byte[] payload = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (payload != null) writeFrame(payload);
                        } catch (InterruptedException e) { break; }
                    }
                });
            }
        }

        void appendBytes(byte[] payload) {
            if (!queue.offer(payload)) {
                LOG.warn("WAL 写入队列已满，丢弃消息 file={}", file);
            }
        }

private void writeFrame(byte[] payload) {
            try {
                int frameSize = 8 + payload.length;
                if (useMmap && mappedBuf != null) {
                    ensureMmap(frameSize);
                    if (useMmap) {
                        int pos = mappedBuf.position();
                        mappedBuf.putInt(MAGIC);
                        mappedBuf.putInt(payload.length);
                        mappedBuf.put(payload);
                        mappedBuf.force();
                        commitPos.set(pos + frameSize);
                        return;
                    }
                }
                long pos = channel.position();
                header.clear(); header.putInt(MAGIC); header.putInt(payload.length); header.flip();
                channel.write(header);
                channel.write(ByteBuffer.wrap(payload));
                channel.force(false);
                commitPos.set(pos + frameSize);
            } catch (Exception e) {
                LOG.warn("WAL frame 写入失败 file={}", file, e);
            }
        }

long initialIndex() {
            return 0;
        }

        void reset() {
            try {
                if (channel.size() < 8) {
                    header.clear();
                    header.putInt(MAGIC);
                    header.putInt(0); 
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






