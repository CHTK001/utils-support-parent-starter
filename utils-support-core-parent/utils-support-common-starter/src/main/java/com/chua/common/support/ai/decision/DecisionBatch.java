package com.chua.common.support.ai.decision;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次概率决策求值的结果。
 *
 * <p>{@link DecisionBatch} 只承载「问题标识 → 答案」的对应关系，不携带 token 用量、
 * 原始响应体等与规则判断无关的信息——那些留在各适配器自己的响应类型上，
 * 避免通用 SPI 被某个实现的细节污染。</p>
 *
 * <p><b>推荐用法</b>：拿到批次后立刻调 {@link #answers(DecisionRequest)} 做一次
 * 交叉校验，再把 {@link #flatten()} 产出的变量灌进规则上下文：</p>
 * <pre>{@code
 * DecisionRequest request = DecisionRequest.of(state, queries);
 * DecisionBatch batch = provider.decide(request);
 * Map<String, Decision> answers = batch.answers(request);   // 交叉校验
 * context.putAll(batch.flatten());                          // 展平为上下文变量
 * }</pre>
 *
 * @param decisions 答案列表，标识互不重复
 * @author CH
 * @since 4.0.0.42
 */
public record DecisionBatch(List<Decision> decisions) {

    /**
     * 展平后置信度变量的后缀
     */
    public static final String SUFFIX_CONFIDENCE = ".confidence";

    /**
     * 展平后概率分布变量的后缀
     */
    public static final String SUFFIX_PROBABILITIES = ".probabilities";

    /**
     * 构造决策结果批次。
     *
     * <p>value class 前置条件——组件为 {@link List}，用
     * {@link List#copyOf(Collection)} 做防御性拷贝。</p>
     *
     * @param decisions 答案列表
     * @throws NullPointerException     {@code decisions} 为 null 时
     * @throws IllegalArgumentException 答案列表为空或标识重复时
     */
    public DecisionBatch {
        Objects.requireNonNull(decisions, "decisions 不能为 null");
        if (decisions.isEmpty()) {
            throw new IllegalArgumentException("decisions 不能为空");
        }
        Map<String, Decision> indexed = new LinkedHashMap<>();
        for (Decision decision : decisions) {
            if (indexed.put(decision.queryId(), decision) != null) {
                throw new IllegalArgumentException("decisions 中存在重复的答案标识: " + decision.queryId());
            }
        }
        decisions = List.copyOf(decisions);
    }

    /**
     * 答案数量。
     *
     * @return 答案数量
     */
    public int size() {
        return decisions.size();
    }

    /**
     * 按标识查找答案。
     *
     * @param queryId 问题标识
     * @return 对应答案；不存在时返回 null
     */
    public Decision decision(String queryId) {
        for (Decision decision : decisions) {
            if (decision.queryId().equals(queryId)) {
                return decision;
            }
        }
        return null;
    }

    /**
     * 与请求做交叉校验并返回标识到答案的映射。
     *
     * <p>逐项校验：</p>
     * <ol>
     *   <li>请求里的每个问题都有答案（<b>缺一即失败</b>——漏答会让规则静默拿到
     *       null 变量并在别处以「变量不存在」的形式报错，根因被掩盖）</li>
     *   <li>每个答案都能对上请求里的问题（防适配器串台）</li>
     *   <li>类型与问题声明一致</li>
     *   <li>结论标签落在该问题的候选集内（NOUL 为 true / false，
     *       CHOICE 为 {@code options} 的键，SCORE 为 {@code levels} 的元素）</li>
     *   <li>概率分布的键全部是合法候选（允许只返回 Top-K 子集）</li>
     * </ol>
     *
     * @param request 原请求
     * @return 不可变的标识到答案映射，迭代顺序与请求声明顺序一致
     * @throws DecisionException 任一项校验不通过时
     */
    public Map<String, Decision> answers(DecisionRequest request) {
        Objects.requireNonNull(request, "request 不能为 null");
        Map<String, Decision> result = new LinkedHashMap<>();
        for (DecisionQuery query : request.queries()) {
            Decision decision = decision(query.id());
            if (decision == null) {
                throw new DecisionException("问题 " + query.id() + " 没有对应答案");
            }
            if (decision.type() != query.type()) {
                throw new DecisionException("问题 " + query.id() + " 声明类型为 " + query.type()
                        + "，实际答案为 " + decision.type());
            }
            validateCandidates(query, decision);
            result.put(query.id(), decision);
        }
        if (result.size() != decisions.size()) {
            List<String> extra = decisions.stream()
                    .map(Decision::queryId)
                    .filter(id -> !result.containsKey(id))
                    .toList();
            throw new DecisionException("存在请求之外的多余答案: " + extra);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 展平为普通上下文变量，供规则 {@code when} 直接取值。
     *
     * <p>每个答案产出三个变量：</p>
     * <table border="1">
     *   <caption>展平变量</caption>
     *   <tr><th>变量名</th><th>含义</th><th>类型</th></tr>
     *   <tr><td>{@code <id>}</td><td>结论标签，供等值比较</td><td>{@link String}</td></tr>
     *   <tr><td>{@code <id>.confidence}</td><td>所选标签的置信度，供阈值比较</td><td>{@link Double}</td></tr>
     *   <tr><td>{@code <id>.probabilities}</td><td>完整分布，供自定义判据</td><td>{@code Map<String, Double>}</td></tr>
     * </table>
     *
     * <p>这样条件层不需要认识 {@link Decision} 这个类型，就能写
     * {@code when("fraud", gt(0.8))} 或 {@code when("channel", eq("alipay"))}。</p>
     *
     * @return 不可变变量表，迭代顺序与答案声明顺序一致
     */
    public Map<String, Object> flatten() {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (Decision decision : decisions) {
            variables.put(decision.queryId(), decision.label());
            variables.put(decision.queryId() + SUFFIX_CONFIDENCE, decision.confidence());
            variables.put(decision.queryId() + SUFFIX_PROBABILITIES, decision.probabilities());
        }
        return Collections.unmodifiableMap(variables);
    }

    /**
     * 校验答案的标签与分布键都在问题的候选集内。
     *
     * @param query    问题声明
     * @param decision 答案
     */
    private void validateCandidates(DecisionQuery query, Decision decision) {
        Set<String> candidates = candidates(query);
        if (!candidates.contains(decision.label())) {
            throw new DecisionException("问题 " + query.id() + " 的答案标签 " + decision.label()
                    + " 不在候选集内: " + candidates);
        }
        for (String key : decision.probabilities().keySet()) {
            if (!candidates.contains(key)) {
                throw new DecisionException("问题 " + query.id() + " 的概率分布键 " + key
                        + " 不在候选集内: " + candidates);
            }
        }
    }

    /**
     * 取问题声明的候选集。
     *
     * <p>NOUL 的候选集<b>恒为</b> {@link DecisionType#TRUE_LABEL} /
     * {@link DecisionType#FALSE_LABEL}，与 {@code criteria} 无关：
     * {@code criteria} 只是「结论说明表」（键为 true / false，值为说明文字），
     * 描述语义而不是重新定义标签。是 / 否型问题在协议层面就是二值的，
     * 允许自定义标签会让实现方永远无法满足
     * 声明的候选集。键的合法性在 {@link DecisionQuery} 构造期就已经拦下，
     * 这里不再分支。</p>
     *
     * @param query 问题声明
     * @return 候选集；NOUL 为 true / false，SCORE 为等级别名
     */
    private static Set<String> candidates(DecisionQuery query) {
        if (query.type().isNoul()) {
            return Set.of(DecisionType.TRUE_LABEL, DecisionType.FALSE_LABEL);
        }
        if (query.type().isScore()) {
            return new LinkedHashSet<>(query.levels());
        }
        return query.options().keySet();
    }
}
