package com.chua.common.support.ai.decision;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个概率决策的答案。
 *
 * <p>三种问题类型统一收敛到同一组组件：<b>结论标签</b> + <b>完整概率分布</b>。
 * 这样规则层不必按类型分支取值，{@code when} 阈值判断对 NOUL / CHOICE / SCORE
 * 全部适用。</p>
 *
 * <table border="1">
 *   <caption>类型与取值对应关系</caption>
 *   <tr><th>type</th><th>{@code label}</th><th>{@code probabilities} 的键</th></tr>
 *   <tr><td>{@link DecisionType#NOUL}</td><td>{@code "true"} 或 {@code "false"}</td>
 *       <td>{@code "true"} / {@code "false"}</td></tr>
 *   <tr><td>{@link DecisionType#CHOICE}</td><td>被选中的选项键</td>
 *       <td>各选项键</td></tr>
 *   <tr><td>{@link DecisionType#SCORE}</td><td>被选中的等级</td>
 *       <td>各级别名</td></tr>
 * </table>
 *
 * <p><b>{@code confidence} 是派生值而非组件</b>：它恒等于
 * {@code probabilities.get(label)}。做成 record 组件就等于开了一个
 * 「confidence 与分布对不上」的口子，而这种不一致极难在下游发现
 * （规则照常跑，只是判错）。因此本类只提供派生方法 {@link #confidence()}。</p>
 *
 * <p><b>刻意不校验分布求和为 1</b>：部分服务只返回 Top-K 候选的相对概率，
 * 强行要求归一会把合法响应判成非法。求和归一化是调用方的事，不是协议约束。</p>
 *
 * @param queryId      对应的问题标识
 * @param type         问题类型
 * @param label        结论标签，必须是 {@code probabilities} 中的一个键
 * @param probabilities 完整概率分布，取值均在 0.0 ~ 1.0
 * @author CH
 * @since 4.0.0.42
 */
public record Decision(
        String queryId,
        DecisionType type,
        String label,
        Map<String, Double> probabilities) {

    /**
     * 构造单个决策答案。
     *
     * <p>value class 前置条件——{@code probabilities} 用
     * {@link Collections#unmodifiableMap(Map)} 包装 {@link LinkedHashMap} 拷贝，
     * 而不是 {@link Map#copyOf(Map)}：后者不保证迭代顺序，
     * 而概率分布的输出顺序直接影响日志比对与序列化结果的稳定性。</p>
     *
     * <p><b>概率求和内部走 {@link BigDecimal}</b>：{@code double} 连加会累积
     * 表示误差（{@code 0.1 + 0.2 + 0.3} 得 {@code 0.6000000000000001}），
     * 用 {@code double} 累加做「和是否大于 0」判断在边界上不可靠。
     * 走 {@link BigDecimal#valueOf(double)} 后按十进制精确累加。
     * 注意必须用 {@code valueOf} 而非 {@code new BigDecimal(double)}——
     * 后者取的是二进制精确展开（{@code 0.93} 会变成 50 多位小数），
     * 恰好背离本意。
     * 对外仍暴露 {@link Double}：单值取值与排序比较用 {@code double} 足够，
     * 无需让调用方背 BigDecimal。</p>
     *
     * @param queryId      对应的问题标识
     * @param type         问题类型
     * @param label        结论标签
     * @param probabilities 完整概率分布
     * @throws NullPointerException     {@code type}、{@code label} 或 {@code probabilities} 为 null 时
     * @throws IllegalArgumentException 标识为空白、分布为空、取值越界或标签不在分布中时
     */
    public Decision {
        if (queryId == null || queryId.isBlank()) {
            throw new IllegalArgumentException("queryId 不能为 null 或空白");
        }
        if (type == null) {
            throw new NullPointerException("type 不能为 null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label 不能为 null 或空白");
        }
        if (probabilities == null) {
            throw new NullPointerException("probabilities 不能为 null");
        }
        if (probabilities.isEmpty()) {
            throw new IllegalArgumentException("probabilities 不能为空");
        }
        Map<String, Double> copy = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("probabilities 的键不能为 null 或空白");
            }
            if (value == null) {
                throw new NullPointerException("probabilities 中 " + key + " 的概率不能为 null");
            }
            if (value < 0.0D || value > 1.0D || value.isNaN()) {
                throw new IllegalArgumentException(
                        "probabilities 中 " + key + " 的概率必须在 0.0~1.0 之间，实际 " + value);
            }
            copy.put(key, value);
            sum = sum.add(BigDecimal.valueOf(value));
        }
        if (sum.signum() <= 0) {
            throw new IllegalArgumentException("probabilities 的概率之和必须大于 0");
        }
        if (!copy.containsKey(label)) {
            throw new IllegalArgumentException("label " + label + " 不在 probabilities 的键中: " + copy.keySet());
        }
        probabilities = Collections.unmodifiableMap(copy);
    }

    /**
     * 构造是 / 否型答案，由「成立」概率反推标签与分布。
     *
     * <p>自动补齐 {@code false} 分支，使 {@link #isTrue()} 可用。
     * 这是最常见的构造场景，故单独提供。</p>
     *
     * <p><b>补概率内部走 {@link BigDecimal}</b>：直接写
     * {@code 1.0D - p} 会因二进制表示误差产出 {@code 0.06999999999999995}
     * 这种值（{@code p = 0.93} 时），日志比对与等值断言都会受影响。
     * 改走十进制减法后得到 {@code 0.07}——即最接近 0.07 的那个
     * {@code double}，与 {@code p} 的十进制字面量自洽。</p>
     *
     * <p><b>分布用显式 {@link LinkedHashMap} 逐项 put</b>，不经过
     * {@code Map.of} 再拷贝：{@code Map.of} 的迭代顺序带每 JVM 随机 SALT，
     * 复制它等于把随机顺序原样保留下来，分布顺序会在两次运行之间变化。
     * 这里固定为 {@code true} 在前、{@code false} 在后。</p>
     *
     * @param queryId          对应的问题标识
     * @param trueProbability  成立的概率，取值 0.0 ~ 1.0
     * @return 是 / 否型答案
     * @throws IllegalArgumentException 概率越界时
     */
    public static Decision noul(String queryId, double trueProbability) {
        if (trueProbability < 0.0D || trueProbability > 1.0D || Double.isNaN(trueProbability)) {
            throw new IllegalArgumentException("trueProbability 必须在 0.0~1.0 之间，实际 " + trueProbability);
        }
        Map<String, Double> distribution = new LinkedHashMap<>();
        distribution.put(DecisionType.TRUE_LABEL, trueProbability);
        distribution.put(DecisionType.FALSE_LABEL, complement(trueProbability));
        return new Decision(queryId, DecisionType.NOUL,
                trueProbability >= 0.5D ? DecisionType.TRUE_LABEL : DecisionType.FALSE_LABEL,
                distribution);
    }

    /**
     * 求「1 - 概率」的补数。
     *
     * <p>走 {@link BigDecimal#ONE} 减 {@link BigDecimal#valueOf(double)}，
     * 按十进制精确相减后再转回 {@code double}。
     * {@code valueOf} 走的是 {@link Double#toString(double)} 的最短十进制表示，
     * 正是「用户写了 0.93 就按 0.93 算」的期望；
     * 若用 {@code new BigDecimal(double)} 会拿到二进制精确展开，精度反而更差。</p>
     *
     * @param probability 原概率，取值 0.0 ~ 1.0
     * @return 补概率
     */
    private static double complement(double probability) {
        return BigDecimal.ONE.subtract(BigDecimal.valueOf(probability)).doubleValue();
    }

    /**
     * 构造多选一或有序分级型答案。
     *
     * @param queryId      对应的问题标识
     * @param type         问题类型，只能是 CHOICE 或 SCORE
     * @param label        结论标签
     * @param probabilities 完整概率分布
     * @return 决策答案
     * @throws IllegalArgumentException 类型为 NOUL 时
     */
    public static Decision of(String queryId, DecisionType type, String label, Map<String, Double> probabilities) {
        if (type != null && type.isNoul()) {
            throw new IllegalArgumentException("NOUL 类型请使用 Decision.noul(queryId, trueProbability)");
        }
        return new Decision(queryId, type, label, probabilities);
    }

    /**
     * 获取结论标签的置信度。
     *
     * @return {@code probabilities.get(label)}；由构造期校验保证非 null
     */
    public double confidence() {
        return probabilities.get(label);
    }

    /**
     * 获取指定候选的概率。
     *
     * @param candidate 候选键
     * @return 该候选的概率；分布中不存在时返回 null
     */
    public Double probabilityOf(String candidate) {
        return candidate == null ? null : probabilities.get(candidate);
    }

    /**
     * 是否为「成立」。
     *
     * <p>供规则层 {@code when} 做等值判断，避免直接依赖
     * {@link DecisionType#TRUE_LABEL} 常量字符串。</p>
     *
     * @return true 表示问题类型为是 / 否型且结论为成立
     */
    public boolean isTrue() {
        return type.isNoul() && DecisionType.TRUE_LABEL.equals(label);
    }
}
