package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.decision.DecisionType;
import com.chua.common.support.collection.wrap.Wraps;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单个决策问题的答案。
 *
 * <p>把三种协议的答案统一收敛到同一组组件：结论标签 + 概率分布 + 置信度，
 * 调用方按类型取出自己关心的数值即可。与 {@link com.chua.common.support.ai.decision.Decision}
 * 不同，本类为消费者网关/云端协议服务设计，额外携带期望分（{@link #score()}）
 * 与标签质量（{@link #labelMass()}）一类的协议叶片。</p>
 *
 * <p>类型与取值对应关系：
 * <table border="1">
 *   <caption>类型与取值对应关系</caption>
 *   <tr><th>type</th><th>{@code label}</th><th>{@code probability}</th>
 *       <th>{@code score}</th><th>{@code choice}</th><th>{@code probabilities} 的键</th></tr>
 *   <tr><td>{@link DecisionType#NOUL}</td>
 *       <td>{@code "yes"} 或 {@code "no"}</td>
 *       <td>{@code yes} 的概率</td><td>空</td><td>空</td>
 *       <td>{@code "yes"} / {@code "no"}</td></tr>
 *   <tr><td>{@link DecisionType#CHOICE}</td>
 *       <td>最高概率选项的标签（OpenAI 为选项原文，SystemOne 为 {@code A}~{@code Z}）</td>
 *       <td>空</td><td>空</td>
 *       <td>被选中的标签</td><td>各选项标签</td></tr>
 *   <tr><td>{@link DecisionType#SCORE}</td>
 *       <td>最高概率等级的标签（{@code 0}~{@code 9}）</td>
 *       <td>空</td><td>等级的期望分</td>
 *       <td>空</td><td>各级标签索引</td></tr>
 * </table>
 *
 * <p><b>{@code confidence} 是派生值而非组件</b>：choice 类型按
 * {@code (K * pMax - 1) / (K - 1)} 计算（K 为选项数，pMax 为最大概率），
 * score 类型按 {@code 1 - E|i - mode| / E|i - (L - 1)| / 2|} 计算
 * （i 为等级索引，mode 为众数索引，L 为等级数）。
 * 实现类根据 {@link #probabilities()} 计算后回填。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public record DecisionAnswer(

        /**
         * 对应的问题标识
         */
        String name,

        /**
         * 问题类型；问题被拒绝回答（refusal）时为空
         */
        DecisionType type,

        /**
         * 结论标签（NOUL 为 yes/no、CHOICE 为选项标签、SCORE 为等级标签）
         */
        String label,

        /**
         * 成立概率（NOUL 专用），取值 0.0 ~ 1.0
         */
        Double probability,

        /**
         * 期望分（SCORE 专用），取值为各等级的概率加权平均索引，可以落在等级之间
         */
        Double score,

        /**
         * 被选中的选项标签（CHOICE 专用）
         */
        String choice,

        /**
         * 置信度
         */
        Double confidence,

        /**
         * 概率分布，按标签索引（各标签对应取值的概率）
         */
        Map<String, Double> probabilities,

        /**
         * 等级标签表（SCORE 专用），等级索引 → 等级描述
         */
        Map<Integer, String> legend,

        /**
         * 标签质量（运气成分指标）：SystemOne 兼容端点返回完整词表
         * 下的未归一化标签概率；{@code x_label_mass} 越低说明模型把概率
         * 压在选项外，答案不可靠
         */
        Double labelMass) {

    /**
     * NOUL 的「成立」标签
     */
    public static final String YES_LABEL = "yes";

    /**
     * NOUL 的「不成立」标签
     */
    public static final String NO_LABEL = "no";

    /**
     * 构造是 / 否型答案。
     *
     * @param name        问题标识
     * @param predicate   结论：成立
     * @param probability 成立概率，取值 0.0 ~ 1.0
     * @return 是 / 否型答案
     */
    public static DecisionAnswer noul(String name, boolean predicate, double probability) {
        return new DecisionAnswer(name, DecisionType.NOUL,
                predicate ? YES_LABEL : NO_LABEL,
                probability, null, null, null,
                Map.of(YES_LABEL, probability, NO_LABEL, 1 - probability),
                null, null);
    }

    /**
     * 构造多选一答案。
     *
     * @param name         问题标识
     * @param choice       被选中的选项标签
     * @param confidence   置信度
     * @param probabilities 概率分布，按标签索引
     * @return 多选一答案
     */
    public static DecisionAnswer choice(String name, String choice, Double confidence,
                                        Map<String, Double> probabilities) {
        return new DecisionAnswer(name, DecisionType.CHOICE, choice, null, null, choice, confidence,
                probabilities, null, null);
    }

    /**
     * 构造有序分级型答案。
     *
     * @param name         问题标识
     * @param score        期望分（概率加权平均等级索引）
     * @param confidence   置信度
     * @param legend       等级标签表，索引 → 等级描述
     * @param probabilities 概率分布，按等级索引
     * @return 有序分级型答案
     */
    public static DecisionAnswer score(String name, double score, Double confidence,
                                       Map<Integer, String> legend,
                                       Map<String, Double> probabilities) {
        return new DecisionAnswer(name, DecisionType.SCORE, null, null, score, null, confidence, probabilities,
                legend, null);
    }

    /**
     * 构造拒绝型答案。
     *
     * <p>当问题命中安全检查、内容过滤等原因拒绝回答时，OpenAI 协议返回的类型为
     * {@code "refusal"}（SystemOne 协议），本类以 {@code type == null} 记录。</p>
     *
     * @param name 问题标识
     * @return 拒绝型答案
     */
    public static DecisionAnswer refusal(String name) {
        return new DecisionAnswer(name, null, "refusal", null, null, null, null, null, null, null);
    }

    /**
     * 是否为拒绝型答案。
     *
     * @return true 表示问题未返回结论
     */
    public boolean isRefusal() {
        return type == null && "refusal".equals(label);
    }

    /**
     * 获取结论值（按类型收敛）：
     * noul → Boolean，choice → 选项标签，score → 期望分。
     *
     * @return 结论值
     */
    public Object value() {
        if (type == null || type.isNoul()) {
            return predicate();
        }
        if (type.isChoice()) {
            return choice;
        }
        return score;
    }

    /**
     * 是否为「成立」（noul 为是 / 成立）。
     *
     * @return true 表示结论为 yes
     */
    public Boolean predicate() {
        if (type == null || !type.isNoul()) {
            return null;
        }
        return label != null && YES_LABEL.equals(label);
    }

    /**
     * 获取选择的选项标签。
     *
     * @return 选项标签；非 CHOICE 类型时返回 null
     */
    public String choice() {
        return type != null && type.isChoice() ? choice : null;
    }

    /**
     * 获取期望分割分。
     *
     * @return 期望分割分；非 SCORE 类型时返回 null
     */
    public Double score() {
        return type != null && type.isScore() ? score : null;
    }

    /**
     * 获取选项数 / 等级数。
     *
     * @return 选项数量（CHOICE）或等级数量（SCORE）
     */
    public int candidates() {
        if (probabilities == null) {
            return 0;
        }
        return probabilities.size();
    }

    /**
     * 获取待查找标签。
     *
     * @param candidate 候选标签
     * @return 该候选的概率；分布中不存在时返回 null
     */
    public Double probabilityOf(String candidate) {
        return candidate == null ? null : probabilities.get(candidate);
    }

    /**
     * 获取等级描述。
     *
     * @param index 等级索引
     * @return 描述；不存在时返回 null
     */
    public String levelOf(int index) {
        if (legend == null) {
            return null;
        }
        return legend.get(index);
    }

    /**
     * 获取被选中的等级索引（期望分割分的四舍五入值）。
     *
     * @return 等级索引
     */
    public Integer level() {
        if (type == null || !type.isScore()) {
            return null;
        }
        if (score == null) {
            return null;
        }
        return BigDecimal.valueOf(score).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * 获取所有标签的排序概率。
     *
     * <p>按问题中定义顺序排列：CHOICE 随选项输入顺序，SCORE 随等级升序，
     * noul 固定为 {@code true} → {@code false}。</p>
     *
     * @return 排序概率列表
     */
    public List<Double> sortedProbabilities() {
        if (probabilities == null) {
            return Collections.emptyList();
        }
        List<Double> result = Wraps.list();
        if (type != null) {
            if (type.isNoul()) {
                result.add(probabilities.get(YES_LABEL));
                result.add(probabilities.get(NO_LABEL));
            } else if (type.isChoice()) {
                result.addAll(probabilities.values());
            } else if (type.isScore()) {
                for (int i = 0; i < legend.size(); i++) {
                    result.add(probabilities.get(String.valueOf(i)));
                }
            }
        } else {
            result.addAll(probabilities.values());
        }
        return result;
    }
}
