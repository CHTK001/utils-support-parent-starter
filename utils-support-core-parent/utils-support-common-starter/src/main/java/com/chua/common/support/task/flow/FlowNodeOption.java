package com.chua.common.support.task.flow;

import java.util.Objects;

/**
 * 节点配置字段候选选项。
 *
 * <p>用于 {@link FlowNodeField} 类型为 {@code select} 的下拉候选列表。</p>
 *
 * @param label 展示文案
 * @param value 提交值
 * @author CH
 * @since 4.0.0.42
 */
public record FlowNodeOption(
        String label,
        Object value
) {

    /**
     * 规范构造器：展示文案为空值敌对。
     *
     * <p>value class 前置条件——引用组件不接受 null。
     * 展示文案是下拉选项的必有内容；{@code value} 是提交值，
     * 允许为 null（表示提交空值），不校验。</p>
     *
     * @param label 展示文案
     * @param value 提交值，可为 null
     */
    public FlowNodeOption {
        label = Objects.requireNonNull(label, "label 不能为 null");
    }

    /**
     * 创建选项。
     *
     * @param label 展示文案
     * @param value 提交值
     * @return 选项
     */
    public static FlowNodeOption of(String label, Object value) {
        return new FlowNodeOption(label, value);
    }
}
