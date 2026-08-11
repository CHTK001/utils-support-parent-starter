package com.chua.common.support.scattergather;

/**
 * 传输协议降级策略。
 * <p>当首选协议（如 UDP）不可用时，通过此接口降级为备用协议（如 TCP）。</p>
 *
 * @author CH
 */
@FunctionalInterface
public interface TransportFallbackStrategy {

    /**
     * 执行降级调用。
     *
     * @param context       查询上下文
     * @param node          目标节点
     * @param timeoutMillis 超时时间
     * @return 查询结果
     * @throws Exception 调用异常
     */
    ScatterGatherResult<Object> fallbackInvoke(ScatterGatherContext context, ScatterGatherNode node, long timeoutMillis) throws Exception;
}