package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 七牛云系列模型定价提供者。
 *
 * <p>包含七牛云提供的各模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("qiniu")
public class QiniuPricingProvider extends AbstractPricingProvider {
}
