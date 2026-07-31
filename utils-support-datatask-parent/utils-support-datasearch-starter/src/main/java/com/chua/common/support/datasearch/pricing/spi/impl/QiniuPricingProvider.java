package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.List;

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

    @Override
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("qwen-max")
                        .name("七牛云 Qwen Max")
                        .provider("qiniu")
                        .description("七牛云中转通义千问旗舰模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.0024"))
                        .outputUnitPrice(new BigDecimal("0.0096"))
                        .currency("CNY")
                        .build()
        );
    }
}
