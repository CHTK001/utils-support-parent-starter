package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.pricing.ModelPricingProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * DataSearch 模型定价提供者实现。
 *
 * <p>通过 ServiceProvider 加载各厂商 PricingProvider 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("datasearch")
public class DataSearchModelPricingProvider implements ModelPricingProvider {

    @Override
    /** 获取ModelPricing */
    public ModelDefinition getModelPricing(String provider, String model) {
        if (provider == null || model == null) {
            return null;
        }
        try {
            PricingProvider pricingProvider = ServiceProvider.of(ModelMetricsProvider.class).getExtension(provider);
            if (pricingProvider == null) {
                return null;
            }
            List<ModelDefinition> pricing = pricingProvider.getPricing();
            if (pricing == null || pricing.isEmpty()) {
                return null;
            }
            for (ModelDefinition md : pricing) {
                if (model.equals(md.getId())) {
                    return md;
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }
}
