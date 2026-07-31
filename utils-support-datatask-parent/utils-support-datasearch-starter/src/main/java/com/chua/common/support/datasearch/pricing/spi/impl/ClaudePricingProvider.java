package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Anthropic Claude 系列模型定价提供者。
 *
 * <p>包含 Sonnet、Opus、Haiku 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("claude")
public class ClaudePricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("claude-sonnet-4-20250514")
                        .name("Claude Sonnet 4")
                        .provider("claude")
                        .description("Anthropic 高性能模型")
                        .capabilities(List.of("chat", "vision", "tools"))
                        .inputUnitPrice(new BigDecimal("0.003"))
                        .outputUnitPrice(new BigDecimal("0.015"))
                        .currency("USD")
                        .build(),
                ModelDefinition.builder()
                        .id("claude-3-5-haiku-20241022")
                        .name("Claude 3.5 Haiku")
                        .provider("claude")
                        .description("Anthropic 轻量模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.0008"))
                        .outputUnitPrice(new BigDecimal("0.004"))
                        .currency("USD")
                        .build()
        );
    }
}
