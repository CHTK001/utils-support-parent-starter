package com.chua.common.support.wal;


/**
 * 链式写入处理器，由业务方实现。
 *
 * <p>在回调中通过 {@link WalChain} 连续追加多条记录，回调返回后由
 * {@link WalLog#appendChain(WalChainHandler)} 统一一次性 fsync。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
@FunctionalInterface
public interface WalChainHandler {

    /**
    * 在回调中追加链式操作。
    *
    * @param chain 链式写入接口
    * @throws Exception 业务异常，写入会中止
    */
    void apply(WalChain chain) throws Exception;
}
