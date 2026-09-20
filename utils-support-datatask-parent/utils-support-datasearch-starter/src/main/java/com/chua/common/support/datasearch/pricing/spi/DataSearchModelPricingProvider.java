package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.pricing.ModelPricingProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * <p>合并结果按模型 标识 建索引常驻，查询为一次哈希取值；仅当入参厂商与索引命中的
 * 厂商不一致（多厂商同名模型）时才回退一次精确匹配。索引按各数据源返回内容的签名
 * 感知变化，{@code PricingSyncer} 同步进新价后自动重建，无需重启。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("datasearch")
public class DataSearchModelPricingProvider implements ModelPricingProvider {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(DataSearchModelPricingProvider.class);

    /**
     * 定价索引：模型 标识 -> 合并后的模型定义（随数据源内容签名变化而重建）
    */
    private volatile Map<String, ModelDefinition> index;

    /**
     * 构建 {@link #index} 时的数据源内容签名
    */
    private volatile String indexSignature;

    /**
     * 数据源清单快照（仅向 SPI 取一次）
    */
    private volatile List<ModelMetricsProvider> sources;

    @Override
    /**
     * 获取模型pricing
    */
    public ModelDefinition getModelPricing(String provider, String model) {
        if (model == null) {
            return null;
        }
        ModelDefinition hit = loadIndex().get(model);
        if (hit == null) {
            return null;
        }
        // 入参厂商与命中厂商不一致时先按厂商精确匹配；上层传的可能是 SPI 注册名
        // 或 CLI 工具名，此时精确匹配必然落空，仍需保留按模型标识的命中
        if (provider != null && hit.getProvider() != null
                && !provider.equalsIgnoreCase(hit.getProvider())) {
            ModelDefinition scoped = findAcrossSources(provider, model);
            return scoped != null ? scoped : hit;
        }
        return hit;
    }

    /**
     * 按 (provider, model) 在所有数据源中精确匹配并合并。
     *
     * @param provider 厂商标识
     * @param model    模型 标识
     * @return 合并后的模型定义，无命中时返回 空
     */
    private ModelDefinition findAcrossSources(String provider, String model) {
        ModelDefinition merged = null;
        for (ModelMetricsProvider metricsProvider : sources()) {
            for (ModelDefinition md : safeMetrics(metricsProvider)) {
                if (model.equals(md.getId()) && provider.equalsIgnoreCase(md.getProvider())) {
                    merged = merge(merged, md);
                }
            }
        }
        return merged;
    }

    /**
     * 加载 model 索引：数据源内容变化（如 {@code PricingSyncer} 同步进新价）时重建。
     *
     * @return 模型 标识 -> 合并后的模型定义
     */
    private Map<String, ModelDefinition> loadIndex() {
        String signature = signature();
        Map<String, ModelDefinition> cached = index;
        if (cached != null && signature.equals(indexSignature)) {
            return cached;
        }
        synchronized (this) {
            String current = signature();
            if (index != null && current.equals(indexSignature)) {
                return index;
            }
            Map<String, ModelDefinition> map = new LinkedHashMap<>();
            for (ModelMetricsProvider metricsProvider : sources()) {
                for (ModelDefinition md : safeMetrics(metricsProvider)) {
                    if (md.getId() == null) {
                        continue;
                    }
                    map.put(md.getId(), merge(map.get(md.getId()), md));
                }
            }
            log.info("[datasearch-pricing] 模型定价索引构建完成: {} 个模型", map.size());
            index = map;
            indexSignature = current;
            return map;
        }
    }

    /**
     * 数据源内容签名：各源返回列表的身份与条数，用于感知同步后的数据替换。
     *
     * @return 签名串
     */
    private String signature() {
        StringBuilder builder = new StringBuilder(64);
        for (ModelMetricsProvider metricsProvider : sources()) {
            List<ModelDefinition> metrics = safeMetrics(metricsProvider);
            builder.append(metricsProvider.name()).append('#')
                    .append(System.identityHashCode(metrics)).append('#')
                    .append(metrics.size()).append(';');
        }
        return builder.toString();
    }

    /**
     * 数据源清单（仅向 SPI 取一次）。
     *
     * @return 排序后的数据源列表
     */
    private List<ModelMetricsProvider> sources() {
        List<ModelMetricsProvider> cached = sources;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (sources != null) {
                return sources;
            }
            sources = orderedProviders();
            return sources;
        }
    }

    /**
     * 官方价格源（artificialanalysis）优先，保证官方牌价先占位，其余源仅补齐缺失字段。
     *
     * @return 排序后的数据源列表
     */
    private List<ModelMetricsProvider> orderedProviders() {
        Map<String, ModelMetricsProvider> providers;
        try {
            providers = ServiceProvider.of(ModelMetricsProvider.class).list();
        } catch (Exception e) {
            log.debug("[datasearch-pricing] 加载定价数据源失败: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (providers == null || providers.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModelMetricsProvider> ordered = new ArrayList<>(providers.size());
        providers.forEach((key, value) -> ordered.add(value));
        ordered.sort(Comparator.comparing(p -> "artificialanalysis".equals(p.name()) ? 0 : 1));
        return ordered;
    }

    /**
     * 取数据源指标，单个数据源异常不影响其余数据源。
     *
     * @param metricsProvider 数据源
     * @return 模型定义列表，异常时返回空列表
     */
    private List<ModelDefinition> safeMetrics(ModelMetricsProvider metricsProvider) {
        try {
            List<ModelDefinition> metrics = metricsProvider.getMetrics();
            return metrics == null ? Collections.emptyList() : metrics;
        } catch (Exception e) {
            log.debug("[datasearch-pricing] 数据源[{}]取数失败: {}",
                    metricsProvider.name(), e.getMessage());
            return Collections.emptyList();
        }
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
