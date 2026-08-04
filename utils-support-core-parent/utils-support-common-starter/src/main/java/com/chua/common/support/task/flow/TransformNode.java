package com.chua.common.support.task.flow;

/**
 * 数据转换节点接口。
 *
 * <p>从流程上下文或当前数据中取值并写入当前数据，
 * 实现节点间数据形态的简单转换，供下游节点继续消费。</p>
 *
 * <p>节点属性说明：</p>
 * <ul>
 *   <li>{@code source} — 取值来源，支持三种形式：</li>
 *   <li>　{@code current} — 取当前处理数据</li>
 *   <li>　{@code attribute:key} — 取上下文属性 key 的值</li>
 *   <li>　其它 — 视为上下文属性键直接取值</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TransformNode extends FlowNode {

    @Override
    default String type() {
        return "transform";
    }
}
