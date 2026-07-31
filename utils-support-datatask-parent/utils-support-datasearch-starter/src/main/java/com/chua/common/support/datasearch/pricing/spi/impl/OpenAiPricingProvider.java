package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * OpenAI 系列模型定价提供者。
 *
 * <p>包含 GPT-4o、GPT-4o-mini、o1、o3 等模型定价。</p>
 *
 * <author>CH</author>
 * @since 4.0.0.42
 */
@Spi("openai")
public class OpenAiPricingProvider extends AbstractPricingProvider {

    @Override
    protected List<ModelDefinition> fetchOnlinePricing() {
        return readClasspathPricing();
    }
}
