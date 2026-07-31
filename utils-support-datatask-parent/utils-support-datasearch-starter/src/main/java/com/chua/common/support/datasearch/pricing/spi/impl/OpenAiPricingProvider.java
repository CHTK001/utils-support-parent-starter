package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * OpenAI 系列模型定价提供者。
 *
 * <p>包含 GPT-4o、GPT-4o-mini、o1、o3 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("openai")
public class OpenAiPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("gpt-4o")
                        .name("GPT-4o")
                        .provider("openai")
                        .description("OpenAI 最强多模态模型")
                        .capabilities(List.of("chat", "vision", "tools"))
                        .inputUnitPrice(new BigDecimal("0.0025"))
                        .outputUnitPrice(new BigDecimal("0.01"))
                        .currency("USD")
                        .build(),
                ModelDefinition.builder()
                        .id("gpt-4o-mini")
                        .name("GPT-4o Mini")
                        .provider("openai")
                        .description("OpenAI 轻量多模态模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.00015"))
                        .outputUnitPrice(new BigDecimal("0.0006"))
                        .currency("USD")
                        .build(),
                ModelDefinition.builder()
                        .id("o1")
                        .name("o1")
                        .provider("openai")
                        .description("OpenAI 推理模型")
                        .capabilities(List.of("chat", "reasoning"))
                        .inputUnitPrice(new BigDecimal("0.015"))
                        .outputUnitPrice(new BigDecimal("0.06"))
                        .currency("USD")
                        .build(),
                ModelDefinition.builder()
                        .id("o3")
                        .name("o3")
                        .provider("openai")
                        .description("OpenAI 最新推理模型")
                        .capabilities(List.of("chat", "reasoning"))
                        .inputUnitPrice(new BigDecimal("0.015"))
                        .outputUnitPrice(new BigDecimal("0.06"))
                        .currency("USD")
                        .build()
        );
    }
}
