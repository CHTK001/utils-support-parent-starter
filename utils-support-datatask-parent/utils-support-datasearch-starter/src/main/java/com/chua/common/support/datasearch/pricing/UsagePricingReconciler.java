package com.chua.common.support.datasearch.pricing;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.ModelMetricsProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 用量 / 定价对账器 —— 比对「已发生的用量」与「可用的模型指标」，找出计费与能力缺口。
 *
 * <p>排查「用量同步进来但费用为 0」「模型有价却没入库」这类问题时，此前只能靠手工 SQL 事后发现。
 * 本类把对账逻辑收敛为纯计算：调用方把用量条目与数据源喂进来，它产出结构化差异报告，
 * 既可在定时任务 / 接口里复用，也能脱离数据库单测。</p>
 *
 * <p>对账维度：</p>
 * <ul>
 *   <li><b>缺价模型</b>：有用量但所有数据源都查不到价格 —— 这些记录的费用会落到 0</li>
 *   <li><b>零费用用量</b>：外部来源的用量条目本身 totalCost ≤ 0 —— 可能是免费档，也可能是定价缺失</li>
 *   <li><b>闲置定价</b>：有价但从未被任何用量命中 —— 目录冗余，或该模型换过来源名</li>
 *   <li><b>重复定价</b>：同一 (provider, model) 出现多条价格 —— 合并索引会按顺序取首条，需人工消歧</li>
 * </ul>
 *
 * <p>模型匹配与运行时一致：先原样命中，再按折叠键（剥厂商前缀与分隔符、大小写归一）匹配，
 * 因此 {@code GLM-5.3-Flash} 与 {@code z-ai/glm-5.3-flash} 视为同一模型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class UsagePricingReconciler {

    /**
     * 免费档标记：折叠后模型标识的结尾
     */
    private static final String FREE_MARK = "free";

    /**
     * 构造。
     */
    private UsagePricingReconciler() {
    }

    /**
     * 一条用量条目（对账输入）。
     *
     * @param group      数据源分组（如 opencode / qoder）
     * @param provider   厂商标识
     * @param model      模型标识
     * @param totalCost  该条用量记录的费用
     * @param external   是否外部工具来源
     */
    public record UsageEntry(String group, String provider, String model,
                             double totalCost, boolean external) {
    }

    /**
     * 对账报告。
     *
     * @param unpricedModels 缺价模型 → 条数
     * @param zeroCostGroups 零费用用量 → 按数据源分组的条数
     * @param idlePricings  闲置定价 → 厂商:模型
     * @param duplicatePricings 重复定价 → 厂商:模型 → 条数
     * @param totalUsages   参与对账的用量总条数
     */
    public record Report(Map<String, Integer> unpricedModels,
                         Map<String, Integer> zeroCostGroups,
                         List<String> idlePricings,
                         Map<String, Integer> duplicatePricings,
                         int totalUsages) {

        /**
         * 规范构造器：对账结果做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。
         * 三个 Map 均由 {@code TreeMap}（大小写不敏感序）产出，此处改用
         * {@link LinkedHashMap} 快照并 {@link Collections#unmodifiableMap} 包装：
         * 既保留原有的大小写不敏感排序（{@link #render()} 的输出顺序依赖它），
         * 又不使用不保证迭代顺序的 {@code Map.copyOf}。</p>
         *
         * @param unpricedModels 缺价模型 → 条数
         * @param zeroCostGroups 零费用用量 → 按数据源分组的条数
         * @param idlePricings 闲置定价 → 厂商:模型
         * @param duplicatePricings 重复定价 → 厂商:模型 → 条数
         * @param totalUsages 参与对账的用量总条数
         */
        public Report {
            unpricedModels = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(unpricedModels, "unpricedModels 不能为 null")));
            zeroCostGroups = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(zeroCostGroups, "zeroCostGroups 不能为 null")));
            idlePricings = List.copyOf(Objects.requireNonNull(idlePricings, "idlePricings 不能为 null"));
            duplicatePricings = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(duplicatePricings, "duplicatePricings 不能为 null")));
        }

        /**
         * 是否存在需要关注的问题。
         *
         * @return 有缺口返回 true
         */
        public boolean hasIssue() {
            return !unpricedModels.isEmpty() || !zeroCostGroups.isEmpty()
                    || !duplicatePricings.isEmpty();
        }

        /**
         * 渲染为可读文本。
         *
         * @return 多行报告文本
         */
        public String render() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("用量/定价对账报告（用量总条数=").append(totalUsages).append("）\n");
            sb.append("  缺价模型（有量无价，费用会落 0）：").append(unpricedModels.size()).append('\n');
            unpricedModels.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(20)
                    .forEach(e -> sb.append("    - ").append(e.getKey())
                            .append("  x").append(e.getValue()).append('\n'));
            sb.append("  零费用用量（按来源）：\n");
            zeroCostGroups.forEach((g, n) -> sb.append("    - ").append(g).append("  x").append(n).append('\n'));
            sb.append("  闲置定价（有价无量）：").append(idlePricings.size()).append('\n');
            idlePricings.stream().limit(10)
                    .forEach(m -> sb.append("    - ").append(m).append('\n'));
            sb.append("  重复定价（同厂商同模型多条）：").append(duplicatePricings.size()).append('\n');
            duplicatePricings.forEach((m, n) -> sb.append("    - ").append(m).append("  x").append(n).append('\n'));
            return sb.toString();
        }
    }

    /**
     * 对账：比对用量条目与各数据源提供的模型指标。
     *
     * @param usages 用量条目
     * @return 对账报告
     */
    public static Report reconcile(Collection<UsageEntry> usages) {
        return reconcile(usages, loadAllSources());
    }

    /**
     * 对账：显式给定数据源指标（便于单测，避免依赖 SPI 注册）。
     *
     * @param usages   用量条目
     * @param sources  各数据源指标
     * @return 对账报告
     */
    public static Report reconcile(Collection<UsageEntry> usages,
                                   List<List<ModelDefinition>> sources) {
        // 合并索引：模型标识（原样 + 折叠）→ 合并后的模型定义
        Map<String, ModelDefinition> index = buildIndex(sources);
        Map<String, Integer> duplicatePricings = countDuplicates(sources);

        // 记录命中的定价键，用于识别闲置定价
        java.util.Set<String> hitKeys = new java.util.LinkedHashSet<>();
        Map<String, Integer> unpriced = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> zeroCostGroups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        int total = 0;
        for (UsageEntry usage : usages == null ? List.<UsageEntry>of() : usages) {
            if (usage == null || usage.model() == null || usage.model().isBlank()) {
                continue;
            }
            total++;
            String key = providerKey(usage.provider(), usage.model());
            if (usage.external() && usage.totalCost() <= 0D) {
                zeroCostGroups.merge(String.valueOf(usage.group()), 1, Integer::sum);
            }
            ModelDefinition hit = find(index, usage.model());
            if (hit == null || hit.getInputUnitPrice() == null && hit.getOutputUnitPrice() == null) {
                unpriced.merge(key, 1, Integer::sum);
            } else {
                String hitKey = matchKey(index, usage.model());
                if (hitKey != null) {
                    hitKeys.add(hitKey);
                }
            }
        }

        // 闲置定价：原样键里有、但没被任何用量命中；以 厂商:模型 输出
        List<String> idle = new ArrayList<>();
        for (Map.Entry<String, ModelDefinition> entry : index.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("\u0000fold:")) {
                continue;
            }
            ModelDefinition md = entry.getValue();
            // 该模型是否被用量命中（按折叠键判断，覆盖前缀/大小写差异）
            String foldedKey = "\u0000fold:" + fold(key);
            if (!hitKeys.contains(key) && !hitKeys.contains(foldedKey)) {
                idle.add(providerKey(md.getProvider(), md.getId()));
            }
        }
        idle.sort(String::compareToIgnoreCase);
        return new Report(unpriced, zeroCostGroups, idle, duplicatePricings, total);
    }

    /**
     * 加载全部数据源指标。
     *
     * @return 各数据源指标列表
     */
    private static List<List<ModelDefinition>> loadAllSources() {
        List<List<ModelDefinition>> result = new ArrayList<>();
        Map<String, ModelMetricsProvider> providers;
        try {
            providers = ServiceProvider.of(ModelMetricsProvider.class).list();
        } catch (Exception e) {
            return result;
        }
        if (providers == null) {
            return result;
        }
        for (ModelMetricsProvider provider : providers.values()) {
            if (provider == null) {
                continue;
            }
            try {
                List<ModelDefinition> metrics = provider.getMetrics();
                if (metrics != null && !metrics.isEmpty()) {
                    result.add(metrics);
                }
            } catch (Exception e) {
                // 单个数据源失败不影响对账
            }
        }
        return result;
    }

    /**
     * 构建合并索引：原样标识与折叠键都指向同一份合并结果。
     *
     * @param sources 各数据源指标
     * @return 索引
     */
    private static Map<String, ModelDefinition> buildIndex(List<List<ModelDefinition>> sources) {
        Map<String, ModelDefinition> index = new LinkedHashMap<>();
        for (List<ModelDefinition> metrics : sources) {
            for (ModelDefinition md : metrics) {
                if (md == null || md.getId() == null || md.getId().isBlank()) {
                    continue;
                }
                index.merge(md.getId(), md, UsagePricingReconciler::merge);
                String folded = fold(md.getId());
                if (!folded.isEmpty()) {
                    index.merge("\u0000fold:" + folded, md, UsagePricingReconciler::merge);
                }
            }
        }
        return index;
    }

    /**
     * 合并两条定义：目标为空的字段用源补齐。
     *
     * @param target 已合并结果（首次为 null）
     * @param src    当前数据源记录
     * @return 合并结果
     */
    private static ModelDefinition merge(ModelDefinition target, ModelDefinition src) {
        if (target == null) {
            return src;
        }
        if (target.getInputUnitPrice() == null) {
            target.setInputUnitPrice(src.getInputUnitPrice());
        }
        if (target.getOutputUnitPrice() == null) {
            target.setOutputUnitPrice(src.getOutputUnitPrice());
        }
        if (target.getCacheHitPrice() == null) {
            target.setCacheHitPrice(src.getCacheHitPrice());
        }
        if (target.getContextWindowTokens() == null) {
            target.setContextWindowTokens(src.getContextWindowTokens());
        }
        if (target.getReasoning() == null || !target.getReasoning()) {
            target.setReasoning(src.getReasoning());
        }
        if (target.getImageInput() == null || !target.getImageInput()) {
            target.setImageInput(src.getImageInput());
        }
        if (target.getFunctionCalling() == null || !target.getFunctionCalling()) {
            target.setFunctionCalling(src.getFunctionCalling());
        }
        return target;
    }

    /**
     * 统计同厂商同模型的重复定价。
     *
     * @param sources 各数据源指标
     * @return 厂商:模型 → 出现次数（仅含 &gt;1 的）
     */
    private static Map<String, Integer> countDuplicates(List<List<ModelDefinition>> sources) {
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (List<ModelDefinition> metrics : sources) {
            for (ModelDefinition md : metrics) {
                if (md == null || md.getId() == null || md.getId().isBlank()) {
                    continue;
                }
                if (md.getInputUnitPrice() == null && md.getOutputUnitPrice() == null) {
                    continue;
                }
                counts.merge(providerKey(md.getProvider(), md.getId()), 1, Integer::sum);
            }
        }
        Map<String, Integer> duplicates = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        counts.forEach((k, v) -> {
            if (v > 1) {
                duplicates.put(k, v);
            }
        });
        return duplicates;
    }

    /**
     * 在索引中查找模型：先原样，再折叠。
     *
     * @param index 索引
     * @param model 模型标识
     * @return 命中的定义；未命中返回 null
     */
    private static ModelDefinition find(Map<String, ModelDefinition> index, String model) {
        ModelDefinition exact = index.get(model);
        if (exact != null) {
            return exact;
        }
        String folded = fold(model);
        if (folded.isEmpty()) {
            return null;
        }
        return index.get("\u0000fold:" + folded);
    }

    /**
     * 取模型在索引中的匹配键（用于标记命中）。
     *
     * @param index 索引
     * @param model 模型标识
     * @return 匹配键；未命中返回 null
     */
    private static String matchKey(Map<String, ModelDefinition> index, String model) {
        if (index.containsKey(model)) {
            return model;
        }
        String folded = fold(model);
        String key = "\u0000fold:" + folded;
        return index.containsKey(key) ? key : null;
    }

    /**
     * 厂商:模型 组合键。
     *
     * @param provider 厂商
     * @param model    模型
     * @return 组合键
     */
    private static String providerKey(String provider, String model) {
        return (provider == null ? "?" : provider) + ":" + model;
    }

    /**
     * 折叠模型标识：剥厂商前缀与分隔符，只留小写字母数字。
     *
     * @param model 模型标识
     * @return 折叠值
     */
    private static String fold(String model) {
        if (model == null) {
            return "";
        }
        String name = model;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') {
                builder.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                builder.append((char) (ch + ('a' - 'A')));
            }
        }
        return builder.toString();
    }
}
