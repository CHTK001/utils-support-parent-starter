package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.chat.ChatClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 上下文压缩配置。
 *
 * <p>控制 Agent 在执行过程中如何对对话历史进行压缩和偏差纠正。
 * 压缩分为两个阶段：
 * <ol>
 *   <li><b>首次压缩</b> — 当消息数达到 {@link #contextCompressionThreshold} 时，
 *       在压缩前通过 Consumer 保存完整原始上下文作为基线快照</li>
 *   <li><b>偏差纠正</b> — 基线建立后每经过 {@link #contextDeviationThreshold} 轮，
 *       读取基线快照进行总结，与当前压缩上下文做偏差纠正，输出修复后的上下文</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   Agent.create("agentscope")
 *       .chatClient(mainChatClient)
 *       .compressionConfig(AgentCompressionConfig.builder()
 *           .enabled(true)
 *           .contextCompressionThreshold(12)
 *           .contextDeviationThreshold(6)
 *           .build())
 *       .run("开始对话");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCompressionConfig {

    /**
     * 是否启用上下文压缩。
     *
     * <p>默认 false，关闭时所有压缩逻辑不生效。
     */
    @Builder.Default
    private boolean enabled = false;

    /**
     * 上下文压缩触发阈值（消息数）。
     *
     * <p>当历史消息数达到此值时触发首次压缩。
     * 压缩前会保存完整的原始上下文作为基线快照。
     */
    @Builder.Default
    private int contextCompressionThreshold = 12;

    /**
     * 上下文偏差纠正阈值（轮次）。
     *
     * <p>基线建立后，每经过此轮数触发一次偏差纠正：
     * 读取基线快照 → 用压缩 ChatClient 总结 → 与当前压缩上下文偏差纠正 → 输出修复后的上下文。
     */
    @Builder.Default
    private int contextDeviationThreshold = 6;

    /**
     * 压缩专用 ChatClient。
     *
     * <p>用于总结和偏差纠正。若未设置（null），默认和主 Agent 的 ChatClient 同源。
     * 建议使用更便宜的小模型以节省成本。
     */
    @Builder.Default
    private ChatClient compressionChatClient = null;

    /**
     * 压缩后保留的最近消息数。
     *
     * <p>在压缩上下文时，保留最近 N 条消息不被压缩，
     * 确保最近的对话上下文完整保留。
     */
    @Builder.Default
    private int retainMessages = 6;

    public boolean isEnabled() {
        return enabled;
    }

    public int getContextCompressionThreshold() {
        return contextCompressionThreshold;
    }

    public int getContextDeviationThreshold() {
        return contextDeviationThreshold;
    }

    public ChatClient getCompressionChatClient() {
        return compressionChatClient;
    }

    public int getRetainMessages() {
        return retainMessages;
    }
}
