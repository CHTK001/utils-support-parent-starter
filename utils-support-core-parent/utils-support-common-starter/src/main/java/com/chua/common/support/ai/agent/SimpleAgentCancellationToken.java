package com.chua.common.support.ai.agent;

/**
 * {@link AgentCancellationToken} 默认实现：可手动置位，线程安全。
 *
 * <p>调用方持有实例，在需要终止运行时调用 {@link #cancel(String)}；
 * 执行方在循环点轮询 {@link #isCancelled()}。也提供
 * {@link #throwIfCancelled()} 供工具执行等同步点直接抛
 * {@link AgentCancelledException}。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class SimpleAgentCancellationToken implements AgentCancellationToken {

    /**
     * none() 共享单例
     */
    static final class NoneHolder {
        static final SimpleAgentCancellationToken INSTANCE = new SimpleAgentCancellationToken();
    }

    /**
     * 取消标志
     */
    private volatile boolean cancelled = false;

    /**
     * 取消原因
     */
    private volatile String reason;

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String reason() {
        return reason;
    }

    /**
     * 置位取消（首因保留：多次调用只保留第一次原因）。
     *
     * @param reason 取消原因，可为 null
     */
    public void cancel(String reason) {
        if (!cancelled) {
            synchronized (this) {
                if (!cancelled) {
                    this.reason = reason;
                    this.cancelled = true;
                }
            }
        }
    }

    /**
     * 已取消则抛出 {@link AgentCancelledException}。
     */
    public void throwIfCancelled() {
        if (cancelled) {
            throw new AgentCancelledException(reason);
        }
    }
}
