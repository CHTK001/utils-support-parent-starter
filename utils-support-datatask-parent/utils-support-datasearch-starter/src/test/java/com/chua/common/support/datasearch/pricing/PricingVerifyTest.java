package com.chua.common.support.datasearch.pricing;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.impl.ArtificialAnalysisModelMetricsProvider;

import java.util.List;

/**
 * 临时验证:实际抓取 Artificial Analysis 官网数据,打印主流模型价格用于与权威源核对。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PricingVerifyTest {

    /**
     * 抓取并打印。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        List<ModelDefinition> list = new ArtificialAnalysisModelMetricsProvider().fetchOnlinePricing();
        System.out.println("total models: " + list.size());
        for (ModelDefinition md : list) {
            String id = md.getId();
            if (id == null) {
                continue;
            }
            String lower = id.toLowerCase();
            boolean interesting = lower.contains("deepseek") || lower.contains("v4")
                    || lower.contains("claude-sonnet") || lower.contains("qwen3-8")
                    || lower.contains("gpt-5-") || lower.contains("kimi-k2");
            if (interesting) {
                System.out.printf("%-40s | in=%-10s out=%-10s cacheHit=%-8s ctx=%-9s params=%-6s reason=%-5s effort=%-5s icon=%s%n",
                        id,
                        md.getInputUnitPrice(), md.getOutputUnitPrice(),
                        md.getCacheHitPrice(),
                        md.getContextWindowTokens(), md.getActiveParams(),
                        md.getReasoning(), md.getReasoningEffort(),
                        md.getIconUrl());
            }
        }
    }
}
