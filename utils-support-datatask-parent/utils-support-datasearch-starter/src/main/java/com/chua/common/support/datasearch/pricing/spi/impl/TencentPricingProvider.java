package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * 腾讯混元系列模型定价提供者。
 *
 * <p>包含混元-Pro、混元-Lite 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("tencent")
public class TencentPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("hunyuan-pro")
                        .name("混元 Pro")
                        .provider("tencent")
                        .description("腾讯旗舰大模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.0006"))
                        .outputUnitPrice(new BigDecimal("0.0024"))
                        .currency("CNY")
                        .build(),
                ModelDefinition.builder()
                        .id("hunyuan-lite")
                        .name("混元 Lite")
                        .provider("tencent")
                        .description("腾讯轻量模型")
                        .capabilities(List.of("chat"))
                        .inputUnitPrice(new BigDecimal("0.0001"))
                        .outputUnitPrice(new BigDecimal("0.0003"))
                        .currency("CNY")
                        .build()
        );
    }
}
