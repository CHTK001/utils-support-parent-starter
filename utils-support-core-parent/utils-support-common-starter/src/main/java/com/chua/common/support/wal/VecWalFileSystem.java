package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * VEC（向量）格式 WAL 文件系统实现。
 *
 * <p>职责：使用 WAL 操作码写入向量记录，并负责向量标识、维度、浮点向量和元数据的编码与解码。</p>
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

    /**
     * WAL 中一条向量记录。
     *
     * @param id       向量 标识
     * @param dim      向量维度
     * @param data     向量浮点数组
     * @param metadata 元数据原文，可为 空
     */
    public record VecRecord(String id, int dim, float[] data, String metadata) {

        /**
         * 规范构造器：标识 必填，向量浮点数组 做防御性拷贝。
         *
         * <p>value class 前置条件——空值敌对，且数组组件必须深不可变。
         * {@link #decode(byte[])} 只会用 反解 出的标识与新分配的数组构造本记录。</p>
         *
         * <p>浮点数组 与 元数据原文 均保留 空 语义：{@link #decode(byte[])} 在分片尾部
         * 缺少元数据长度时会显式置 空，而浮点数组 无从证明外部调用方不传 空。</p>
         */
        public VecRecord {
            Objects.requireNonNull(id, "id 不能为 null");
            data = data == null ? null : data.clone();
        }

        /**
         * 访问器覆写：返回内部浮点数组的副本。
         *
         * @return 浮点数组副本；data 为 空 时返回 空
         */
        @Override
        public float[] data() {
            return data == null ? null : data.clone();
        }
    }
}
