package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Z.AI 系列模型定价提供者。
 *
 * <p>包含 GLM-4 等模型通过 Z.AI 网关的定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zai")
public class ZaiPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("zai-glm-4")
                        .name("Z.AI GLM-4")
                        .provider("zai")
                        .description("Z.AI 平台 GLM-4 模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.0001"))
                        .outputUnitPrice(new BigDecimal("0.0003"))
                        .currency("CNY")
                        .build()
        );
    }
}
