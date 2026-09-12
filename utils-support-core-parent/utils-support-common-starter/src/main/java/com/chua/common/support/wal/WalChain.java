package com.chua.common.support.wal;


/**
 * WAL 链式写入接口。
 *
 * <p>用于 {@link WalLog#appendChain(WalChainHandler)} 中在回调内连续追加多条记录，
 * 整个链路完成后一次性 fsync，保证原子性。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface WalChain {

    /**
     * 追加一条带字节 payload 的操作。
     *
     * @param op      操作类型
     * @param payload 业务字节流
     * @return 当前 walchain（链式调用）
     */
    WalChain add(byte op, byte[] payload);

    /**
     * 追加一条带字符串 payload 的操作（UTF-8 编码）。
     *
     * @param op 操作类型
     * @param s  业务字符串
     * @return 当前 walchain（链式调用）
     */
    WalChain add(byte op, String s);

    /**
     * 追加一条空 payload 的操作。
     *
     * @param op 操作类型
     * @return 当前 walchain（链式调用）
     */
    WalChain add(byte op);

    /**
     * 当前链上已累计的操作数量。
     *
     * @return 数量
     */
    int size();
}
