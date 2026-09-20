package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.pricing.ModelPricingProvider;
import com.chua.common.support.spi.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AiUsage} 字段补全器 —— 把各 {@link UsageParser} 只解析了原始 Token 计数的记录，
 * 补齐为可直接用于对账与统计的完整记录。
 *
 * <p>补全内容（全部遵循"只填空、不覆盖"，重复调用结果一致）：</p>
 * <ul>
 *   <li>{@code totalTokens} — 输入与输出 Token 之和，供上游漏填时补齐</li>
 *   <li>{@code inputUnitPrice} / {@code outputUnitPrice} — 取自定价表，口径为<b>币种 / 百万 Token</b></li>
 *   <li>{@code inputCost} — 缓存命中部分按 {@code cacheHitPrice} 计价，其余按输入单价计价</li>
 *   <li>{@code outputCost} — 输出单价计价，推理 Token 另按 {@code internalReasoningPrice} 计价</li>
 *   <li>{@code totalCost} — 输入费用与输出费用之和</li>
 *   <li>{@code currency} / {@code estimated} — 费用一旦由定价表推导，即与定价表币种对齐并标记为估算</li>
 * </ul>
 *
 * <p>模型标识按本地会话里的原样字符串匹配定价表；由于 usage 侧的 {@code provider} 记录的是
 * CLI 工具名（如 qoder、claude-编码）而非模型厂商，定价匹配以模型标识为准，厂商仅在
 * {@link ModelPricingProvider} 内部用于区分同名模型。</p>
 *
 * <p>本类直接改写传入的 {@link AiUsage} 实例（{@code AiUsage} 为可变对象），
 * 以避免逐条记录复制全部字段；调用方若需保留原值请自行拷贝。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class UsageFieldCompleter {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(UsageFieldCompleter.class);

    /**
     * 单价口径的 Token 基数（定价表单价为 币种 / 百万 Token）
     */
    private static final long TOKENS_PER_UNIT = 1_000_000L;

    /**
     * 费用计算保留小数位
     */
    private static final int COST_SCALE = 10;

    /**
     * 定价查询缓存容量上限，超出后不再缓存新键，避免长流任务无界增长
     */
    private static final int PRICING_CACHE_LIMIT = 4096;

    /**
     * 定价查询缓存：模型标识 -> 模型定义；未命中缓存 {@link Optional#empty()}，避免重复查表
     */
    private static final Map<String, Optional<ModelDefinition>> PRICING_CACHE = new ConcurrentHashMap<>();

    /**
     * 定价提供者是否已为空的标记：确认无任何定价数据后不再重复走 SPI
     */
    private static volatile boolean pricingUnavailable;

    /**
     * 创建 usage字段补全器 实例
     */
    private UsageFieldCompleter() {
    }

    /**
     * 补全单条用量记录的费用与汇总字段。
     *
     * @param usage 待补全记录，为 空 时直接返回 空
     * @return 同一实例（字段已就地补全）
     */
    public static AiUsage complete(AiUsage usage) {
        if (usage == null) {
            return null;
        }
        completeTotals(usage);
        ModelDefinition pricing = resolvePricing(usage.getProvider(), usage.getModel());
        if (pricing == null || currencyConflict(usage, pricing)) {
            return usage;
        }
        completeUnitPrices(usage, pricing);
        boolean derived = completeCosts(usage, pricing);
        if (derived) {
            completeCurrency(usage, pricing);
            usage.setEstimated(true);
        }
        return usage;
    }

    /**
     * 记录币种与定价表币种是否不同。
     *
     * <p>单价与费用的单位都带定价表币种，而 {@code currency} 是整行金额的唯一标注；
     * 上游按 credits 出账的记录若贴上每百万 Token 的法币单价，同一行的单价与费用
     * 就会分属两种币种，因此这类记录只做汇总字段补全。</p>
     *
     * <p>仅<b>已经带着金额</b>的记录才受此限制：不少解析器会给分文未记的行也盖上币种标签
     * （zcode 曾无条件写 {@code CREDITS}），这类空标签不该把整行的定价补全挡掉。</p>
     *
     * @param usage   用量记录
     * @param pricing 定价表记录
     * @return true 表示记录已带金额且币种不一致，应跳过价格类字段
     */
    private static boolean currencyConflict(AiUsage usage, ModelDefinition pricing) {
        if (usage.getTotalCost() == null && usage.getInputCost() == null && usage.getOutputCost() == null) {
            return false;
        }
        String recordCurrency = usage.getCurrency();
        String pricingCurrency = pricing.getCurrency();
        return recordCurrency != null && !recordCurrency.isBlank()
                && pricingCurrency != null && !pricingCurrency.isBlank()
                && !recordCurrency.equalsIgnoreCase(pricingCurrency);
    }

    /**
     * 以逐条惰性方式补全用量流，不改变流的顺序与背压特性。
     *
     * @param stream 用量记录流
     * @return 补全后的用量记录流
     */
    public static Flux<AiUsage> complete(Flux<AiUsage> stream) {
        return stream == null ? Flux.empty() : stream.map(UsageFieldCompleter::complete);
    }

    /**
     * 清空定价查询缓存。
     *
     * <p>"查无此模型"的负结果同样会被缓存以免逐条重复查表，因此在
     * {@code PricingSyncer} 同步进新价之后需调用本方法，否则此前无价的模型仍按无价处理。</p>
     */
    public static void reset() {
        PRICING_CACHE.clear();
        pricingUnavailable = false;
    }

    /**
     * 补齐总 Token 数（输入与输出之和）。
     *
     * @param usage 待补全记录
     */
    private static void completeTotals(AiUsage usage) {
        if (usage.getTotalTokens() != null) {
            return;
        }
        Integer input = usage.getInputTokens();
        Integer output = usage.getOutputTokens();
        if (input == null && output == null) {
            return;
        }
        usage.setTotalTokens(nullToZero(input) + nullToZero(output));
    }

    /**
     * 补齐输入/输出单价。
     *
     * @param usage   待补全记录
     * @param pricing 定价表记录
     */
    private static void completeUnitPrices(AiUsage usage, ModelDefinition pricing) {
        if (usage.getInputUnitPrice() == null) {
            usage.setInputUnitPrice(usable(pricing.getInputUnitPrice()));
        }
        if (usage.getOutputUnitPrice() == null) {
            usage.setOutputUnitPrice(usable(pricing.getOutputUnitPrice()));
        }
    }

    /**
     * 补齐输入/输出/总费用。
     *
     * <p>上游已给出 {@code totalCost} 的记录视为真实账单值，不回算、不拆分。</p>
     *
     * @param usage   待补全记录
     * @param pricing 定价表记录
     * @return 是否由定价表推导出了费用
     */
    private static boolean completeCosts(AiUsage usage, ModelDefinition pricing) {
        if (usage.getTotalCost() != null) {
            return false;
        }
        BigDecimal inputCost = calcInputCost(usage, pricing);
        BigDecimal outputCost = calcOutputCost(usage, pricing);
        if (inputCost == null && outputCost == null) {
            return false;
        }
        if (usage.getInputCost() == null) {
            usage.setInputCost(inputCost);
        }
        if (usage.getOutputCost() == null) {
            usage.setOutputCost(outputCost);
        }
        usage.setTotalCost(nullToZeroBD(usage.getInputCost()).add(nullToZeroBD(usage.getOutputCost())));
        return true;
    }

    /**
     * 计算输入费用：缓存命中部分按缓存命中价计价，其余按输入单价计价。
     *
     * <p>依赖 {@link UsageParser} 的令牌口径契约：{@code inputTokens} 已包含命中量，
     * 故非缓存输入为 {@code input - cache}。上游若违反口径给出 {@code cache > input}，
     * 命中部分按输入封顶折算，既不退化成全额输入价（丢折扣），也不出现负的计费令牌。</p>
     *
     * @param usage   待补全记录
     * @param pricing 定价表记录
     * @return 输入费用；单价或 Token 数缺失时返回 空
     */
    private static BigDecimal calcInputCost(AiUsage usage, ModelDefinition pricing) {
        Integer input = usage.getInputTokens();
        BigDecimal inputPrice = usable(pricing.getInputUnitPrice());
        if (input == null || input < 0 || inputPrice == null) {
            return null;
        }
        Integer cache = usage.getCacheTokens();
        BigDecimal cacheHitPrice = usable(pricing.getCacheHitPrice());
        if (cache == null || cache <= 0 || cacheHitPrice == null) {
            return priceOf(input, inputPrice);
        }
        int hit = Math.min(cache, input);
        return priceOf(input - hit, inputPrice)
                .add(priceOf(hit, cacheHitPrice));
    }

    /**
     * 计算输出费用：输出 Token 按输出单价计价，推理 Token 按推理单价计价后相加。
     *
     * <p>解析器已把推理量从 {@code outputTokens} 中净出，因此缺少推理单价时必须退回输出单价，
     * 否则净出的那段令牌会从账单里凭空消失。OpenRouter 实测 447 个模型仅 31 个带
     * {@code internal_reasoning}，且其值等于 {@code completion}，退回是常态而非兜底。</p>
     *
     * @param usage   待补全记录
     * @param pricing 定价表记录
     * @return 输出费用；单价与 Token 数不同时具备时返回 空
     */
    private static BigDecimal calcOutputCost(AiUsage usage, ModelDefinition pricing) {
        Integer output = usage.getOutputTokens();
        BigDecimal outputPrice = usable(pricing.getOutputUnitPrice());
        BigDecimal normal = output == null || output < 0 || outputPrice == null
                ? null : priceOf(output, outputPrice);
        Integer reasoning = usage.getReasoningTokens();
        BigDecimal reasoningPrice = usable(pricing.getInternalReasoningPrice());
        if (reasoningPrice == null) {
            reasoningPrice = outputPrice;
        }
        BigDecimal reasoned = reasoning == null || reasoning <= 0 || reasoningPrice == null
                ? null : priceOf(reasoning, reasoningPrice);
        if (normal == null) {
            return reasoned;
        }
        return reasoned == null ? normal : normal.add(reasoned);
    }

    /**
     * 费用币种与定价表对齐（费用按定价表币种算出，标注其它币种会误导对账）。
     *
     * @param usage   待补全记录
     * @param pricing 定价表记录
     */
    private static void completeCurrency(AiUsage usage, ModelDefinition pricing) {
        if (pricing.getCurrency() != null && !pricing.getCurrency().isBlank()) {
            usage.setCurrency(pricing.getCurrency());
        }
    }

    /**
     * 按 币种 / 百万 Token 的单价折算给定 Token 数的费用。
     *
     * @param tokens           Token 数
     * @param pricePerMillion  每百万 Token 单价
     * @return 费用
     */
    private static BigDecimal priceOf(long tokens, BigDecimal pricePerMillion) {
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(TOKENS_PER_UNIT), COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 查询模型定价（带缓存，未命中不重复查表）。
     *
     * @param provider 厂商标识（本地会话场景下为 CLI 工具名，可为 空）
     * @param model    模型标识
     * @return 定价表记录，查无时返回 空
     */
    private static ModelDefinition resolvePricing(String provider, String model) {
        if (model == null || model.isBlank() || pricingUnavailable) {
            return null;
        }
        String key = model.trim();
        Optional<ModelDefinition> cached = PRICING_CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        ModelDefinition found = queryPricing(provider, key);
        if (PRICING_CACHE.size() < PRICING_CACHE_LIMIT) {
            PRICING_CACHE.put(key, Optional.ofNullable(found));
        }
        return found;
    }

    /**
     * 依次以候选模型标识询问全部已注册的定价提供者，任一给出带价记录即采用。
     *
     * @param provider 厂商标识
     * @param model    模型标识
     * @return 定价表记录，全部未命中时返回 空
     */
    private static ModelDefinition queryPricing(String provider, String model) {
        List<ModelPricingProvider> providers;
        try {
            providers = ServiceProvider.of(ModelPricingProvider.class).collect();
        } catch (Exception e) {
            log.debug("[datasearch] 定价提供者加载失败: {}", e.getMessage());
            providers = null;
        }
        if (providers == null || providers.isEmpty()) {
            pricingUnavailable = true;
            log.info("[datasearch] 未发现 ModelPricingProvider 实现, 用量费用字段不补全");
            return null;
        }
        for (String candidate : candidateModels(model)) {
            for (ModelPricingProvider pricingProvider : providers) {
                ModelDefinition definition = query(pricingProvider, provider, candidate);
                if (definition != null) {
                    return definition;
                }
            }
        }
        return null;
    }

    /**
     * 向单个定价提供者查询，异常按未命中处理（不打日志，避免逐条刷屏）。
     *
     * @param pricingProvider 定价提供者
     * @param provider        厂商标识
     * @param model           模型标识
     * @return 带价记录，未命中时返回 空
     */
    private static ModelDefinition query(ModelPricingProvider pricingProvider, String provider, String model) {
        try {
            ModelDefinition definition = pricingProvider.getModelPricing(provider, model);
            return hasPrice(definition) ? definition : null;
        } catch (Exception e) {
            log.debug("[datasearch] 定价提供者[{}]查询[{}]失败: {}",
                    pricingProvider.getClass().getSimpleName(), model, e.getMessage());
            return null;
        }
    }

    /**
     * 生成候选模型标识：原标识优先，别名（小写、剥前缀）次之。
     *
     * @param model 原始模型标识
     * @return 候选标识列表
     */
    private static List<String> candidateModels(String model) {
        List<String> candidates = new ArrayList<>(3);
        candidates.add(model);
        String lower = model.toLowerCase(Locale.ROOT);
        if (!lower.equals(model)) {
            candidates.add(lower);
        }
        String alias = modelAlias(model);
        if (!alias.isEmpty() && !candidates.contains(alias)) {
            candidates.add(alias);
        }
        return candidates;
    }

    /**
     * 定价记录是否携带可用的价格（仅有元数据不足以支撑费用补全）。
     *
     * <p>负数是数据源对"价格不定"条目下发的哨兵，按无价处理；免费档的 0 是有效报价。</p>
     *
     * @param pricing 定价表记录，允许为 空
     * @return true 表示至少具备一项非负的输入/输出单价
     */
    private static boolean hasPrice(ModelDefinition pricing) {
        return pricing != null
                && (usable(pricing.getInputUnitPrice()) != null || usable(pricing.getOutputUnitPrice()) != null);
    }

    /**
     * 取可用单价。
     *
     * @param price 定价表单价
     * @return 非负单价；缺失或负数哨兵时返回 空
     */
    private static BigDecimal usable(BigDecimal price) {
        return price == null || price.signum() < 0 ? null : price;
    }

    /**
     * 派生模型标识的别名：剥掉本地会话常见的 {@code [xxx]} 前缀与 {@code vendor/} 前缀。
     *
     * @param model 原始模型标识
     * @return 别名；无可剥离内容时返回原值
     */
    private static String modelAlias(String model) {
        String alias = model;
        if (alias.startsWith("[")) {
            int end = alias.indexOf(']');
            if (end > 0 && end + 1 < alias.length()) {
                alias = alias.substring(end + 1).trim();
            }
        }
        int slash = alias.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < alias.length()) {
            alias = alias.substring(slash + 1).trim();
        }
        return alias.isEmpty() ? model : alias.toLowerCase(Locale.ROOT);
    }

    /**
     * 空 值按 0 处理。
     *
     * @param value 整型值
     * @return 非 空 值或 0
     */
    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 空 值按零处理。
     *
     * @param value 金额值
     * @return 非 空 值或零
     */
    private static BigDecimal nullToZeroBD(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
