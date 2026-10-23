package com.chua.common.support.datasearch.chat.indicator;

import com.chua.common.support.ai.chat.ChatClientIndicator;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.indicator.ModelsDevChatClientIndicator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Models.dev 服务商的 {@link ChatClientIndicator} 实现。
 *
 * <p>保留该类作为 Models.dev 模块对外的稳定入口，具体解析统一委托给
 * {@link ModelsDevChatClientIndicator}：models.dev 的响应是
 * {@code {厂商: {models: {模型: {cost, limit, modalities, ...}}}}} 两级结构，
 * 价格字段 {@code cost.input/output} 已是 USD / 百万 Token。
 * 不指定厂商时返回全量。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ModelsDevIndicator implements ChatClientIndicator {

    /**
     * Models.dev 公开端点（无需 Key）
     */
    public static final String DEFAULT_URL = ModelsDevChatClientIndicator.DEFAULT_URL;

    private final ModelsDevChatClientIndicator delegate;

    public ModelsDevIndicator(ChatClientSetting setting) {
        String url = setting != null ? setting.getMetricsUrl() : null;
        this.delegate = new ModelsDevChatClientIndicator(null, url, new ObjectMapper());
    }

    public ModelsDevIndicator(String metricsUrl) {
        this(metricsUrl, new ObjectMapper());
    }

    public ModelsDevIndicator(String metricsUrl, ObjectMapper objectMapper) {
        this.delegate = new ModelsDevChatClientIndicator(null, metricsUrl, objectMapper);
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
