package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * 华为盘古系列模型定价提供者。
 *
 * <p>包含盘古-NLP、盘古-Vision 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("huawei")
public class HuaweiPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("pangu-nlp")
                        .name("盘古 NLP")
                        .provider("huawei")
                        .description("华为盘古自然语言模型")
                        .capabilities(List.of("chat", "tools"))
                        .inputUnitPrice(new BigDecimal("0.0004"))
                        .outputUnitPrice(new BigDecimal("0.0012"))
                        .currency("CNY")
                        .build()
        );
    }
}
