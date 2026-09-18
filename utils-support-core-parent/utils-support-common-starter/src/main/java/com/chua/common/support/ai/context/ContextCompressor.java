package com.chua.common.support.ai.context;

import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.agent.AgentContextCompressionService;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
* 统一上下文压缩入口 — Agent / 普通 ChatClient 共用。
* <p>
* 算法委托 {@link AgentContextCompressionService}（两阶段：阈值压缩 + 偏差纠正）。
* 不创建计划、不调用工具，只处理 {@code List<ChatMessage>}。
* </p>
*
* <pre>{@code
* // 轻量
* ContextCompressor c = ContextCompressor.create(config, chatClient);
* // Agent 配置
* ContextCompressor c = ContextCompressor.fromAgent(agentConfig, chatClient, workspace);
* List&lt;ChatMessage&gt; next = c.maybeCompress(history);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public final class ContextCompressor {

    /** 压缩配置 */
    private final ContextCompressionConfig config;

    /** 底层压缩服务 */
    private final AgentContextCompressionService delegate;

    /**
    * 创建 ContextCompressor 实例
    * @param config config
    * @param fallbackClient ChatClient
    */
    private ContextCompressor(ContextCompressionConfig config, ChatClient fallbackClient) {
        this.config = config != null ? config : ContextCompressionConfig.builder().build();
        String workspace = this.config.getWorkspace() != null
                ? this.config.getWorkspace()
                : ".agent/memory";
        this.delegate = new AgentContextCompressionService(
                this.config.toAgentConfig(),
                this.config.getCompressionChatClient(),
                fallbackClient,
                workspace);
    }

    /**
    * 轻量配置创建。
    * @param config 配置，不允许为 null
    * @param fallbackClient fallback客户端，不允许为 null
    * @return 上下文Compressor 对象
    */
    public static ContextCompressor create(ContextCompressionConfig config, ChatClient fallbackClient) {
        return new ContextCompressor(config, fallbackClient);
    }

    /**
    * 从 Agent 压缩配置创建（统一 Agent 与轻量入口）。
    */
    public static ContextCompressor fromAgent(AgentCompressionConfig agentConfig,
                                              ChatClient fallbackClient,
                                              String workspace) {
        ContextCompressionConfig cfg = ContextCompressionConfig.from(agentConfig);
        if (workspace != null && !workspace.isBlank()) {
            cfg.setWorkspace(workspace);
        }
        return new ContextCompressor(cfg, fallbackClient);
    }

    /**
     * Disabled
     * @return 上下文Compressor 对象
     */
    public static ContextCompressor disabled() {
        return new ContextCompressor(ContextCompressionConfig.builder().enabled(false).build(), null);
    }

    /**
     * 是否Enabled
     * @return 是否成功（true 表示成功）
     */
    public boolean isEnabled() {
        return config.isEnabled()
                && config.getCompressionThreshold() > 0;
    }

    /**
    * 与 Agent {@code CompressionAwareModel#detectAndCompress} 相同策略：
    * <ul>
    *   <li>未达阈值：若已有基线则累计偏差轮次，原样返回</li>
    *   <li>首次达阈值：保存基线并压缩</li>
    *   <li>已有基线且到偏差阈值：基线矫正</li>
    *   <li>已有基线但未到偏差阈值：原样返回</li>
    * </ul>
    * @param messages 方法入参 messages
    * @return 结果列表，无数据时为空列表
    */
    public List<ChatMessage> maybeCompress(List<ChatMessage> messages) {
        if (!isEnabled() || messages == null) {
            return messages;
        }

        int effectiveCount = messages.size();
        int threshold = config.getCompressionThreshold();

        if (effectiveCount < threshold) {
            if (delegate.isBaselineSaved()) {
                delegate.shouldTriggerDeviationCorrection();
            }
            return messages;
        }

        if (!delegate.isBaselineSaved()) {
            delegate.onFirstCompression(messages);
            List<ChatMessage> compressed = delegate.compressContext(messages);
            log.info("[ContextCompressor] First compression, {} -> {}",
                    effectiveCount, compressed != null ? compressed.size() : 0);
            return compressed;
        }

        if (delegate.shouldTriggerDeviationCorrection()) {
            List<ChatMessage> baseline = delegate.loadBaseline();
            if (baseline == null || baseline.isEmpty()) {
                log.warn("[ContextCompressor] Baseline empty, skip deviation correction");
                return messages;
            }
            List<ChatMessage> corrected = delegate.onDeviationCompression(baseline, messages);
            log.info("[ContextCompressor] Deviation correction, {} -> {}",
                    messages.size(), corrected != null ? corrected.size() : 0);
            return corrected;
        }

        return messages;
    }

    /**
    * 强制压缩（忽略消息数阈值，仍尊重 enabled）。
    * @param fullContext full上下文，不允许为 null
    * @return 结果列表，无数据时为空列表
    */
    public List<ChatMessage> compress(List<ChatMessage> fullContext) {
        if (!config.isEnabled() || fullContext == null) {
            return fullContext;
        }
        return delegate.compressContext(fullContext);
    }

    /**
    * 将单个 user prompt 包装为单条消息后按 maybeCompress 策略压缩。
    * <p>
    * 仅用于无状态的轻量 ChatClient（如 AggregateChatClient）：
    * 将 prompt 视为 {@code List<ChatMessage>} 的 user 消息，
    * 返回压缩后的内容（若未触发压缩则原样返回）。
    * </p>
    * @param prompt 提示词，不允许为 null
    * @return 结果字符串
    */
    public String compressPrompt(String prompt) {
        if (!isEnabled() || prompt == null) {
            return prompt;
        }
        List<ChatMessage> compressed = maybeCompress(List.of(new ChatMessage("user", prompt)));
        if (compressed == null || compressed.isEmpty()) {
            return prompt;
        }
        return compressed.getFirst().getContent();
    }

    /**
     * 是否BaselineSaved
     * @return 是否成功（true 表示成功）
     */
    public boolean isBaselineSaved() {
        return delegate.isBaselineSaved();
    }

    /**
     * 获取Config
     * @return 上下文Compression配置 对象
     */
    public ContextCompressionConfig getConfig() {
        return config;
    }

    /**
    * 底层服务（测试/高级场景）。
    * @return Agent上下文Compression服务 对象
    */
    public AgentContextCompressionService getService() {
        return delegate;
    }
}
