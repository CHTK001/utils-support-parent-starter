package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * KV 格式 WAL 文件系统实现。
 * payload: int32 keyLen + key(utf8) + int32 valLen + val(byte[])
 */
@Spi("wal-kv")
public class KvWalFileSystem extends AbstractWalFileSystem {

    public KvWalFileSystem(WalStoreConfig config) throws IOException {
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

    public static byte[] encode(String key, byte[] value) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 4 + (value == null ? 0 : value.length));
        bb.putInt(kb.length); bb.put(kb);
        bb.putInt(value == null ? 0 : value.length);
        if (value != null) bb.put(value);
        byte[] result = new byte[bb.position()];
        bb.position(0); bb.get(result);
        return result;
    }

    public static Optional<KvPair> decode(byte[] payload) {
        if (payload == null || payload.length < 8) return Optional.empty();
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int keyLen = bb.getInt();
        if (keyLen < 0 || keyLen > bb.remaining()) return Optional.empty();
        byte[] kb = new byte[keyLen]; bb.get(kb);
        int valLen = bb.getInt();
        byte[] val = valLen > 0 ? new byte[valLen] : new byte[0];
        if (valLen > 0) bb.get(val);
        return Optional.of(new KvPair(new String(kb, StandardCharsets.UTF_8), val));
    }

    public record KvPair(String key, byte[] value) {}
}
