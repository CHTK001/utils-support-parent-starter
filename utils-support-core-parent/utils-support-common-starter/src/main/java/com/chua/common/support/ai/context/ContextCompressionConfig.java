package com.chua.common.support.ai.context;

import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.chat.ChatClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文压缩配置（轻量，可脱离 Agent 使用）。
 * <p>
 * 与 {@link AgentCompressionConfig} 字段对齐，可互转。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextCompressionConfig {

    @Builder.Default
    /**
     * 是否启用
     */
    private boolean enabled = false;

    /** 消息数达到此值触发首次压缩并保存基线 */
    @Builder.Default
    /** Compression阈值 */
    private int compressionThreshold = 12;

    /** 基线后每 N 轮做偏差纠正 */
    @Builder.Default
    /** Deviation阈值 */
    private int deviationThreshold = 6;

    /** 压缩用 ChatClient（建议小模型） */
    /** Compressionchat客户端 */
    private ChatClient compressionChatClient;

    /** 压缩后保留最近消息数 */
    @Builder.Default
    /** Retainmessages */
    private int retainMessages = 6;

    /** 基线快照工作目录（默认 .agent/memory） */
    @Builder.Default
    /** Workspace */
    private String workspace = ".agent/memory";

    public static ContextCompressionConfig from(AgentCompressionConfig agent) {
        if (agent == null) {
            return ContextCompressionConfig.builder().build();
        }
        return ContextCompressionConfig.builder()
                .enabled(agent.isEnabled())
                .compressionThreshold(agent.getContextCompressionThreshold())
                .deviationThreshold(agent.getContextDeviationThreshold())
                .compressionChatClient(agent.getCompressionChatClient())
                .retainMessages(agent.getRetainMessages())
                .build();
    }

    public AgentCompressionConfig toAgentConfig() {
        return AgentCompressionConfig.builder()
                .enabled(enabled)
                .contextCompressionThreshold(compressionThreshold)
                .contextDeviationThreshold(deviationThreshold)
                .compressionChatClient(compressionChatClient)
                .retainMessages(retainMessages)
                .build();
    }
}
