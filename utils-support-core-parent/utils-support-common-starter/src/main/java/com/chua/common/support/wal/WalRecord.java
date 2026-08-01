package com.chua.common.support.wal;

/**
 * WAL 单条记录（不可变）。
 *
 * @param lsn     日志序号，从 1 开始单调递增
 * @param op      操作类型，业务自定义，取值范围 0-127
 * @param payload 业务序列化字节流，长度由调用方决定
 * @author CH
 * @since 4.0.0.42
 */
public record WalRecord(
    long lsn,
    byte op,
    byte[] payload
) {

    /**
     * 复制 payload 防止外部修改影响记录内部状态。
     */
    public WalRecord {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    /**
     * 返回 payload 副本，调用方可以安全修改。
     *
     * @return payload 字节数组副本
     */
    public byte[] payloadCopy() {
        return payload.clone();
    }
}
