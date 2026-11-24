package com.chua.common.support.ai.agent;

/**
 * Agent 计划确认契约。
 *
 * <p>在 Agent 按计划执行前进行人工/外部审批：实现方弹出审批流程并阻塞等待结论。
 * 同步 {@code run} 链路直接回调本接口；流式链路则由实现转为
 * {@code CONFIRMATION_REQUEST} 事件挂起等待。</p>
 *
 * <p>审批结论三态：放行（按计划执行）、修订（带反馈重新生成计划）、
 * 拒绝（终止本次执行并把理由回给调用方）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@FunctionalInterface
public interface AgentPlanConfirmation {

    /**
     * 审批一个计划。
     *
     * @param plan 待审批计划，不应为 null
     * @return 审批结论，不应为 null
     */
    Outcome confirm(AgentPlan plan);

    /**
     * 审批结论类型
     */
    enum Decision {
        /**
         * 放行：按计划执行
         */
        PROCEED,
        /**
         * 修订：按反馈重新生成计划后再次送审
         */
        REVISE,
        /**
         * 拒绝：终止执行
         */
        REJECT
    }

    /**
     * 审批结论（决策 + 反馈文本）。
     *
     * @param decision 决策，不应为 null
     * @param feedback 修订意见 / 拒绝理由，可为 null
     */
    record Outcome(Decision decision, String feedback) {

        /**
         * 构造结论。
         */
        public Outcome {
            if (decision == null) {
                throw new NullPointerException("decision 不能为 null");
            }
        }

        /**
         * 放行结论。
         *
         * @return 放行
         */
        public static Outcome proceed() {
            return new Outcome(Decision.PROCEED, null);
        }

        /**
         * 修订结论。
         *
         * @param feedback 修订意见
         * @return 修订
         */
        public static Outcome revise(String feedback) {
            return new Outcome(Decision.REVISE, feedback);
        }

        /**
         * 拒绝结论。
         *
         * @param reason 拒绝理由
         * @return 拒绝
         */
        public static Outcome reject(String reason) {
            return new Outcome(Decision.REJECT, reason);
        }
    }

    /**
     * 恒放行确认器（默认兜底，不做任何阻塞）。
     *
     * @return 恒放行确认器
     */
    static AgentPlanConfirmation alwaysProceed() {
        return plan -> Outcome.proceed();
    }
}
