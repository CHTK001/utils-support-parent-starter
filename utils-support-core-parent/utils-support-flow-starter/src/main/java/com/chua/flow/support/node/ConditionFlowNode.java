package com.chua.flow.support.node;

import com.chua.common.support.task.flow.ConditionNode;
import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.FlowProps;

/**
* 条件节点。
*
* <p>流程中的分支决策节点，通过 {@link #test(FlowContext)} 求值判断结果，
* 引擎按结果走注册的 true/false 分支。</p>
*
* <p>节点属性说明：</p>
* <ul>
*   <li>{@code key} — 取值键，为 "current" 时取当前数据，否则取上下文属性</li>
*   <li>{@code equals} — 等于比较的期望值（可选）</li>
*   <li>{@code notEmpty} — 是否要求非空（可选，默认 true）</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class ConditionFlowNode implements ConditionNode {

    /**
    * 当前数据取值标识
    */
    private static final String KEY_CURRENT = "current";

    /**
    * 求值条件判断结果。
    *
    * <p>支持三种判断模式：</p>
    * <ul>
    *   <li>{@code key + equals} — 比较上下文属性是否等于指定值</li>
    *   <li>{@code key + notEmpty} — 判断上下文属性是否非空</li>
    *   <li>仅 {@code key} — 默认判断上下文属性是否存在且非空</li>
    * </ul>
    *
    * @param context 当前流程上下文
    * @return 条件判断结果
    */
    @Override
    public boolean test(FlowContext context) {
        FlowProps props = context.currentNodeProps();
        String key = props.getString("key");
        Object value = resolveValue(key, context);
        if (props.has("equals")) {
            Object expected = props.get("equals");
            return expected != null ? expected.equals(value) : value == null;
        }
        Boolean notEmpty = props.getBoolean("notEmpty");
        boolean requireNotEmpty = notEmpty == null || notEmpty;
        return requireNotEmpty ? value != null : value == null;
    }

    /**
    * 解析取值来源。
    *
    * <p>键为 "current" 时取当前处理数据，否则取实例上下文属性。</p>
    *
    * @param key     取值键
    * @param context 当前流程上下文
    * @return 取值结果
    */
    private Object resolveValue(String key, FlowContext context) {
        if (key == null) {
            return null;
        }
        if (KEY_CURRENT.equals(key)) {
            return context.getData();
        }
        return context.getAttribute(key);
    }
}
