package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * 智谱 AI 系列模型定价提供者。
 *
 * <p>包含 GLM-4、GLM-4-FLASH 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zhipu")
public class ZhipuPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> fetchOnlinePricing() {
        return readClasspathPricing();
    }
}
