package com.chua.chronicle.support.wal;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.wal.CheckpointMeta;
import com.chua.common.support.wal.WalChain;
import com.chua.common.support.wal.WalChainHandler;
import com.chua.common.support.wal.WalConfig;
import com.chua.common.support.wal.WalException;
import com.chua.common.support.wal.WalLog;
import com.chua.common.support.wal.WalOp;
import com.chua.common.support.wal.WalRecord;
import com.chua.common.support.wal.WalReplayHandler;
import com.chua.common.support.wal.WalReplayResult;
import com.chua.common.support.wal.WalSegmentInfo;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.ValueIn;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Spi("chronicle")
public class ChronicleWalLog implements WalLog {

    private static final String FIELD_LSN = "lsn";
    private static final String FIELD_OP = "op";
    private static final String FIELD_PAYLOAD = "payload";
    private static final String FIELD_CHECKPOINT = "checkpointLsn";

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;
    private final WalConfig config;
    private final AtomicLong currentLsn = new AtomicLong(0L);
    private final AtomicLong checkpointLsn = new AtomicLong(0L);
    private volatile boolean closed;

    public ChronicleWalLog(WalConfig config) {
        this.config = config;
        Path dir = resolveDir(config);
        try {
            File file = dir.toFile();
            if (!file.exists() && !file.mkdirs()) {
                throw new WalException("Cannot create Chronicle WAL directory: " + dir);
            }
            this.queue = SingleChronicleQueueBuilder.single(file).build();
            this.appender = queue.createAppender();
        } catch (WalException e) {
            throw e;
        } catch (Exception e) {
            throw new WalException("Chronicle WAL init failed: " + dir, e);
        }
        scanTail();
    }

    private Path resolveDir(WalConfig config) {
        Path walDir = config.walDir();
        if (walDir != null) {
            return walDir.resolve(config.namespace());
        }
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("wal").resolve(config.namespace());
    }

