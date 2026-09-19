package com.chua.kafka.support.wal;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.wal.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Kafka 实现的无限制 WAL。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("kafka")
public class KafkaWalLog implements WalLog {

    /** Checkpoint_文件 */
    private static final String CHECKPOINT_FILE = "checkpoint.dat";
    /** 配置 */
    private final WalConfig config;
    /** Topic */
    private final String topic;
    /** producer */
    private final KafkaProducer<String, byte[]> producer;
    /** consumer */
    private final KafkaConsumer<String, byte[]> consumer;
    /** 当前LSN */
    private final AtomicLong currentLsn = new AtomicLong(0);
    /** checkpointlsn */
    private final AtomicLong checkpointLsn = new AtomicLong(0);
    /** 锁 */
    private final ReentrantLock lock = new ReentrantLock();
    /** closed */
    private volatile boolean closed;

    /**
     * 创建 kafkawal日志 实例
     * @param config 配置
     */
    public KafkaWalLog(WalConfig config) {
        this.config = config;
        this.topic = config.namespace();

        Properties prodProps = new Properties();
        prodProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        prodProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        prodProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        this.producer = new KafkaProducer<>(prodProps);

        Properties consProps = new Properties();
        consProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        consProps.put(ConsumerConfig.GROUP_ID_CONFIG, "wal-" + topic);
        consProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        this.consumer = new KafkaConsumer<>(consProps);
        consumer.subscribe(Collections.singletonList(topic));

        loadCheckpointFromFile();
        scanMaxLsn();
    }

