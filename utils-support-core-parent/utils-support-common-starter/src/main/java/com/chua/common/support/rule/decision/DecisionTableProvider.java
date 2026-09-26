package com.chua.common.support.rule.decision;

import com.chua.common.support.rule.RuleContext;
import com.chua.common.support.rule.RuleException;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置决策表实现。
 *
 * <p>按优先级升序求值候选行，命中策略见 {@link DecisionTable.HitPolicy}。
 * 这是 {@link RuleDecisionProvider} 的默认实现，也是「决策」节点开箱可用的原因：
 * 不引入任何三方依赖就能用决策表。</p>
 *
 * <h3>为什么 UNIQUE 策略要把冲突当配置错误</h3>
 * <p>决策表出现「两行都成立但结果不同」通常意味着规则写重了或阈值区间重叠。
 * 此时取第一个会让结果依赖行的书写顺序，UNIQUE 策略选择直接报错，
 * 把问题暴露在装载/运行期，而不是让线上结论悄悄改变。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class DecisionTableProvider implements RuleDecisionProvider {

    /**
     * 共享实例：无状态
     */
    public static final DecisionTableProvider INSTANCE = new DecisionTableProvider();

    @Override
    public boolean supports(DecisionTable table) {
        return table != null;
    }

    @Override
    public Object decide(DecisionTable table, RuleContext context) {
        List<DecisionTable.Row> ordered = new ArrayList<>(table.rows());
        ordered.sort(java.util.Comparator.comparingInt(DecisionTable.Row::priority));

        Object firstOutcome = null;
        for (DecisionTable.Row row : ordered) {
            if (!row.condition().test(context)) {
                continue;
            }
            if (context != null && context.halted()) {
                // 决策过程中被 halt：视为本规则链终止，直接返回已定结果
                return firstOutcome == null ? table.defaultValue() : firstOutcome;
            }
            if (table.hitPolicy() == DecisionTable.HitPolicy.FIRST) {
                return row.outcome();
            }
            if (firstOutcome == null) {
                firstOutcome = row.outcome();
            } else if (!java.util.Objects.equals(firstOutcome, row.outcome())) {
                throw new RuleException("决策表[" + table.id() + "] 采用 UNIQUE 命中策略，"
                        + "但优先级 " + row.priority() + " 的候选行给出不同结果："
                        + firstOutcome + " 与 " + row.outcome()
                        + "；请检查规则是否重叠");
            }
        }
        return firstOutcome == null ? table.defaultValue() : firstOutcome;
    }
}
