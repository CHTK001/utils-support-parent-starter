package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * 讯飞星火系列模型定价提供者。
 *
 * <p>包含 Spark-Pro、Spark-Lite 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("xunfei")
public class XunfeiPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> fetchOnlinePricing() {
        return readClasspathPricing();
    }
}
