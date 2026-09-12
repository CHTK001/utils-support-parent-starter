package com.chua.common.support.wal;


/**
* WAL 单个操作（用于链式 API）。
*
* @param op      操作类型
* @param payload 业务字节流
* @author CH
* @since 4.0.0.42
 */
public record WalOp(
    byte op,
    byte[] payload
) {

    /**
    * 复制 payload 防止外部修改。
     */
    public WalOp {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    /**
    * 创建指定操作类型的空 payload 操作。
    *
    * @param op 操作类型
    * @return WalOp 实例
     */
    public static WalOp of(byte op) {
        return new WalOp(op, new byte[0]);
    }

    /**
    * 创建带字节 payload 的操作。
    *
    * @param op      操作类型
    * @param payload 业务字节流
    * @return WalOp 实例
     */
    public static WalOp of(byte op, byte[] payload) {
        return new WalOp(op, payload);
    }
}
