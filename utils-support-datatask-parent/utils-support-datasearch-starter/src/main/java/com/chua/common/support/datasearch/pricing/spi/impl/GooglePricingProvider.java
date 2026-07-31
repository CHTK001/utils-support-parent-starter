package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Google Gemini 系列模型定价提供者。
 *
 * <p>包含 Gemini 2.5 Pro、2.5 Flash 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("google")
public class GooglePricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("gemini-2.5-pro")
                        .name("Gemini 2.5 Pro")
                        .provider("google")
                        .description("Google 最强推理模型")
                        .capabilities(List.of("chat", "reasoning", "vision"))
                        .inputUnitPrice(new BigDecimal("0.00125"))
                        .outputUnitPrice(new BigDecimal("0.005"))
                        .currency("USD")
                        .build(),
                ModelDefinition.builder()
                        .id("gemini-2.5-flash")
                        .name("Gemini 2.5 Flash")
                        .provider("google")
                        .description("Google 快速多模态模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.000075"))
                        .outputUnitPrice(new BigDecimal("0.0003"))
                        .currency("USD")
                        .build()
        );
    }
}
