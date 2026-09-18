package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * VEC（向量）格式 WAL 文件系统实现。
 * payload: int32 键len + 键(utf8) + int32 dim + float[dim] + int32 metalen + meta(utf8)
 *
 * @author CH
 * @since 4.0.0
*/
@Spi("wal-vec")
public class VecWalFileSystem extends AbstractWalFileSystem {
/**
 * op类型。
 * @return op类型的结果
 * @param config 配置
*/

    public VecWalFileSystem(WalStoreConfig config) throws IOException {
        super(config);
    }

    @Override
    protected byte opType() { return 0x03; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return null;
        }
        /**
        * encode。
        * @param id 标识
        * @param dim dim
        * @param data 数据
        * @param metadata metadata
        * @return encode的结果
        */
        int keyLen = ByteBuffer.wrap(payload).getInt();
        if (keyLen <= 0 || keyLen > payload.length - 4) {
            return null;
        }
        return new String(payload, 4, keyLen, StandardCharsets.UTF_8);
    }

    /**
     * 编码。
     *
     * @param id ID，不允许为 null
     * @param dim 方法入参 dim
     * @param data 数据，不允许为 null
     * @param metadata 方法入参 metadata
     * @return 结果值
     */
    public static byte[] encode(String id, int dim, float[] data, String metadata) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] metaBytes = metadata == null ? new byte[0] : metadata.getBytes(StandardCharsets.UTF_8);
        int total = 4 + idBytes.length + 4 + dim * 4 + 4 + metaBytes.length;
        ByteBuffer bb = ByteBuffer.allocate(total);
        bb.putInt(idBytes.length);
        bb.put(idBytes);
        bb.putInt(dim);
        for (float f : data) {
            bb.putFloat(f);
        }
        bb.putInt(metaBytes.length);
        bb.put(metaBytes);
        /**
        * decode。
        * @param payload payload
        * @return decode的结果
        * @param id 标识
        * @param dim dim
        * @param data 数据
        * @param metadata metadata
        */
        byte[] result = new byte[bb.position()];
        /**
        * decode。
        * @param payload payload
        * @return decode的结果
        * @param id id
        * @param dim dim
        * @param data 数据
        * @param metadata metadata
        */
        bb.position(0);
        bb.get(result);
        return result;
    }

    /**
     * 解码。
     *
     * @param payload 方法入参 payload
     * @return 可选结果，不存在时为 Optional.empty()
     */
    public static Optional<VecRecord> decode(byte[] payload) {
        if (payload == null || payload.length < 12) {
            return Optional.empty();
        }
        int pos = 0;
        int idLen = ByteBuffer.wrap(payload, pos, 4).getInt();
        pos += 4;
        if (idLen < 0 || pos + idLen > payload.length) {
            return Optional.empty();
        }
        String id = new String(payload, pos, idLen, StandardCharsets.UTF_8);
        pos += idLen;
        if (pos + 4 > payload.length) {
            return Optional.empty();
        }
        int dim = ByteBuffer.wrap(payload, pos, 4).getInt();
        pos += 4;
        if (pos + dim * 4 > payload.length) {
            return Optional.empty();
        }
        float[] data = new float[dim];
        for (int i = 0; i < dim; i++) {
            data[i] = ByteBuffer.wrap(payload, pos + i * 4, 4).getFloat();
        }
        pos += dim * 4;
        String metadata = null;
        if (pos + 4 <= payload.length) {
            int metaLen = ByteBuffer.wrap(payload, pos, 4).getInt();
            pos += 4;
            if (metaLen > 0 && pos + metaLen <= payload.length) {
                metadata = new String(payload, pos, metaLen, StandardCharsets.UTF_8);
            }
        }
        return Optional.of(new VecRecord(id, dim, data, metadata));
    }

    public record VecRecord(String id, int dim, float[] data, String metadata) {}
}
