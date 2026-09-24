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
 * <p>模型标识除原样命中外，还回退到<b>折叠匹配</b>：剥掉厂商前缀、大小写与所有分隔符
 * （{@code -}、{@code _}、{@code .}、{@code :}）后再比对，因此本机会话里的
 * {@code GLM-5.3-Flash}、{@code glm5.3-flash}、{@code z-ai/glm-5.3-flash}
 * 都能对上目录中的 {@code glm-5-3-flash}。免费档写法一并归一：目录侧的
 * {@code 名称:free} 与各家 CLI 的 {@code 名称-free}（常再省掉版本日期段）视为同一档，
 * 命中后按目录里的 0 价出账。</p>
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
     * 免费档标记：折叠后模型标识的结尾
    */
    private static final String FREE_MARK = "free";

    /**
     * 定价索引：模型 标识 -> 合并后的模型定义（随数据源内容签名变化而重建）
    */
    private volatile Map<String, ModelDefinition> index;

    /**
     * 折叠索引：折叠后的模型标识 -> 合并后的模型定义（与 {@link #index} 同批重建）
    */
    private volatile Map<String, ModelDefinition> foldedIndex = Collections.emptyMap();

    /**
     * {@link #foldedIndex} 中全部免费档键（与 {@link #index} 同批重建）
    */
    private volatile List<String> freeFoldedKeys = Collections.emptyList();

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
        ModelDefinition hit = findPricing(model);
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
     * 按模型标识取定价：原样命中优先，其次折叠匹配，最后解析免费档写法。
     *
     * @param model 模型 标识
     * @return 合并后的模型定义，三级都未命中时返回 空
     */
    private ModelDefinition findPricing(String model) {
        ModelDefinition exact = loadIndex().get(model);
        if (exact != null) {
            return exact;
        }
        Map<String, ModelDefinition> folded = foldedIndex;
        String key = fold(model);
        ModelDefinition foldedHit = key.isEmpty() ? null : folded.get(key);
        return foldedHit == null ? findFreeVariant(folded, key) : foldedHit;
    }

    /**
     * 解析 {@code 名称-free} 这类免费档写法。
     *
     * <p>目录侧写成 {@code 名称:free}，且名称里常带版本日期段（如
     * {@code deepseek-v4-flash-0731:free}），而各家 CLI 记的是 {@code deepseek-v4-flash-free}，
     * 折叠后仍差一段日期，等值比对必然落空。这里只在<b>同名的付费档也存在</b>时才回查
     * 该前缀下的免费档，避免 {@code glm-free} 这类残缺名称撞到 {@code glm-5.3-flash:free}；
     * 多个免费变体取最短键，即版本段最少、最接近请求名的那一个。</p>
     *
     * @param folded 折叠索引
     * @param key    请求标识的折叠值
     * @return 免费档定价，不满足回查条件时返回 空
     */
    private ModelDefinition findFreeVariant(Map<String, ModelDefinition> folded, String key) {
        if (key.length() <= FREE_MARK.length() || !key.endsWith(FREE_MARK)) {
            return null;
        }
        String stem = key.substring(0, key.length() - FREE_MARK.length());
        if (!folded.containsKey(stem)) {
            return null;
        }
        String best = null;
        for (String candidate : freeFoldedKeys) {
            if (candidate.length() > stem.length() && candidate.startsWith(stem)
                    && (best == null || candidate.length() < best.length())) {
                best = candidate;
            }
        }
        return best == null ? null : folded.get(best);
    }

    /**
     * 折叠模型标识：剥掉厂商前缀与所有分隔符，只留小写字母数字。
     *
     * @param model 模型 标识
     * @return 折叠值；只剩分隔符时返回空串
     */
    private static String fold(String model) {
        String name = model;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                builder.append((char) (ch + ('a' - 'A')));
            }
        }
        return builder.toString();
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
            Map<String, ModelDefinition> folded = new LinkedHashMap<>();
            for (ModelMetricsProvider metricsProvider : sources()) {
                for (ModelDefinition md : safeMetrics(metricsProvider)) {
                    if (md.getId() == null) {
                        continue;
                    }
                    map.put(md.getId(), merge(map.get(md.getId()), md));
                    String key = fold(md.getId());
                    if (!key.isEmpty()) {
                        folded.put(key, merge(folded.get(key), md));
                    }
                }
            }
            List<String> freeKeys = new ArrayList<>(16);
            for (String key : folded.keySet()) {
                if (key.endsWith(FREE_MARK)) {
                    freeKeys.add(key);
                }
            }
            log.info("[datasearch-pricing] 模型定价索引构建完成: {} 个模型, 折叠后 {} 个键, 免费档 {} 个",
                    map.size(), folded.size(), freeKeys.size());
            index = map;
            foldedIndex = folded;
            freeFoldedKeys = freeKeys;
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
        fillIfNull(target::getHleScore, src.getHleScore(), target::setHleScore);
        fillIfNull(target::getOmniscienceIndex, src.getOmniscienceIndex(), target::setOmniscienceIndex);
        fillIfNull(target::getGpqaScore, src.getGpqaScore(), target::setGpqaScore);
        fillIfNull(target::getAime25Score, src.getAime25Score(), target::setAime25Score);
        fillIfNull(target::getLivecodebenchScore, src.getLivecodebenchScore(), target::setLivecodebenchScore);
        fillIfNull(target::getScicodeScore, src.getScicodeScore(), target::setScicodeScore);
        fillIfNull(target::getIfbenchScore, src.getIfbenchScore(), target::setIfbenchScore);
        fillIfNull(target::getCritptScore, src.getCritptScore(), target::setCritptScore);
        fillIfNull(target::getMmmuProScore, src.getMmmuProScore(), target::setMmmuProScore);
        fillIfNull(target::getTau2Score, src.getTau2Score(), target::setTau2Score);
        fillIfNull(target::getTauBankingScore, src.getTauBankingScore(), target::setTauBankingScore);
        fillIfNull(target::getLcrScore, src.getLcrScore(), target::setLcrScore);
        fillIfNull(target::getTerminalbenchHardScore, src.getTerminalbenchHardScore(), target::setTerminalbenchHardScore);
        fillIfNull(target::getTerminalBench21Score, src.getTerminalBench21Score(), target::setTerminalBench21Score);
        fillIfNull(target::getTerminalBench40Score, src.getTerminalBench40Score(), target::setTerminalBench40Score);
        fillIfNull(target::getAutomationBenchScore, src.getAutomationBenchScore(), target::setAutomationBenchScore);
        fillIfNull(target::getGdpvalNormalizedScore, src.getGdpvalNormalizedScore(), target::setGdpvalNormalizedScore);
        fillIfNull(target::getBriefcaseScore, src.getBriefcaseScore(), target::setBriefcaseScore);
        fillIfNull(target::getHarveyLabScore, src.getHarveyLabScore(), target::setHarveyLabScore);
        fillIfNull(target::getApexAgentsScore, src.getApexAgentsScore(), target::setApexAgentsScore);
        fillIfNull(target::getItbenchSreScore, src.getItbenchSreScore(), target::setItbenchSreScore);
        fillIfNull(target::getOmniscienceAccuracy, src.getOmniscienceAccuracy(), target::setOmniscienceAccuracy);
        fillIfNull(target::getOmniscienceNonHallucination, src.getOmniscienceNonHallucination(), target::setOmniscienceNonHallucination);
        fillIfNull(target::getElo, src.getElo(), target::setElo);
        fillIfNull(target::getCostPerTask, src.getCostPerTask(), target::setCostPerTask);
        fillIfNull(target::getJsonMode, src.getJsonMode(), target::setJsonMode);
        fillIfNull(target::getOpenaiCompatible, src.getOpenaiCompatible(), target::setOpenaiCompatible);
        fillIfNull(target::getIntelligenceIndexEstimated, src.getIntelligenceIndexEstimated(), target::setIntelligenceIndexEstimated);
        fillIfNull(target::getOutputSpeedTokensPerSecond, src.getOutputSpeedTokensPerSecond(), target::setOutputSpeedTokensPerSecond);
        fillIfNull(target::getLatencyFirstTokenSeconds, src.getLatencyFirstTokenSeconds(), target::setLatencyFirstTokenSeconds);
        fillIfNull(target::getContextWindowTokens, src.getContextWindowTokens(), target::setContextWindowTokens);
        fillIfNull(target::getActiveParams, src.getActiveParams(), target::setActiveParams);
        mergeBoolean(target::getReasoning, src.getReasoning(), target::setReasoning);
        mergeBoolean(target::getImageInput, src.getImageInput(), target::setImageInput);
        mergeBoolean(target::getWebSearch, src.getWebSearch(), target::setWebSearch);
        mergeBoolean(target::getFunctionCalling, src.getFunctionCalling(), target::setFunctionCalling);
        fillIfNull(target::getEndToEndResponseTimeSeconds, src.getEndToEndResponseTimeSeconds(), target::setEndToEndResponseTimeSeconds);
        fillIfNull(target::getInternalReasoningPrice, src.getInternalReasoningPrice(), target::setInternalReasoningPrice);
        fillIfNull(target::getOutputModalities, src.getOutputModalities(), target::setOutputModalities);
        fillIfNull(target::getDeprecated, src.getDeprecated(), target::setDeprecated);
        if (src.getCapabilities() != null && !src.getCapabilities().isEmpty()) {
            target.setCapabilities(unionCapabilities(target.getCapabilities(), src.getCapabilities()));
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

    /**
     * 合并能力标签列表：目标缺失时直接采用源列表，否则取并集（保持原顺序并去重）。
     *
     * <p>数据源各自只声明已知的能力（如 Artificial Analysis 声明 深度思考、
     * 打开router 声明 图片输入/函数调用），首个源优先时会把后续源的标签丢掉，
     * 这里按并集补齐。</p>
     *
     * @param target 已合并的能力标签列表，可为 空
     * @param src    当前数据源的能力标签列表
     * @return 合并后的能力标签列表
     */
    private List<String> unionCapabilities(List<String> target, List<String> src) {
        if (target == null || target.isEmpty()) {
            return new ArrayList<>(src);
        }
        List<String> merged = new ArrayList<>(target);
        for (String capability : src) {
            if (capability != null && !merged.contains(capability)) {
                merged.add(capability);
            }
        }
        return merged;
    }
}
