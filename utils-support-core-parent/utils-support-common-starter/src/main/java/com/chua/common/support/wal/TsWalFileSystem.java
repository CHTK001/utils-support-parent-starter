package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * TS（时序）格式 WAL 文件系统实现。
 * payload: int32 keyLen + key(utf8) + int64 ts + double value + [int32 ttlSec]
 */
@Spi("wal-ts")
public class TsWalFileSystem extends AbstractWalFileSystem {

    public TsWalFileSystem(WalStoreConfig config) throws IOException {
        super(config);
    }

    @Override
    protected byte opType() { return 0x02; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        int keyLen = ByteBuffer.wrap(payload).getInt();
        if (keyLen <= 0 || keyLen > payload.length - 4) return null;
        return new String(payload, 4, keyLen, StandardCharsets.UTF_8);
    }

    public static byte[] encode(String measure, long ts, double value) {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 8 + 8);
        bb.putInt(kb.length); bb.put(kb); bb.putLong(ts); bb.putDouble(value);
        return bb.array();
    }

    public static byte[] encodeWithTtl(String measure, long ts, double value, int ttlSec) {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 8 + 8 + 4);
        bb.putInt(kb.length); bb.put(kb); bb.putLong(ts); bb.putDouble(value); bb.putInt(ttlSec);
        return bb.array();
    }

    public static Optional<TsRecord> decode(byte[] payload) {
        if (payload == null || payload.length < 20) return Optional.empty();
        int pos = 0;
        int keyLen = ByteBuffer.wrap(payload, pos, 4).getInt(); pos += 4;
        if (keyLen <= 0 || pos + keyLen > payload.length) return Optional.empty();
        String measure = new String(payload, pos, keyLen, StandardCharsets.UTF_8);
        pos += keyLen;
        if (pos + 16 > payload.length) return Optional.empty();
        long ts = ByteBuffer.wrap(payload, pos, 8).getLong(); pos += 8;
        double value = ByteBuffer.wrap(payload, pos, 8).getDouble(); pos += 8;
        Integer ttlSec = pos + 4 <= payload.length ? ByteBuffer.wrap(payload, pos, 4).getInt() : null;
        return Optional.of(new TsRecord(measure, ts, value, ttlSec));
    }

    public record TsRecord(String measure, long ts, double value, Integer ttlSec) {
        public long expireAt() {
            return ttlSec == null ? Long.MAX_VALUE : ts + (long) ttlSec * 1000L;
        }
        public boolean isExpired(long now) { return expireAt() <= now; }
    }
}
