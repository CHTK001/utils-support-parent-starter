package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * 字节豆包系列模型定价提供者。
 *
 * <p>包含豆包-Pro、豆包-Lite 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("doubao")
public class DoubaoPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("doubao-pro")
                        .name("豆包 Pro")
                        .provider("doubao")
                        .description("字节跳动旗舰对话模型")
                        .capabilities(List.of("chat"))
                        .inputUnitPrice(new BigDecimal("0.0005"))
                        .outputUnitPrice(new BigDecimal("0.0015"))
                        .currency("CNY")
                        .build(),
                ModelDefinition.builder()
                        .id("doubao-lite")
                        .name("豆包 Lite")
                        .provider("doubao")
                        .description("字节跳动轻量模型")
                        .capabilities(List.of("chat"))
                        .inputUnitPrice(new BigDecimal("0.0001"))
                        .outputUnitPrice(new BigDecimal("0.0003"))
                        .currency("CNY")
                        .build()
        );
    }
}
