package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Amazon Bedrock 系列模型定价提供者。
 *
 * <p>包含 Claude、Llama 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("amazon")
public class AmazonPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("anthropic.claude-3-5-sonnet")
                        .name("Claude 3.5 Sonnet (Bedrock)")
                        .provider("amazon")
                        .description("Amazon Bedrock Anthropic Claude 模型")
                        .capabilities(List.of("chat", "vision", "tools"))
                        .inputUnitPrice(new BigDecimal("0.003"))
                        .outputUnitPrice(new BigDecimal("0.015"))
                        .currency("USD")
                        .build(),
                ModelDefinition.builder()
                        .id("meta.llama3-70b-instruct")
                        .name("Llama 3 70B (Bedrock)")
                        .provider("amazon")
                        .description("Amazon Bedrock Meta Llama 3 模型")
                        .capabilities(List.of("chat"))
                        .inputUnitPrice(new BigDecimal("0.0007"))
                        .outputUnitPrice(new BigDecimal("0.0011"))
                        .currency("USD")
                        .build()
        );
    }
}
