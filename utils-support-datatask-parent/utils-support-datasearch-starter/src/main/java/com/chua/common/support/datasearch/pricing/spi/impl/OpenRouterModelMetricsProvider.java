package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter 聚合模型定价提供者。
 *
 * <p>通过 OpenRouter 公开模型目录接口（免鉴权）获取数百个模型的输入/输出单价，
 * 单价按每百万 Token 换算（USD）。解析失败时回退到 classpath 内置 JSON。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("openrouter")
public class OpenRouterModelMetricsProvider extends AbstractModelMetricsProvider {

    /** Models_api */
    private static final String MODELS_API = "https://openrouter.ai/api/v1/models";

    /**
     * 每百万 Token 的换算基数
     */
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /**
     * 从公开模型目录 API 获取全量定价。
     *
     * @return 模型定价列表，接口不可达或结构变化时回退内置 JSON
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        String json = fetchUrl(MODELS_API);
        if (json == null || json.isEmpty()) {
            return readClasspathPricing();
        }
        List<ModelDefinition> parsed = parseModelsApi(json);
        return parsed.isEmpty() ? readClasspathPricing() : parsed;
    }

    /**
     * 解析 OpenRouter models 目录响应。
     *
     * @param json 响应 JSON
     * @return 模型定价列表
     */
    private List<ModelDefinition> parseModelsApi(String json) {
        List<Map<String, Object>> rows;
        try {
            Map<String, Object> root = Json.fromJson(json, new TypeReference<Map<String, Object>>() { });
            if (root == null || !(root.get("data") instanceof List)) {
                return new ArrayList<>();
            }
            rows = (List<Map<String, Object>>) root.get("data");
        } catch (Exception e) {
            log.debug("[openrouter] 目录解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            try {
                String id = String.valueOf(row.get("id"));
                Object pricingObj = row.get("pricing");
                if (!(pricingObj instanceof Map)) { continue; }
                Map<String, Object> pricing = (Map<String, Object>) pricingObj;
                BigDecimal in = perMillion(String.valueOf(pricing.get("prompt")));
                BigDecimal out = perMillion(String.valueOf(pricing.get("completion")));
                if (in == null || out == null) { continue; }
                result.add(ModelDefinition.builder()
                        .id(id)
                        .name(id)
                        .provider(name())
                        .capabilities(List.of("chat"))
                        .inputUnitPrice(in)
                        .outputUnitPrice(out)
                        .currency("USD")
                        .build());
            } catch (Exception ignored) {
                // 单条脏数据不影响整体
            }
        }
        return result;
    }

    /**
     * 将"美元/Token"换算为"美元/百万 Token"。
     *
     * @param perToken 每 Token 美元价
     * @return 每百万 Token 美元价，无法解析返回 null
     */
    private BigDecimal perMillion(String perToken) {
        try {
            return new BigDecimal(perToken).multiply(PER_MILLION).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }
}
