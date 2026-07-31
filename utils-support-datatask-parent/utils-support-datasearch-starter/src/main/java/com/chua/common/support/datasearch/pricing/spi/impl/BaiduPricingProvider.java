package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * 百度文心一言系列模型定价提供者。
 *
 * <p>包含 ERNIE-4、ERNIE-Speed 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("baidu")
public class BaiduPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("ernie-4")
                        .name("ERNIE 4")
                        .provider("baidu")
                        .description("百度旗舰大模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.0008"))
                        .outputUnitPrice(new BigDecimal("0.002"))
                        .currency("CNY")
                        .build(),
                ModelDefinition.builder()
                        .id("ernie-speed")
                        .name("ERNIE Speed")
                        .provider("baidu")
                        .description("百度轻量快速模型")
                        .capabilities(List.of("chat"))
                        .inputUnitPrice(new BigDecimal("0.0001"))
                        .outputUnitPrice(new BigDecimal("0.0002"))
                        .currency("CNY")
                        .build()
        );
    }
}
