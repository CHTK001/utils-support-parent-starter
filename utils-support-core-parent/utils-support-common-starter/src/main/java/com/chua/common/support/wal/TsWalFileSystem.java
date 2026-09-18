package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * TS（时序）格式 WAL 文件系统实现。
 * payload: int32 键len + 键(utf8) + int64 ts + double 值 + [int32 ttlsec]
 * @author CH
 * @since 4.0.0
 * @param now now
 * @return 是否expired的结果
 * @param config 配置
*/
@Spi("wal-ts")
public class TsWalFileSystem extends AbstractWalFileSystem {

    /**
    * tswal文件系统。
    * @param config 配置
    */
    public TsWalFileSystem(WalStoreConfig config) throws IOException {
        super(config);
    }

    @Override
    protected byte opType() { return 0x02; }
/**
 * decode键。
 * @param payload payload
 * @return decode键的结果
*/

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return null;
        }
        int keyLen = ByteBuffer.wrap(payload).getInt();
        if (keyLen <= 0 || keyLen > payload.length - 4) {
            return null;
        }
        return new String(payload, 4, keyLen, StandardCharsets.UTF_8);
    /**
    * encode。
    * @param measure 测量
    * @param ts ts
    * @param value 值
    * @return encode的结果
    */
    }

    /**
     * 编码。
     *
     * @param measure 方法入参 measure
     * @param ts 方法入参 ts
     * @param value 值，不允许为 null
     * @return 结果值
     */
    public static byte[] encode(String measure, long ts, double value) {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 8 + 8);
        bb.putInt(kb.length);
        bb.put(kb);
        bb.putLong(ts);
        bb.putDouble(value);
        return bb.array();
    /**
    * encodewithttl。
    * @param measure 测量
    * @param ts ts
    * @param value 值
    * @param ttlSec ttlsec
    * @return encodeWithTtl的结果
    */
    }

    /**
     * 编码WithTtl。
     *
     * @param measure 方法入参 measure
     * @param ts 方法入参 ts
     * @param value 值，不允许为 null
     * @param ttlSec 方法入参 ttlSec
     * @return 结果值
     */
    public static byte[] encodeWithTtl(String measure, long ts, double value, int ttlSec) {
        byte[] kb = measure.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + kb.length + 8 + 8 + 4);
        bb.putInt(kb.length);
        bb.put(kb);
        bb.putLong(ts);
        bb.putDouble(value);
        bb.putInt(ttlSec);
        return bb.array();
    /**
    * decode。
    * @param payload payload
    * @return decode的结果
    */
    }

    /**
     * 解码。
     *
     * @param payload 方法入参 payload
     * @return 可选结果，不存在时为 Optional.empty()
     */
    public static Optional<TsRecord> decode(byte[] payload) {
        if (payload == null || payload.length < 20) {
            return Optional.empty();
        }
        int pos = 0;
        int keyLen = ByteBuffer.wrap(payload, pos, 4).getInt();
        pos += 4;
        if (keyLen <= 0 || pos + keyLen > payload.length) {
            return Optional.empty();
        }
        String measure = new String(payload, pos, keyLen, StandardCharsets.UTF_8);
        pos += keyLen;
        if (pos + 16 > payload.length) {
            return Optional.empty();
        }
        long ts = ByteBuffer.wrap(payload, pos, 8).getLong();
        pos += 8;
        double value = ByteBuffer.wrap(payload, pos, 8).getDouble();
        pos += 8;
        Integer ttlSec = pos + 4 <= payload.length ? ByteBuffer.wrap(payload, pos, 4).getInt() : null;
        return Optional.of(new TsRecord(measure, ts, value, ttlSec));
    /**
    * tsrecord。
    * @param measure 测量
    * @param ts ts
    * @param value 值
    * @param ttlSec ttlsec
    * @return TsRecord的结果
    * @param now now
    */
    }

    /**
     * Ts记录。
     *
     * @param measure 方法入参 measure
     * @param ts 方法入参 ts
     * @param value 值，不允许为 null
     * @param ttlSec 方法入参 ttlSec
     * @return 结果值
     */
    public record TsRecord(String measure, long ts, double value, Integer ttlSec) {
        /**
        * expireAt。
        * @return expireAt的结果
        */
        public long expireAt() {
            return ttlSec == null ? Long.MAX_VALUE : ts + (long) ttlSec * 1000L;
        }
        public boolean isExpired(long now) { return expireAt() <= now; }
    }
}
