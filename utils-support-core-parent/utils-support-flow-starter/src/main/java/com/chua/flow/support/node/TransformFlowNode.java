package com.chua.flow.support.node;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowNode;
import com.chua.common.support.task.flow.FlowNodeExecutor;
import com.chua.common.support.task.flow.FlowProps;

/**
 * 数据转换节点执行器。
 *
 * <p>从实例上下文或当前数据中取值并写入当前数据，
 * 实现节点间数据形态的简单转换。</p>
 *
 * <p>节点属性说明：</p>
 * <ul>
 *   <li>{@code source} — 取值来源，支持三种形式：</li>
 *   <li>　{@code current} — 取当前处理数据</li>
 *   <li>　{@code attribute:key} — 取上下文属性 key 的值</li>
 *   <li>　其它 — 视为上下文属性键直接取值</li>
 * </ul>
 *
 * <p>执行后将取值结果写入当前数据，供下游节点继续消费。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("transform")
@FlowNode(value = "transform", describe = "数据转换")
public class TransformFlowNode implements FlowNodeExecutor {

    /**
     * 当前数据取值标识
     */
    private static final String SOURCE_CURRENT = "current";

    /**
     * 上下文属性取值前缀
     */
    private static final String SOURCE_ATTRIBUTE_PREFIX = "attribute:";

    /**
     * 执行数据转换节点。
     *
     * <p>按 source 属性解析取值来源，将结果写入当前数据。</p>
     *
     * @param instance 当前流程实例
     */
    @Override
    public void execute(FlowInstance instance) {
        FlowProps props = instance.currentNodeProps();
        String source = props.getString("source");
        Object value = resolveSource(source, instance);
        instance.setCurrentData(value);
    }

    /**
     * 解析取值来源并返回对应值。
     *
     * @param source   取值来源标识
     * @param instance 当前流程实例
     * @return 解析后的值
     */
    private Object resolveSource(String source, FlowInstance instance) {
        if (source == null || SOURCE_CURRENT.equals(source)) {
            return instance.getCurrentData();
        }
        if (source.startsWith(SOURCE_ATTRIBUTE_PREFIX)) {
            return instance.getAttribute(source.substring(SOURCE_ATTRIBUTE_PREFIX.length()));
        }
        return instance.getAttribute(source);
    }
}
