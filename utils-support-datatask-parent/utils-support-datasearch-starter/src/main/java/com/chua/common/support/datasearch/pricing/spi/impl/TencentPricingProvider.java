package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

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
}
