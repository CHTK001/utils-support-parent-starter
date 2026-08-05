package com.chua.common.support.wal;


/**
 * WAL 回放处理器，由业务方实现。
 *
 * <p>用于 {@link WalLog#replay(long, long, WalReplayHandler)} 中处理单条记录。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface WalReplayHandler {

    /**
     * 处理单条 WAL 记录。
     *
     * @param lsn     记录序号
     * @param op      操作类型
     * @param payload 业务字节流
     * @return true=继续回放后续记录；false=中止回放
     * @throws Exception 业务异常，回放循环会中止并向上抛出
     */
    boolean onRecord(long lsn, byte op, byte[] payload) throws Exception;
}
