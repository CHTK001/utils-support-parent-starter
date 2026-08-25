package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.pricing.ModelPricingProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * DataSearch 模型定价桥接实现。
 *
 * <p>通过 ServiceProvider 加载各数据源的 {@link ModelMetricsProvider} 实现，
 * 将其指标列表适配为 AI 对话侧的 ModelPricingProvider 查询。</p>
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
            ModelMetricsProvider metricsProvider =
                    ServiceProvider.of(ModelMetricsProvider.class).getExtension(provider);
            if (metricsProvider == null) {
                return null;
            }
            List<ModelDefinition> metrics = metricsProvider.getMetrics();
            if (metrics == null || metrics.isEmpty()) {
                return null;
            }
            for (ModelDefinition md : metrics) {
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
