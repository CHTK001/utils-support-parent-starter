package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.pricing.ModelPricingProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 数据搜索 模型定价桥接实现。
 *
 * <p>通过 ServiceProvider 加载各数据源的 {@link ModelMetricsProvider} 实现，
 * 将全部数据源的指标列表**合并**为一份完整模型定义：首个命中的源作为基础
 * （如 Artificial Analysis 的官方牌价、logo、速度/延迟），后续源仅补齐基础中
 * 缺失的字段（如 打开router 的图片价格、网络检索价格、缓存价格、多模态能力）。</p>
 *
 * <p>因此无论注册多少个数据源，上层按 (provider, model) 查询都能拿到合并后的
 * 完整数据，不再依赖单个实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("datasearch")
public class DataSearchModelPricingProvider implements ModelPricingProvider {

    @Override
    /** 获取模型pricing */
    public ModelDefinition getModelPricing(String provider, String model) {
        if (provider == null || model == null) {
            return null;
        }
        try {
            Map<String, ModelMetricsProvider> providers =
                    ServiceProvider.of(ModelMetricsProvider.class).list();
            if (providers == null || providers.isEmpty()) {
                return null;
            }
            // 官方价格源(artificialanalysis)优先:官方牌价先占位,其余源(openrouter)仅补齐缺失字段
            List<Map.Entry<String, ModelMetricsProvider>> ordered =
                    new ArrayList<>(providers.entrySet());
            ordered.sort(Comparator.comparing(
                    e -> "artificialanalysis".equals(e.getKey()) ? 0 : 1));
            ModelDefinition merged = null;
            for (Map.Entry<String, ModelMetricsProvider> entry : ordered) {
                ModelMetricsProvider metricsProvider = entry.getValue();
                List<ModelDefinition> metrics = metricsProvider.getMetrics();
                if (metrics == null || metrics.isEmpty()) {
                    continue;
                }
                for (ModelDefinition md : metrics) {
                    if (model.equals(md.getId())) {
                        merged = merge(merged, md);
                        break;
                    }
                }
            }
            return merged;
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    /**
     * 合并两条模型定义：Target 为 空 的字段用 src 补齐。
     *
     * @param target 已合并的结果，首次调用为 空
     * @param src    当前数据源的命中记录
     * @return 合并后的结果
     */
    private ModelDefinition merge(ModelDefinition target, ModelDefinition src) {
        if (target == null) {
            target = new ModelDefinition();
        }
        fillIfNull(target::getId, src.getId(), target::setId);
        fillIfNull(target::getName, src.getName(), target::setName);
        fillIfNull(target::getProvider, src.getProvider(), target::setProvider);
        fillIfNull(target::getDescription, src.getDescription(), target::setDescription);
        fillIfNull(target::getDownloadUrl, src.getDownloadUrl(), target::setDownloadUrl);
        fillIfNull(target::getIconUrl, src.getIconUrl(), target::setIconUrl);
        fillIfNull(target::getReasoningEffort, src.getReasoningEffort(), target::setReasoningEffort);
        fillIfNull(target::getInputUnitPrice, src.getInputUnitPrice(), target::setInputUnitPrice);
        fillIfNull(target::getOutputUnitPrice, src.getOutputUnitPrice(), target::setOutputUnitPrice);
        fillIfNull(target::getCacheHitPrice, src.getCacheHitPrice(), target::setCacheHitPrice);
        fillIfNull(target::getCacheWritePrice, src.getCacheWritePrice(), target::setCacheWritePrice);
        fillIfNull(target::getImagePrice, src.getImagePrice(), target::setImagePrice);
        fillIfNull(target::getWebSearchPrice, src.getWebSearchPrice(), target::setWebSearchPrice);
        fillIfNull(target::getCurrency, src.getCurrency(), target::setCurrency);
        fillIfNull(target::getIntelligenceIndex, src.getIntelligenceIndex(), target::setIntelligenceIndex);
        fillIfNull(target::getOutputSpeedTokensPerSecond, src.getOutputSpeedTokensPerSecond(), target::setOutputSpeedTokensPerSecond);
        fillIfNull(target::getLatencyFirstTokenSeconds, src.getLatencyFirstTokenSeconds(), target::setLatencyFirstTokenSeconds);
        fillIfNull(target::getContextWindowTokens, src.getContextWindowTokens(), target::setContextWindowTokens);
        fillIfNull(target::getActiveParams, src.getActiveParams(), target::setActiveParams);
        fillIfNull(target::getReasoning, src.getReasoning(), target::setReasoning);
        mergeBoolean(target::getImageInput, src.getImageInput(), target::setImageInput);
        mergeBoolean(target::getWebSearch, src.getWebSearch(), target::setWebSearch);
        fillIfNull(target::getFunctionCalling, src.getFunctionCalling(), target::setFunctionCalling);
        fillIfNull(target::getEndToEndResponseTimeSeconds, src.getEndToEndResponseTimeSeconds(), target::setEndToEndResponseTimeSeconds);
        fillIfNull(target::getInternalReasoningPrice, src.getInternalReasoningPrice(), target::setInternalReasoningPrice);
        fillIfNull(target::getOutputModalities, src.getOutputModalities(), target::setOutputModalities);
        fillIfNull(target::getDeprecated, src.getDeprecated(), target::setDeprecated);
        if (target.getCapabilities() == null && src.getCapabilities() != null) {
            target.setCapabilities(src.getCapabilities());
        }
        if (target.isCompress() != src.isCompress()) {
            target.setCompress(src.isCompress());
        }
        return target;
    }

    /**
     * 目标值缺失时用源值补齐。
     *
     * @param targetGetter 目标取值
     * @param sourceValue  源值
     * @param targetSetter 目标赋值
     * @param <T>          字段类型
     */
    private <T> void fillIfNull(java.util.function.Supplier<T> targetGetter, T sourceValue,
                                java.util.function.Consumer<T> targetSetter) {
        if (sourceValue == null) {
            return;
        }
        T current = targetGetter.get();
        if (current == null) {
            targetSetter.accept(sourceValue);
        }
    }

    /**
     * 合并能力布尔字段:目标为 空 或为 false 而源为 true 时,用源的 true 覆盖。
     *
     * <p>数据源对不支持的能力可能填 false（按名推断），而另一数据源有明确 true
     * （如 打开router 的 输入_modalities），此时需用 true 覆盖，避免丢失能力标记。</p>
     *
     * @param targetGetter 目标取值
     * @param sourceValue  源值
     * @param targetSetter 目标赋值
     */
    private void mergeBoolean(java.util.function.Supplier<Boolean> targetGetter, Boolean sourceValue,
                              java.util.function.Consumer<Boolean> targetSetter) {
        if (sourceValue == null) {
            return;
        }
        Boolean current = targetGetter.get();
        if (current == null || (!current && sourceValue)) {
            targetSetter.accept(sourceValue);
        }
    }
}
