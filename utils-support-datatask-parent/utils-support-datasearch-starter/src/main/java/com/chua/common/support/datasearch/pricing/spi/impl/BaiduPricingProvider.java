package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

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
}
