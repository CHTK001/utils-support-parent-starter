package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * DeepSeek 系列模型定价提供者。
 *
 * <p>包含 V3、R1 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("deepseek")
public class DeepSeekPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("deepseek-chat")
                        .name("DeepSeek V3")
                        .provider("deepseek")
                        .description("DeepSeek 通用对话模型")
                        .capabilities(List.of("chat", "tools"))
                        .inputUnitPrice(new BigDecimal("0.00001"))
                        .outputUnitPrice(new BigDecimal("0.00002"))
                        .currency("CNY")
                        .build(),
                ModelDefinition.builder()
                        .id("deepseek-reasoner")
                        .name("DeepSeek R1")
                        .provider("deepseek")
                        .description("DeepSeek 推理模型")
                        .capabilities(List.of("chat", "reasoning"))
                        .inputUnitPrice(new BigDecimal("0.00004"))
                        .outputUnitPrice(new BigDecimal("0.00016"))
                        .currency("CNY")
                        .build()
        );
    }
}