    private void scanTail() {
        long maxLsn = 0L;
        long cp = 0L;
        ExcerptTailer tailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = tailer.readingDocument(true)) {
                if (!dc.isPresent()) {
                    break;
                }
                if (!dc.isData()) {
                    continue;
                }
                ValueIn in = dc.wire().read(FIELD_LSN);
                if (in.isPresent()) {
                    long lsn = in.int64();
                    if (lsn > maxLsn) maxLsn = lsn;
                }
            }
        }
        // read checkpoint from a dedicated document (stored as data)
        ExcerptTailer cpTailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = cpTailer.readingDocument(true)) {
                if (!dc.isPresent()) break;
                if (!dc.isData()) continue;
                ValueIn in = dc.wire().read(FIELD_CHECKPOINT);
                if (in.isPresent()) {
                    cp = in.int64();
                    // the last checkpoint found wins
                }
            }
        }
        this.currentLsn.set(maxLsn);
        this.checkpointLsn.set(Math.max(0L, cp));
    }

    @Override
    public long append(byte op, byte[] payload) throws IOException {
        ensureOpen();
        long lsn = currentLsn.incrementAndGet();
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write(FIELD_LSN).int64(lsn);
            dc.wire().write(FIELD_OP).int8(op);
            dc.wire().write(FIELD_PAYLOAD).bytes(payload == null ? Bytes.empty() : Bytes.wrapForRead(payload));
        }
        return lsn;
    }

    @Override
    public void sync() throws IOException {
        ensureOpen();
    }

    @Override
    public long currentLsn() {
        return currentLsn.get();
    }

    @Override
    public CheckpointMeta loadCheckpoint() throws IOException {
        ensureOpen();
        return new CheckpointMeta(checkpointLsn.get(), 1, 0L, System.currentTimeMillis());
    }

    @Override
    public void markCheckpoint(long lsn) throws IOException {
        ensureOpen();
        long target = Math.min(lsn, currentLsn.get());
        target = Math.max(0L, target);
        this.checkpointLsn.set(target);
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write(FIELD_CHECKPOINT).int64(target);
            dc.wire().write(FIELD_OP).text("checkpoint");
        }
    }

    @Override
    public void resetCheckpoint() throws IOException {
        ensureOpen();
        this.checkpointLsn.set(0L);
    }

    @Override
    public void forceCheckpoint(long lsn) throws IOException {
        ensureOpen();
        long target = Math.max(0L, lsn);
        this.checkpointLsn.set(target);
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write(FIELD_CHECKPOINT).int64(target);
            dc.wire().write(FIELD_OP).text("checkpoint");
        }
    }

    @Override
    public WalReplayResult replay(WalReplayHandler handler) throws IOException {
        return replay(checkpointLsn.get() + 1, Long.MAX_VALUE, handler);
    }

    @Override
    public WalReplayResult replay(long fromLsn, WalReplayHandler handler) throws IOException {
        return replay(fromLsn, Long.MAX_VALUE, handler);
    }

    @Override
    public WalReplayResult replay(long fromLsn, long toLsn, WalReplayHandler handler) throws IOException {
        ensureOpen();
        List<WalRecord> records = new ArrayList<>();
        ExcerptTailer tailer = queue.createTailer();
        boolean stopped = false;
        while (true) {
            try (DocumentContext dc = tailer.readingDocument(true)) {
                if (!dc.isPresent()) break;
                if (!dc.isData()) continue;
                ValueIn lsnIn = dc.wire().read(FIELD_LSN);
                if (!lsnIn.isPresent()) continue;
                long lsn = lsnIn.int64();
                byte op = dc.wire().read(FIELD_OP).int8();
                byte[] payload = dc.wire().read(FIELD_PAYLOAD).bytes();
                if (payload == null) payload = new byte[0];
                if (lsn < fromLsn || lsn >= toLsn) {
                    continue;
                }
                WalRecord rec = new WalRecord(lsn, op, payload);
                records.add(rec);
                if (handler != null) {
                    try {
                        if (!handler.onRecord(lsn, op, payload)) {
                            stopped = true;
                            break;
                        }
                    } catch (WalException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new WalException("Replay handler error at lsn=" + lsn, e);
                    }
                }
            }
        }
        return new WalReplayResult(new CheckpointMeta(checkpointLsn.get(), 1, 0L, System.currentTimeMillis()), records);
    }

    @Override
    public long appendChain(WalChainHandler handler) throws IOException {
        ensureOpen();
        List<WalOp> ops = new ArrayList<>();
        try {
            handler.apply(new ChronicleWalChain(ops));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new WalException("Chain append failed", e);
        }
        long lastLsn = currentLsn.get();
        for (WalOp op : ops) {
            lastLsn = append(op.op(), op.payload());
        }
        return lastLsn;
    }

    @Override
    public Optional<WalRecord> findByLsn(long lsn) throws IOException {
        ensureOpen();
        ExcerptTailer tailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = tailer.readingDocument(true)) {
                if (!dc.isPresent()) return Optional.empty();
                if (!dc.isData()) continue;
                ValueIn lsnIn = dc.wire().read(FIELD_LSN);
                if (!lsnIn.isPresent()) continue;
                long cur = lsnIn.int64();
                byte op = dc.wire().read(FIELD_OP).int8();
                byte[] payload = dc.wire().read(FIELD_PAYLOAD).bytes();
                if (payload == null) payload = new byte[0];
                if (cur == lsn) {
                    return Optional.of(new WalRecord(cur, op, payload));
                }
            }
        }
    }

    @Override
    public int purgeCheckpointed(int keepSegments) throws IOException {
        return 0;
    }

    @Override
    public WalSegmentInfo currentSegment() {
        return new WalSegmentInfo(1,
                checkpointLsn.get() + 1,
                currentLsn.get(),
                Math.toIntExact(Math.max(0L, currentLsn.get() - checkpointLsn.get())),
                resolveDir(config),
                true);
    }

    @Override
    public List<WalSegmentInfo> listSegments() {
        return Collections.singletonList(currentSegment());
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            queue.close();
        } catch (Exception e) {
            throw new IOException("Close failed", e);
        }
    }

    private void ensureOpen() {
        if (closed) throw new WalException("WAL already closed");
    }

    private static final class ChronicleWalChain implements WalChain {
        private final List<WalOp> ops;

        ChronicleWalChain(List<WalOp> ops) {
            this.ops = ops;
        }

        @Override
        public WalChain add(byte op, byte[] payload) {
            ops.add(new WalOp(op, payload == null ? new byte[0] : payload));
            return this;
        }

        @Override
        public WalChain add(byte op, String s) {
            byte[] bytes = s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ops.add(new WalOp(op, bytes));
            return this;
        }

        @Override
        public WalChain add(byte op) {
            ops.add(new WalOp(op, new byte[0]));
            return this;
        }

        @Override
        public int size() {
            return ops.size();
        }
    }
}