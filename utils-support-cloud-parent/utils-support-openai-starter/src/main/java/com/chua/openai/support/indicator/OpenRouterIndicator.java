package com.chua.openai.support.indicator;

import com.chua.common.support.ai.chat.ChatClientIndicator;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.indicator.OpenRouterChatClientIndicator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter 服务商的 {@link ChatClientIndicator} 实现。
 *
 * <p>保留该类作为 OpenRouter 模块对外的稳定入口，具体解析统一委托给
 * {@link OpenRouterChatClientIndicator}：OpenRouter 的 {@code /api/v1/models}
 * 返回 {@code {data:[...]}}，价格字段是<b>按 Token</b> 的字符串
 * （{@code pricing.prompt/completion}），与 OpenAI 兼容的通用解析不兼容。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OpenRouterIndicator implements ChatClientIndicator {

    /**
     * OpenRouter 公开模型目录（无需 Key）
     */
    public static final String DEFAULT_URL = OpenRouterChatClientIndicator.DEFAULT_URL;

    private final OpenRouterChatClientIndicator delegate;

    public OpenRouterIndicator(ChatClientSetting setting) {
        this.delegate = new OpenRouterChatClientIndicator(setting);
    }

    public OpenRouterIndicator(String metricsUrl) {
        this(metricsUrl, new ObjectMapper());
    }

    public OpenRouterIndicator(String metricsUrl, ObjectMapper objectMapper) {
        this.delegate = new OpenRouterChatClientIndicator(metricsUrl, objectMapper);
    }

    @Override
    public String source() {
        return delegate.source();
    }

    @Override
    public String sourceUrl() {
        return delegate.sourceUrl();
    }

    @Override
    public Long capturedAt() {
        return delegate.capturedAt();
    }

    @Override
    public List<Map<String, Object>> supportedEndpoints() {
        return delegate.supportedEndpoints();
    }

    @Override
    public List<Map<String, Object>> supportedParameters() {
        return delegate.supportedParameters();
    }

    @Override
    public Map<String, Map<String, Object>> modelMetrics() {
        return delegate.modelMetrics();
    }

    @Override
    public BigDecimal inputUnitPrice(String modelId) {
        return delegate.inputUnitPrice(modelId);
    }

    @Override
    public BigDecimal outputUnitPrice(String modelId) {
        return delegate.outputUnitPrice(modelId);
    }

    @Override
    public Long contextWindowTokens(String modelId) {
        return delegate.contextWindowTokens(modelId);
    }
}
