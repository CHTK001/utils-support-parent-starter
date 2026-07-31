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
    protected List<ModelDefinition> fetchOnlinePricing() {
        return readClasspathPricing();
    }
}
