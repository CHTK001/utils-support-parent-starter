package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * 阿里通义千问系列模型定价提供者。
 *
 * <p>包含 Qwen-Plus、Qwen-Turbo 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("alibaba")
public class AlibabaPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("qwen-max")
                        .name("Qwen Max")
                        .provider("alibaba")
                        .description("阿里通义千问旗舰模型")
                        .capabilities(List.of("chat", "tools"))
                        .inputUnitPrice(new BigDecimal("0.0024"))
                        .outputUnitPrice(new BigDecimal("0.0096"))
                        .currency("CNY")
                        .build(),
                ModelDefinition.builder()
                        .id("qwen-turbo")
                        .name("Qwen Turbo")
                        .provider("alibaba")
                        .description("阿里通义千问轻量模型")
                        .capabilities(List.of("chat"))
                        .inputUnitPrice(new BigDecimal("0.0003"))
                        .outputUnitPrice(new BigDecimal("0.0006"))
                        .currency("CNY")
                        .build()
        );
    }
}
