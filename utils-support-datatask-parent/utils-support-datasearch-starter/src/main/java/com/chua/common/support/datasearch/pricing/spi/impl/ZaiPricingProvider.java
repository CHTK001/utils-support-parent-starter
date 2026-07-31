package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
 * Z.AI 系列模型定价提供者。
 *
 * <p>包含 GLM-4 等模型通过 Z.AI 网关的定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zai")
public class ZaiPricingProvider extends AbstractPricingProvider {
}
