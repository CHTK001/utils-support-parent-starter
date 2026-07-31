package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
 * DeepSeek 系列模型定价提供者。
 *
 * <p>包含 V3、R1 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("deepseek")
public class DeepSeekPricingProvider extends AbstractPricingProvider {
}