    /** 扫描最大值Lsn */
    private void scanMaxLsn() {
        consumer.poll(Duration.ofMillis(100));
        consumer.seekToBeginning(consumer.assignment());
        long max = 0;
        while (true) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(200));
            if (records.isEmpty()) {
                break;
            }
            for (ConsumerRecord<String, byte[]> r : records) {
                long lsn = extractLsn(r);
                if (lsn > max) {
                    max = lsn;
                }
            }
        }
        consumer.seekToBeginning(consumer.assignment());
        currentLsn.set(max);
    }

    /**
     * extractlsn
     *
     * @param record record
     * @return extractLsn的结果
     */
    private long extractLsn(ConsumerRecord<String, byte[]> record) {
        try {
            String key = record.key();
            return key == null ? 0 : Long.parseLong(key);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * checkpoint路径
     *
     * @return checkpoint路径的结果
     */
    private Path checkpointPath() {
        Path dir = config.walDir() != null ? config.walDir() : Path.of(System.getProperty("java.io.tmpdir"), "wal");
        return dir.resolve(topic).resolve(CHECKPOINT_FILE);
    }

    /** 加载checkpoint从文件 */
    private void loadCheckpointFromFile() {
        try {
            Path p = checkpointPath();
            if (Files.exists(p)) {
                byte[] data = Files.readAllBytes(p);
                if (data.length == 8) {
                    checkpointLsn.set(ByteBuffer.wrap(data).getLong());
                }
            }
        } catch (IOException ignored) {}
    }

    /** 保存Checkpoint */
    private void saveCheckpoint() {
        try {
            Path p = checkpointPath();
            Files.createDirectories(p.getParent());
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(checkpointLsn.get());
            Files.write(p, buf.array(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {}
    }

    @Override
    /** 追加 */
    public long append(byte op, byte[] payload) throws IOException {
        ensureOpen();
        lock.lock();
        try {
            long lsn = currentLsn.incrementAndGet();
            byte[] value = new byte[payload.length + 1];
            value[0] = op;
            System.arraycopy(payload, 0, value, 1, payload.length);
            producer.send(new ProducerRecord<>(topic, String.valueOf(lsn), value)).get();
            return lsn;
        } catch (Exception e) {
            throw new IOException("Kafka append failed", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    /** 同步 */
    public void sync() throws IOException {
        ensureOpen();
        producer.flush();
    }

    @Override
    /** 当前lsn */
    public long currentLsn() {
        return currentLsn.get();
    }

    @Override
    /** 加载Checkpoint */
    public CheckpointMeta loadCheckpoint() throws IOException {
        ensureOpen();
        return new CheckpointMeta(checkpointLsn.get(), 1, 0, System.currentTimeMillis());
    }

    @Override
    /** 标记Checkpoint */
    public void markCheckpoint(long lsn) throws IOException {
        ensureOpen();
        lock.lock();
        try {
            checkpointLsn.set(Math.min(lsn, currentLsn.get()));
            saveCheckpoint();
        } finally {
            lock.unlock();
        }
    }

    @Override
    /** 重置Checkpoint */
    public void resetCheckpoint() throws IOException {
        ensureOpen();
        lock.lock();
        try {
            checkpointLsn.set(0);
            saveCheckpoint();
        } finally {
            lock.unlock();
        }
    }

    @Override
    /** forcecheckpoint */
    public void forceCheckpoint(long lsn) throws IOException {
        ensureOpen();
        lock.lock();
        try {
            checkpointLsn.set(Math.max(0, lsn));
            saveCheckpoint();
        } finally {
            lock.unlock();
        }
    }

    @Override
    /** Replay */
    public WalReplayResult replay(WalReplayHandler handler) throws IOException {
        return replay(checkpointLsn.get() + 1, Long.MAX_VALUE, handler);
    }

    @Override
    /** Replay */
    public WalReplayResult replay(long fromLsn, WalReplayHandler handler) throws IOException {
        return replay(fromLsn, Long.MAX_VALUE, handler);
    }

    @Override
    /** Replay */
    public WalReplayResult replay(long fromLsn, long toLsn, WalReplayHandler handler) throws IOException {
        ensureOpen();
        lock.lock();
        try {
            consumer.seekToBeginning(consumer.assignment());
            List<WalRecord> records = new ArrayList<>();
            boolean stopped = false;
            while (!stopped) {
                ConsumerRecords<String, byte[]> recordsBatch = consumer.poll(Duration.ofMillis(200));
                if (recordsBatch.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, byte[]> r : recordsBatch) {
                    long lsn = extractLsn(r);
                    if (lsn < fromLsn || lsn >= toLsn) {
                        continue;
                    }
                    byte[] value = r.value();
                    if (value == null || value.length == 0) {
                        continue;
                    }
                    byte op = value[0];
                    byte[] payload = new byte[value.length - 1];
                    System.arraycopy(value, 1, payload, 0, payload.length);
                    WalRecord record = new WalRecord(lsn, op, payload);
                    records.add(record);
                    if (handler != null) {
                        try {
                            if (!handler.onRecord(lsn, op, payload)) {
                                stopped = true;
                                break;
                            }
                        } catch (WalException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new WalException("WAL 回放处理器异常: lsn=" + lsn, e);
                        }
                    }
                }
            }
            return new WalReplayResult(new CheckpointMeta(checkpointLsn.get(), 1, 0, System.currentTimeMillis()), records);
        } finally {
            lock.unlock();
        }
    }

    @Override
    /** 追加Chain */
    public long appendChain(WalChainHandler handler) throws IOException {
        ensureOpen();
        List<WalOp> ops = new ArrayList<>();
        try {
            handler.apply(new WalChain() {
                @Override
                /** 添加 */
                public WalChain add(byte op, byte[] payload) {
                    ops.add(new WalOp(op, payload == null ? new byte[0] : payload));
                    return this;
                }
                @Override
                /** 添加 */
                public WalChain add(byte op, String s) {
                    byte[] bytes = s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ops.add(new WalOp(op, bytes));
                    return this;
                }
                @Override
                /** 添加 */
                public WalChain add(byte op) {
                    ops.add(new WalOp(op, new byte[0]));
                    return this;
                }
                @Override
                /** 获取大小 */
                public int size() {
                    return ops.size();
                }
            });
        } catch (WalException e) {
            throw e;
        } catch (Exception e) {
            throw new WalException("WAL 链式写入处理器异常", e);
        }
        long last = currentLsn.get();
        for (WalOp op : ops) {
            last = append(op.op(), op.payload());
        }
        return last;
    }

    @Override
    /** 查找bylsn */
    public Optional<WalRecord> findByLsn(long lsn) throws IOException {
        ensureOpen();
        lock.lock();
        try {
            consumer.seekToBeginning(consumer.assignment());
            while (true) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, byte[]> r : records) {
                    long cur = extractLsn(r);
                    if (cur == lsn) {
                        byte[] value = r.value();
                        if (value != null && value.length > 0) {
                            byte op = value[0];
                            byte[] payload = new byte[value.length - 1];
                            System.arraycopy(value, 1, payload, 0, payload.length);
                            return Optional.of(new WalRecord(cur, op, payload));
                        }
                    }
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    /** purgecheckpointed */
    public int purgeCheckpointed(int keepSegments) throws IOException {
        return 0;
    }

    @Override
    /** 当前segment */
    public WalSegmentInfo currentSegment() {
        return new WalSegmentInfo(1, checkpointLsn.get() + 1, currentLsn.get(),
                (int) Math.max(0, currentLsn.get() - checkpointLsn.get()),
                checkpointPath().getParent(), true);
    }

    @Override
    /** 列表segments */
    public List<WalSegmentInfo> listSegments() {
        return Collections.singletonList(currentSegment());
    }

    @Override
    /** 关闭 */
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            producer.close();
            consumer.close();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Ensure打开 */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WAL 已关闭");
        }
    }
}
