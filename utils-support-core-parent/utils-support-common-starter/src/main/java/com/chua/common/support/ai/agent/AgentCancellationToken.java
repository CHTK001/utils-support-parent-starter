package com.chua.common.support.ai.agent;

/**
 * Agent 协作式取消令牌。
 *
 * <p>与 {@link AgentLoopGuard} 的中断信号互补：loopGuard 面向「本轮会话护栏」，
 * 本令牌面向「外部调用方对一次运行的生命周期控制」——例如请求被上层取消、
 * 用户关闭页面、下游超时等。实现方在自己的循环点（模型调用前后、工具执行前后）
 * 检查 {@link #isCancelled()}，命中后停止执行并以带 {@link #reason()} 的
 * 取消语义收尾（不按异常堆栈告警）。</p>
 *
 * <p><b>线程安全</b>：实现必须支持任意线程调用。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@FunctionalInterface
public interface AgentCancellationToken {

    /**
     * 是否已取消。
     *
     * @return true 表示本次运行应尽快收尾
     */
    boolean isCancelled();

    /**
     * 取消原因。
     *
     * @return 原因文本；未取消或未说明时返回 null
     */
    default String reason() {
        return null;
    }

    /**
     * 永不取消的空令牌。
     *
     * @return 空令牌单例语义
     */
    static AgentCancellationToken none() {
        return SimpleAgentCancellationToken.NoneHolder.INSTANCE;
    }
}
