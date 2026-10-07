package com.chua.common.support.ai.rule;

/**
 * 行动结论——一次执行最终「做什么」的业务处置。
 *
 * <p><b>作用</b>：分支的 {@code then} 动作（或规则动作）把处置结论写进
 * {@link RuleContext#action(Object)} 这个单槽位，执行结束后由结果侧
 * {@code BranchResult#action()} 带出，调用方据此驱动后续流程——
 * 放行、拦截、转人工还是升级。它与 {@code message}（拦截原因）是一对：
 * <b>{@code action} 回答「做什么」，{@code message} 回答「为什么」</b>，
 * 两者都在命中的那条分支上产生，一起构成这次判定的完整交代。</p>
 *
 * <p><b>与 {@link RuleEffect} 的区别</b>：{@code RuleEffect} 是规则引擎
 * 机制层的聚合档位（命中如何影响执行：放行/拒绝/报错/中断），
 * 由引擎按最重档位自动聚合，业务代码不直接填它；
 * {@code Action} 是业务层的处置语义（本次请求具体怎么处理），
 * 由动作显式写入、单槽位后写覆盖。分支门面只产生 {@code Action}，
 * 不产生 {@code RuleEffect}。</p>
 *
 * <p>枚举只覆盖常用处置；个性化结论仍可直接写字符串
 * （{@link RuleContext#action(Object)} 接受任意对象），
 * 需要归一时用 {@link #of(String)} 解析，识别不了返回 null。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public enum Action {

    /**
     * 放行：不拦截，业务流程继续。
     */
    PASS("放行"),

    /**
     * 转人工复核：交由人工判断后再定夺。
     */
    REVIEW("转人工复核"),

    /**
     * 拒绝：拦截本次请求，不放行。
     */
    REJECT("拒绝"),

    /**
     * 升级处理：提升处置级别，如升级审批、上报安全团队。
     */
    ESCALATE("升级处理");

    /**
     * 中文说明，供界面展示与日志输出
     */
    private final String description;

    Action(String description) {
        this.description = description;
    }

    /**
     * 中文说明。
     *
     * @return 说明文本
     */
    public String description() {
        return description;
    }

    /**
     * 按标识解析，忽略大小写与首尾空白。
     *
     * <p>用于把外部传来的字符串（如 {@code "reject"}）归一成枚举；
     * 解析不了返回 null，由调用方按「自定义处置」或「未表态」处理，
     * 不抛异常——action 槽位本身允许任意自定义值，识别失败不是错误。</p>
     *
     * @param code 标识，可为 null 或空串
     * @return 对应枚举；无法识别时返回 null
     */
    public static Action of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
