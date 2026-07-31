package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Together AI 系列模型定价提供者。
 *
 * <p>包含 Llama、Mixtral 等开源模型通过 Together AI 网关的定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("together")
public class TogetherAiPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> fetchOnlinePricing() {
        return readClasspathPricing();
    }
}
