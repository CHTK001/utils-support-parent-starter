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
    protected List<ModelDefinition> getBuiltinPricing() {
        return List.of(
                ModelDefinition.builder()
                        .id("glm-4")
                        .name("GLM-4")
                        .provider("zhipu")
                        .description("智谱旗舰对话模型")
                        .capabilities(List.of("chat", "vision", "tools"))
                        .inputUnitPrice(new BigDecimal("0.0001"))
                        .outputUnitPrice(new BigDecimal("0.0003"))
                        .currency("CNY")
                        .build(),
                ModelDefinition.builder()
                        .id("glm-4-flash")
                        .name("GLM-4-Flash")
                        .provider("zhipu")
                        .description("智谱轻量快速模型")
                        .capabilities(List.of("chat", "vision"))
                        .inputUnitPrice(new BigDecimal("0.00005"))
                        .outputUnitPrice(new BigDecimal("0.00015"))
                        .currency("CNY")
                        .build()
        );
    }
}
