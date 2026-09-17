package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * KV 格式 WAL 文件系统实现。
 * payload: int32 键len + 键(utf8) + int32 vallen + val(byte[])
 * @author CH
 * @since 4.0.0
 * @param key 键
 * @param value 值
 * @return KvPair的结果
 * @param config 配置
*/
@Spi("wal-kv")
public class KvWalFileSystem extends AbstractWalFileSystem {

    /**
    * kvwal文件系统。
    * @param config 配置
    */
    public KvWalFileSystem(WalStoreConfig config) throws IOException {
        super(config);
    /**
    * op类型。
    * @return op类型的结果
    */
    }

    @Override
    protected byte opType() { return 0x01; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return null;
        }
        int keyLen = ByteBuffer.wrap(payload).getInt();
        if (keyLen <= 0 || keyLen > payload.length - 4) {
            return null;
        }
        /**
        * encode。
        * @param key 键
        * @param value 值
        * @return encode的结果
        */
        return new String(payload, 4, keyLen, StandardCharsets.UTF_8);
    }

    public static byte[] encode(String key, byte[] value) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 4 + (value == null ? 0 : value.length));
        bb.putInt(kb.length); bb.put(kb);
        bb.putInt(value == null ? 0 : value.length);
        if (value != null) {
            bb.put(value);
        }
        byte[] result = new byte[bb.position()];
        bb.position(0); bb.get(result);
        /**
        * decode。
        * @param payload payload
        * @return decode的结果
        * @param key 键
        * @param value 值
        */
        return result;
    /**
    * decode。
    * @param payload payload
    * @return decode的结果
    * @param key 键
    * @param value 值
    */
    }

    public static Optional<KvPair> decode(byte[] payload) {
        if (payload == null || payload.length < 8) {
            return Optional.empty();
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int keyLen = bb.getInt();
        if (keyLen < 0 || keyLen > bb.remaining()) {
            return Optional.empty();
        }
        byte[] kb = new byte[keyLen]; bb.get(kb);
        int valLen = bb.getInt();
        byte[] val = valLen > 0 ? new byte[valLen] : new byte[0];
        if (valLen > 0) {
            bb.get(val);
        }
        return Optional.of(new KvPair(new String(kb, StandardCharsets.UTF_8), val));
    }

    public record KvPair(String key, byte[] value) {}
}
