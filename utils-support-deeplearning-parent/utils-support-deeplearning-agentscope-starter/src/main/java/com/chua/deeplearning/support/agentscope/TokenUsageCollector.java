package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.AiUsage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次运行的令牌用量收集器。
 *
 * <p>{@link AgentScopeAgent#run(String)} 走的是 {@code HarnessAgent.call(...)} 阻塞路径，
 * 拿不到流式的 {@code ModelCallEndEvent} 序列，因此无法像 {@code runStream} 那样在
 * 事件处理里就地累加。解决办法是：为每次运行建一个收集器，交给
 * {@link AgentDebugMiddleware} 在 {@code ModelCallEndEvent} 上累加，
 * 运行结束后由调用方读取。</p>
 *
 * <p>之所以不复用 {@link AgentDebugMiddleware} 自己的 {@code totalInputTokens} 字段：
 * 中间件实例由 harness 持有，调用方拿不到引用；且中间件的计数是跨运行累积的，
 * 而 {@code run()} 需要的是<b>本次</b>运行的增量。故用独立对象、每次运行新建。</p>
 *
 * <p>线程安全：模型调用可能并发（并行工具后的多轮调用），故用原子累加。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TokenUsageCollector {

    /**
     * 输入令牌累计
     */
    private final AtomicLong inputTokens = new AtomicLong();

    /**
     * 输出令牌累计
     */
    private final AtomicLong outputTokens = new AtomicLong();

    /**
     * 缓存命中令牌累计
     */
    private final AtomicLong cachedTokens = new AtomicLong();

    /**
     * 累加一次模型调用的用量。
     *
     * @param input  输入令牌；负数视为未提供
     * @param output 输出令牌；负数视为未提供
     * @param cached 缓存令牌；负数视为未提供
     */
    public void add(int input, int output, int cached) {
        if (input > 0) {
            inputTokens.addAndGet(input);
        }
        if (output > 0) {
            outputTokens.addAndGet(output);
        }
        if (cached > 0) {
            cachedTokens.addAndGet(cached);
        }
    }

    /**
     * 本次运行是否观测到任何用量。
     *
     * @return 三项均未观测到时返回 false
     */
    public boolean isEmpty() {
        return inputTokens.get() == 0L && outputTokens.get() == 0L && cachedTokens.get() == 0L;
    }

    /**
     * 汇总为项目用量对象；未观测到任何用量时返回 null（不造零值）。
     *
     * @param startTime      运行开始时刻（毫秒）
     * @param durationMillis 运行耗时（毫秒）
     * @return 用量对象；无用量返回 null
     */
    public AiUsage toUsage(long startTime, long durationMillis) {
        if (isEmpty()) {
            return null;
        }
        int in = (int) inputTokens.get();
        int out = (int) outputTokens.get();
        return AiUsage.builder()
                .inputTokens(in)
                .outputTokens(out)
                .cacheTokens((int) cachedTokens.get())
                .totalTokens(in + out)
                .startTime(startTime)
                .durationMillis(durationMillis)
                .build();
    }
}
