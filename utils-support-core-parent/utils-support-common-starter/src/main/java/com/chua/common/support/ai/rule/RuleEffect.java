package com.chua.common.support.ai.rule;

/**
 * 规则命中后的结果语义。
 *
 * <p>规则引擎本身不解释业务，只把「命中了哪些规则」聚合成一个
 * {@code RuleEffect} 交给调用方裁决。分成这几档是因为不同的上层
 * 处置方式差别很大：放行、拒绝、报错、中断，必须能被调用方区分开，
 * 不能都塌缩成一个布尔值。</p>
 *
 * <p>多规则聚合时取<b>最重</b>的一档，权重顺序为
 * {@link #HALT} &gt; {@link #ERROR} &gt; {@link #DENY} &gt; {@link #ALLOW} &gt; {@link #NONE}。
 * 「最重」而非「最严」是因为 {@link #HALT} 表示执行被中断，
 * 后续规则根本没跑，不存在「更严」可言。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum RuleEffect {

    /**
     * 规则只做观测，不影响最终结论。
     */
    NONE,

    /**
     * 明确放行。聚合时会被更重的档位覆盖。
     */
    ALLOW,

    /**
     * 明确拒绝。调用方通常据此中断业务流程。
     */
    DENY,

    /**
     * 规则执行出错。与 {@link #DENY} 分开是必要的：
     * 「判定不通过」和「判定过程失败」的上游处置完全不同
     * ——前者是业务结论，后者通常要告警并重试。
     */
    ERROR,

    /**
     * 命中即中断，不再评估后续规则。
     *
     * <p>用于「一票否决」类规则，例如账户已冻结时无需再评估风控。</p>
     */
    HALT;

    /**
     * 取两个结果中更重的一档。
     *
     * @param left  左侧结果，可为 null（按 {@link #NONE} 处理）
     * @param right 右侧结果，可为 null（按 {@link #NONE} 处理）
     * @return 更重的结果
     */
    public static RuleEffect heavier(RuleEffect left, RuleEffect right) {
        RuleEffect a = left == null ? NONE : left;
        RuleEffect b = right == null ? NONE : right;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
