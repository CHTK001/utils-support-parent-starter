package com.chua.common.support.ai.chat.aggregate.monitor;

import com.chua.common.support.ai.AiUsage;

import java.util.function.Consumer;

/**
 * 用量记录器 — 收集 AI 调用 {@link AiUsage} 数据。
 *
 * <p>作为 {@link Consumer}<{@link AiUsage}> 使用，可直接传入
 * {@link com.chua.common.support.ai.chat.aggregate.FailoverTemplate}。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface UsageRecorder extends Consumer<AiUsage> {

    /**
     * 获取当前统计
     *
     * @return 统计信息
     */
    UsageStats stats();

    /**
     * 重置统计
     */
    default void reset() {
    }

    @Override
    /** Accept */
    default void accept(AiUsage usage) {
        if (usage != null) {
            record(usage);
        }
    }

    /**
    * 记录一次用量
    * @param usage 方法入参 usage
    */
    void record(AiUsage usage);
}
