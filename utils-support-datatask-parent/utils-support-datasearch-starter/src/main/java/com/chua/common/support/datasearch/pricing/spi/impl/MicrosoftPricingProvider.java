package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Microsoft Azure OpenAI 系列模型定价提供者。
 *
 * <p>包含 GPT-4o、GPT-4o-mini 等模型定价（与 OpenAI 相同）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("microsoft")
public class MicrosoftPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("gpt-4o")
                        .name("Azure GPT-4o")
                        .provider("microsoft")
                        .description("Microsoft Azure OpenAI 多模态模型")
                        .capabilities(List.of("chat", "vision", "tools"))
                        .inputUnitPrice(new BigDecimal("0.0025"))
                        .outputUnitPrice(new BigDecimal("0.01"))
                        .currency("USD")
                        .build(),
                ModelDefinition.builder()
                        .id("gpt-4o-mini")
                        .name("Azure GPT-4o Mini")
                        .provider("microsoft")
                        .description("Microsoft Azure OpenAI 轻量模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.00015"))
                        .outputUnitPrice(new BigDecimal("0.0006"))
                        .currency("USD")
                        .build()
        );
    }
}
