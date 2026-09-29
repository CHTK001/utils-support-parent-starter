package com.chua.common.support.ai.context;

import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.chat.ChatClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文压缩配置（轻量，可脱离 Agent 使用）。
 * 与 {@link AgentCompressionConfig} 字段对齐，可互转。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextCompressionConfig {

    /**
     * 是否启用
     */
    @Builder.Default
    private boolean enabled = false;

    /**
     * 消息数达到此值触发首次压缩并保存基线
     *
     * Compression阈值
     */
    @Builder.Default
    private int compressionThreshold = 12;

    /**
     * 基线后每 N 轮做偏差纠正
     *
     * Deviation阈值
     */
    @Builder.Default
    private int deviationThreshold = 6;

    /**
     * 压缩用 ChatClient（建议小模型）
     */
    private ChatClient compressionChatClient;

    /**
     * 压缩后保留最近消息数
     *
     * Retainmessages
     */
    @Builder.Default
    private int retainMessages = 6;

    /**
     * 基线快照工作目录（默认 .agent/memory）
     *
     * Workspace
     */
    @Builder.Default
    private String workspace = ".agent/memory";

    /**
     * From
     * @param agent 方法入参 agent
     * @return 上下文Compression配置 对象
     */
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

    /**
     * ToAgentConfig
     * @return AgentCompression配置 对象
     */
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
