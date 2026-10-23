package com.chua.openai.support.indicator;

import com.chua.common.support.ai.chat.ChatClientIndicator;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.indicator.PollinationsChatClientIndicator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Pollinations 服务商的 {@link ChatClientIndicator} 实现。
 *
 * <p>保留该类作为 Pollinations 模块对外的稳定入口，具体解析统一委托给
 * {@link PollinationsChatClientIndicator}：{@code /models} 返回顶层数组，
 * 价格字段是 {@code pricing.promptTextTokens/completionTextTokens}（按 Token），
 * 通用 JSON 解析无法直接命中。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class PollinationsIndicator implements ChatClientIndicator {

    /**
     * Pollinations 公开模型目录（无需 Key）
     */
    public static final String DEFAULT_URL = PollinationsChatClientIndicator.DEFAULT_URL;

    private final PollinationsChatClientIndicator delegate;

    public PollinationsIndicator(ChatClientSetting setting) {
        this.delegate = new PollinationsChatClientIndicator(setting);
    }

    public PollinationsIndicator(String metricsUrl) {
        this(metricsUrl, new ObjectMapper());
    }

    public PollinationsIndicator(String metricsUrl, ObjectMapper objectMapper) {
        this.delegate = new PollinationsChatClientIndicator(metricsUrl, objectMapper);
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
