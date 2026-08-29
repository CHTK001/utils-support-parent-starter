package com.chua.common.support.datasource.wal;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.wal.*;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KV 存储引擎，亿级数据分片架构。
 */
@Slf4j
@Spi("wal-kv-store")
public class KvWalStoreSystem extends AbstractWalStoreSystem<String> {

    private static final byte OP_HAS_TTL = 0x20;

    public KvWalStoreSystem(WalStoreConfig config) throws IOException {
        super(config);
    }

    @Override
    protected byte opType() { return 0x01; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        int keyLen = ByteBuffer.wrap(payload).getInt();
        if (keyLen <= 0 || keyLen > payload.length - 4) return null;
        return new String(payload, 4, keyLen, StandardCharsets.UTF_8);
    }

    @Override
    protected Object decodeValue(String key, byte[] payload) {
        if (payload == null || payload.length < 8) return null;
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.getInt(); // skip keyLen
        bb.getInt(); // skip key bytes offset
        int valLen = bb.remaining() >= 4 ? bb.getInt() : 0;
        if (valLen <= 0 || valLen > bb.remaining()) return null;
        byte[] value = new byte[valLen];
        bb.get(value);
        return value;
    }

    @Override
    public int shardCount() { return config.shardCount(); }

    // ==================== KV 专用 API ====================

    public long put(String key, byte[] value) throws IOException {
        return append(key, encodePayload(key, value, 0));
    }

    public long putWithTtl(String key, byte[] value, int ttlSec) throws IOException {
        return append(key, encodePayload(key, value, ttlSec));
    }

    public List<Map.Entry<String, byte[]>> prefixScan(String prefix) throws IOException {
        return range(prefix, prefix + "\uffff");
    }

    public List<Map.Entry<String, byte[]>> prefixScan(String prefix, int offset, int limit) throws IOException {
        return range(prefix, prefix + "\uffff", offset, limit);
    }

    public List<String> allKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < config.shardCount(); i++) {
            for (WalSegmentInfo seg : walLogs[i].listSegments()) {
                walLogs[i].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if (!isTombstone(op)) {
                        String k = decodeKey(payload);
                        if (k != null) keys.add(k);
                    }
                    return true;
                });
            }
        }
        return new ArrayList<>(keys);
    }

    // ==================== 后台 TTL 清理 ====================

    public void startTtlScanner(long intervalSeconds) {
        compactScheduler.scheduleAtFixedRate(() -> {
            try { scanAndMarkExpired(); } catch (Exception e) {
                log.warn("[kv-store] TTL scan failed: {}", e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("[kv-store] TTL scanner started, interval={}s", intervalSeconds);
    }

    private void scanAndMarkExpired() throws IOException {
        long now = System.currentTimeMillis();
        int expired = 0;
        for (int i = 0; i < config.shardCount(); i++) {
            for (WalSegmentInfo seg : walLogs[i].listSegments()) {
                final int shardIdx = i;
                walLogs[i].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if (isTombstone(op)) return true;
                    if ((op & OP_HAS_TTL) == 0) return true;
                    // last 12 bytes: ttlSec(4) + setAt(8)
                    if (payload.length >= 16) {
                        int ttlSec = ByteBuffer.wrap(payload, payload.length - 12, 4).getInt();
                        long setAt = ByteBuffer.wrap(payload, payload.length - 8, 8).getLong();
                        if (setAt + (long) ttlSec * 1000L <= now) {
                            walLogs[shardIdx].append((byte) (opType() | AbstractWalFileSystem.OP_TOMBSTONE), new byte[0]);
                            expired++;
                        }
                    }
                    return true;
                });
            }
        }
        if (expired > 0) {
            log.info("[kv-store] TTL scan: {} records expired", expired);
            rebuildIndex();
        }
    }

    // ==================== 内部工具 ====================

    private byte[] encodePayload(String key, byte[] value, int ttlSec) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        int bodyLen = value == null ? 0 : value.length;
        int ttlLen = ttlSec > 0 ? 12 : 0;
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 4 + bodyLen + ttlLen);
        bb.putInt(kb.length); bb.put(kb);
        bb.putInt(bodyLen);
        if (value != null) bb.put(value);
        if (ttlLen > 0) {
            byte opWithTtl = (byte) (opType() | OP_HAS_TTL);
            bb.putInt(ttlSec);
            bb.putLong(System.currentTimeMillis());
        }
        return bb.array();
    }

    @Override
    public StoreType storeType() { return StoreType.KV; }

    public static KvWalStoreSystem create(Path baseDir) throws IOException {
        return new KvWalStoreSystem(new WalStoreEnvDetector().detect(baseDir));
    }

    public static KvWalStoreSystem create(Path baseDir, String namespace) throws IOException {
        return new KvWalStoreSystem(new WalStoreEnvDetector().detect(baseDir, namespace));
    }
}
