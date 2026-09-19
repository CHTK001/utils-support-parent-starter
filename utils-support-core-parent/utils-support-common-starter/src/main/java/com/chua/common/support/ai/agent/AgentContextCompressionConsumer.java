package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.chat.ChatMessage;

import java.util.List;

/**
 * 上下文压缩消费者 — 在压缩触发时回调。
 *
 * <p>通过 Agent 在消息轮次达到阈值时触发。
 * 分为两个阶段：
 * <ol>
 *   <li>{@link #onFirstCompression} — 首次触发，将完整原始上下文保存为基线快照</li>
 *   <li>{@link #onDeviationCompression} — 偏差纠正触发，用基线纠正当前压缩上下文</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface AgentContextCompressionConsumer {

    /**
     * 首次压缩触发。
     *
     * <p>在压缩逻辑执行前调用，确保捕获完整的原始上下文。
     * 基线快照将保存到工作区中，供后续偏差纠正阶段使用。
     *
     * @param fullContext 当前完整上下文（所有消息轮次）
     */
    void onFirstCompression(List<ChatMessage> fullContext);

    /**
     * 偏差纠正触发。
     *
     * <p>基线建立后每经过 {@code deviationThreshold} 轮触发一次。
     * 默认实现流程：
     * <ol>
     *   <li>读取工作区中保存的基线快照</li>
     *   <li>用全新会话的压缩 ChatClient 总结基线 → "基线压缩上下文"</li>
     *   <li>将基线压缩上下文 + 当前压缩上下文一起送入压缩 ChatClient</li>
     *   <li>输出纠正后的压缩上下文</li>
     * </ol>
     *
     * @param baselineContext 第一次保存的完整原始上下文快照
     * @param currentContext  当前（经多轮压缩后的）上下文
     * @return 纠正后的压缩上下文
     */
    List<ChatMessage> onDeviationCompression(List<ChatMessage> baselineContext,
                                              List<ChatMessage> currentContext);
}
