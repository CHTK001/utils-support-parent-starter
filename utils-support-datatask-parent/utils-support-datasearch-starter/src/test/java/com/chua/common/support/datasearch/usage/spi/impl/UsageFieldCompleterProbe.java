package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.pricing.ModelPricingProvider;
import com.chua.common.support.datasearch.pricing.PricingSyncer;
import com.chua.common.support.datasearch.pricing.spi.ModelMetricsProvider;
import com.chua.common.support.datasearch.usage.spi.QoderModelCatalog;
import com.chua.common.support.datasearch.usage.spi.ReactiveUsageStreams;
import com.chua.common.support.datasearch.usage.spi.UsageFieldCompleter;
import com.chua.common.support.datasearch.usage.spi.UsageParser;
import com.chua.common.support.spi.ServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link UsageFieldCompleter} 的真实链路验证探针。
 *
 * <p>三段式：先确认定价 SPI 在真实注册路径下能取到数据（含联网同步），
 * 再用已知单价的桩定价源逐条核对补全算术，最后用本机真实会话数据跑通解析到补全的整链。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class UsageFieldCompleterProbe {

    /**
     * 当前生效的桩定价记录，桩源按它应答
     */
    private static ModelDefinition stubPricing;

    /**
     * 桩定价源是否已注册（注册一次后靠切换 {@code stubPricing} 换价格表，避免重复注册同名源）
     */
    private static boolean stubRegistered;

    /**
     * 执行探针
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        probeRealPricingSource();
        probeArithmeticWithStub();
        probeReasoningPricingFallback();
        probeRealLocalData();
        probePricingSyncerFacade();
        probeQoderTierCodes();
    }

    /**
     * 第四段：定价同步门面。spring 侧以 {@code PricingSyncer.loadAll(null)} 取全量定价，
     * loader 传 空 属正常调用方式，因此门面必须返回非空；否则调用方会按"无数据"清空定价表。
     */
    private static void probePricingSyncerFacade() {
        section("4. PricingSyncer.loadAll(null) 门面（spring 落库路径）");
        List<ModelDefinition> all = PricingSyncer.loadAll(null);
        System.out.println("loadAll(null) 条数 = " + all.size());
        int priced = 0;
        ModelDefinition sample = null;
        for (ModelDefinition md : all) {
            if (md.getInputUnitPrice() != null || md.getOutputUnitPrice() != null) {
                priced++;
                if (sample == null && md.getInputUnitPrice() != null && md.getOutputUnitPrice() != null) {
                    sample = md;
                }
            }
        }
        System.out.println("带价条数 = " + priced + " / " + all.size());
        if (sample == null) {
            System.out.println("!! 无可抽样的完整价格记录");
            return;
        }
        BigDecimal perThousand = BigDecimal.valueOf(1000L);
        System.out.printf("  抽样 %-32s 百万价 %s / %s → 落库千价 %s / %s (%s)%n",
                sample.getProvider() + "/" + sample.getId(),
                plain(sample.getInputUnitPrice()), plain(sample.getOutputUnitPrice()),
                plain(sample.getInputUnitPrice().divide(perThousand, 10, RoundingMode.HALF_UP)),
                plain(sample.getOutputUnitPrice().divide(perThousand, 10, RoundingMode.HALF_UP)),
                sample.getCurrency());
        System.out.println("syncAllFromOnline(null) 厂商数 = " + PricingSyncer.syncAllFromOnline(null));
        System.out.println("syncFromOnline(null, openrouter) 厂商数 = "
                + PricingSyncer.syncFromOnline(null, "openrouter"));
        System.out.println("syncFromOnline(null, 未注册厂商) 厂商数 = "
                + PricingSyncer.syncFromOnline(null, "no-such-source"));
    }

    /**
     * 第五段：Qoder 档位码还原。本机转录的 message.model 全部是档位码，
     * 需还原成与定价目录对齐的模型标识；计费档位与未收录码必须保持原值。
     */
    private static void probeQoderTierCodes() {
        section("5. Qoder 档位码还原");
        for (String code : List.of("qfmodel", "qmodel_38max", "qmodel_latest", "gfmodel",
                "kmodel", "dmodel", "auto", "lite", "ultimate", "no-such-tier")) {
            System.out.printf("  %-14s -> %s%n", code, QoderModelCatalog.resolve(code));
        }
    }

    /**
     * 第一段：真实定价源。确认 ServiceProvider 能取到 datasearch 定价实现，
     * 并打印同步前后的索引规模与真实单价（口径应为 币种 / 百万 Token）。
     */
    private static void probeRealPricingSource() {
        section("1. 真实定价 SPI");
        Map<String, ModelPricingProvider> providers =
                ServiceProvider.of(ModelPricingProvider.class).list();
        System.out.println("ModelPricingProvider 注册项 = " + providers.keySet());
        ModelPricingProvider datasearch = null;
        for (ModelPricingProvider candidate : providers.values()) {
            datasearch = candidate;
            break;
        }
        if (datasearch == null) {
            System.out.println("!! 未取到 datasearch 定价实现，后续补全不会生效");
            return;
        }
        System.out.println("同步前索引规模 = " + indexedModels(datasearch));
        Map<String, ModelMetricsProvider> metrics =
                ServiceProvider.of(ModelMetricsProvider.class).list();
        System.out.println("ModelMetricsProvider 注册项 = " + metrics.keySet());
        for (Map.Entry<String, ModelMetricsProvider> entry : metrics.entrySet()) {
            long start = System.currentTimeMillis();
            int synced = 0;
            String error = "无";
            try {
                entry.getValue().syncFromOnline();
                synced = entry.getValue().getMetrics().size();
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            System.out.printf("  %-20s 同步=%d 条 耗时=%dms 失败原因=%s%n",
                    entry.getKey(), synced, System.currentTimeMillis() - start, error);
        }
        System.out.println("同步后索引规模 = " + indexedModels(datasearch));
        for (String model : List.of("gpt-4o", "claude-sonnet-4-20250514", "deepseek-chat",
                "kimi-k2", "deepseek-v4-flash-0731:free")) {
            ModelDefinition definition = datasearch.getModelPricing("openrouter", model);
            System.out.printf("  %-32s -> %s%n", model, definition == null ? "查无"
                    : plain(definition.getInputUnitPrice()) + " / " + plain(definition.getOutputUnitPrice())
                    + " " + definition.getCurrency() + "/百万Token");
        }
    }

    /**
     * 以探针模型集合是否查到带价记录，给出索引可用性的下限估计。
     *
     * @param provider 定价实现
     * @return 命中情况摘要
     */
    private static String indexedModels(ModelPricingProvider provider) {
        List<String> probes = List.of("gpt-4o", "gpt-4o-mini", "o1", "o3",
                "claude-sonnet-4-20250514", "claude-3-5-haiku-20241022",
                "deepseek-chat", "deepseek-reasoner", "kimi-k2",
                "deepseek-v4-flash-0731:free");
        List<String> hit = new ArrayList<>();
        for (String model : probes) {
            if (provider.getModelPricing(null, model) != null) {
                hit.add(model);
            }
        }
        return hit.size() + "/" + probes.size() + " 命中 " + hit;
    }

    /**
     * 第二段：补全算术。用已知单价的桩源覆盖各种输入组合，逐条核对期望值。
     */
    private static void probeArithmeticWithStub() {
        section("2. 补全算术（桩定价：输入 2、输出 10、缓存命中 0.5、推理 10，USD/百万Token）");
        ModelDefinition pricing = ModelDefinition.builder()
                .id("stub-model")
                .provider("stub")
                .inputUnitPrice(new BigDecimal("2"))
                .outputUnitPrice(new BigDecimal("10"))
                .cacheHitPrice(new BigDecimal("0.5"))
                .internalReasoningPrice(new BigDecimal("10"))
                .currency("USD")
                .build();
        stubMode(pricing, () -> {
            check("基础：仅输入输出", AiUsage.builder().model("stub-model")
                    .inputTokens(1_000_000).outputTokens(100_000).build());
            check("含缓存命中", AiUsage.builder().model("stub-model")
                    .inputTokens(1_000_000).cacheTokens(600_000).outputTokens(100_000).build());
            check("命中超过输入(口径异常)", AiUsage.builder().model("stub-model")
                    .inputTokens(100_000).cacheTokens(600_000).outputTokens(0).build());
            check("含推理 Token", AiUsage.builder().model("stub-model")
                    .inputTokens(100_000).outputTokens(50_000).reasoningTokens(50_000).build());
            check("totalTokens 缺失", AiUsage.builder().model("stub-model")
                    .inputTokens(300).outputTokens(200).build());
            check("上游已给 totalCost", AiUsage.builder().model("stub-model")
                    .inputTokens(1_000_000).outputTokens(100_000)
                    .totalCost(new BigDecimal("0.123456")).build());
            check("credits 出账不贴外币单价", AiUsage.builder().model("stub-model")
                    .currency("CREDITS").totalCost(new BigDecimal("0.0821"))
                    .inputTokens(1_000_000).outputTokens(100_000).build());
            check("定价表查无此模型", AiUsage.builder().model("no-such-model")
                    .inputTokens(1_000_000).outputTokens(100_000).build());
            check("model 为空", AiUsage.builder()
                    .inputTokens(1_000_000).outputTokens(100_000).build());
            check("Token 全空", AiUsage.builder().model("stub-model").build());
        });
    }

    /**
     * 第二·五段：推理量计价兜底。
     *
     * <p>解析器已按契约把推理令牌从 {@code outputTokens} 中净出，而 OpenRouter 实测 447 个模型里
     * 仅 31 个带 {@code internal_reasoning}，因此缺推理单价时必须退回输出单价，
     * 否则净出的那段令牌会在账单里凭空消失。</p>
     */
    private static void probeReasoningPricingFallback() {
        section("2b. 推理量计价兜底（桩定价：输入 2、输出 10、缓存命中 0.5、无推理单价）");
        ModelDefinition pricing = ModelDefinition.builder()
                .id("stub-model")
                .provider("stub")
                .inputUnitPrice(new BigDecimal("2"))
                .outputUnitPrice(new BigDecimal("10"))
                .cacheHitPrice(new BigDecimal("0.5"))
                .currency("USD")
                .build();
        stubMode(pricing, () -> {
            check("净出后无推理单价(应=1.00)", AiUsage.builder().model("stub-model")
                    .inputTokens(100_000).outputTokens(50_000).reasoningTokens(50_000).build());
            check("透传超集(会收两遍=1.50)", AiUsage.builder().model("stub-model")
                    .inputTokens(100_000).outputTokens(100_000).reasoningTokens(50_000).build());
        });
    }

    /**
     * 第三段：本机真实会话数据整链跑通，并按模型给出补全覆盖明细。
     */
    private static void probeRealLocalData() {
        runRealParser("ClaudeCodeUsageParser", new ClaudeCodeUsageParser());
        runRealParser("QoderUsageParser", new QoderUsageParser());
    }

    /**
     * 用真实本地会话数据跑通"解析 -> 统一聚合流 -> 字段补全"，打印按模型覆盖明细。
     *
     * @param title  展示名
     * @param parser 被测解析器
     */
    private static void runRealParser(String title, UsageParser parser) {
        section("3. 真实本地数据（" + title + " -> 统一聚合流）");
        List<UsageParser> parsers = List.of(parser);
        long start = System.currentTimeMillis();
        Map<String, int[]> perModel = new java.util.LinkedHashMap<>();
        Map<String, AiUsage> first = new java.util.LinkedHashMap<>();
        int[] total = new int[2];
        ReactiveUsageStreams.concat(parsers).toStream().forEach(usage -> {
            total[0]++;
            String model = usage.getModel() == null ? "<null>" : usage.getModel();
            int[] stat = perModel.computeIfAbsent(model, k -> new int[2]);
            stat[0]++;
            if (usage.getInputUnitPrice() != null || usage.getOutputUnitPrice() != null) {
                stat[1]++;
                total[1]++;
            }
            first.putIfAbsent(model, usage);
        });
        System.out.printf("记录=%d 单价已补=%d 覆盖率=%.1f%% 耗时=%dms%n",
                total[0], total[1], total[0] == 0 ? 0d : total[1] * 100.0 / total[0],
                System.currentTimeMillis() - start);
        System.out.println("模型标识 -> 条数 / 是否补到价 / 补全结果");
        perModel.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(entry -> {
                    AiUsage usage = first.get(entry.getKey());
                    System.out.printf("  %-34s %5d 条  单价=%s/%s 费用=%s %s estimated=%s%n",
                            entry.getKey(), entry.getValue()[0],
                            plain(usage.getInputUnitPrice()), plain(usage.getOutputUnitPrice()),
                            plain(usage.getTotalCost()), usage.getCurrency(), usage.isEstimated());
                });
    }

    /**
     * 打印补全前后的字段对照并核对期望。
     *
     * @param title 用例名
     * @param usage 原始记录
     */
    private static void check(String title, AiUsage usage) {
        String before = fields(usage);
        AiUsage after = UsageFieldCompleter.complete(usage);
        String afterText = fields(after);
        AiUsage again = UsageFieldCompleter.complete(after);
        boolean idempotent = fields(again).equals(afterText);
        System.out.printf("%-22s%n  前 %s%n  后 %s%n  幂等=%s%n", title, before, afterText, idempotent);
    }

    /**
     * 摘要字段拼接。
     *
     * @param usage 用量记录
     * @return 摘要文本
     */
    private static String fields(AiUsage usage) {
        return "total=" + usage.getTotalTokens()
                + " unitIn=" + usage.getInputUnitPrice()
                + " unitOut=" + usage.getOutputUnitPrice()
                + " costIn=" + plain(usage.getInputCost())
                + " costOut=" + plain(usage.getOutputCost())
                + " cost=" + plain(usage.getTotalCost())
                + " " + usage.getCurrency()
                + " est=" + usage.isEstimated();
    }

    /**
     * 去掉末尾零，便于肉眼比对。
     *
     * @param value 金额
     * @return 文本
     */
    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * 临时以桩定价源替换真实源执行。
     *
     * <p>桩源只对 {@code stub-model} 出价，其余模型仍由真实源应答，因此注册后无需回滚
     * （探针为独立进程，且第三段用的是真实模型标识）。桩源按 {@link #stubPricing} 当前值应答，
     * 同一次跑动可以切换多套价格表。</p>
     *
     * @param pricing 本次用例的桩定价记录
     * @param action  执行体
     */
    private static void stubMode(ModelDefinition pricing, Runnable action) {
        if (!stubRegistered) {
            try {
                ServiceProvider.of(ModelPricingProvider.class).register("stub", new ModelPricingProvider() {
                    @Override
                    public ModelDefinition getModelPricing(String provider, String model) {
                        return "stub-model".equals(model) ? stubPricing : null;
                    }
                });
                stubRegistered = true;
            } catch (Exception e) {
                System.out.println("!! 桩定价源注册失败，改用真实源（结果可能受网络影响）: " + e.getMessage());
            }
        }
        stubPricing = pricing;
        action.run();
    }

    /**
     * 分段标题
     *
     * @param title 标题
     */
    private static void section(String title) {
        System.out.println();
        System.out.println("========== " + title + " ==========");
    }

    /**
     * 执行探针
     */
    private UsageFieldCompleterProbe() {
    }
}
