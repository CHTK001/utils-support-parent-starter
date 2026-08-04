package com.chua.common.support.ai.chat.pricing;

import com.chua.common.support.ai.chat.ModelDefinition;
import org.jspecify.annotations.NullUnmarked;

/**
 * 模型定价提供者扩展接口，用于从各厂商定价源获取模型单价。
 *
 * <p>由 datasearch-starter 实现，通过 ServiceProvider 加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface ModelPricingProvider {

    /**
     * 获取指定厂商和模型的定价信息。
     *
     * @param provider 厂商名称（如 openai, deepseek）
     * @param model    模型名称
     * @return 定价定义，若未找到返回 null
     */
    ModelDefinition getModelPricing(String provider, String model);
}
