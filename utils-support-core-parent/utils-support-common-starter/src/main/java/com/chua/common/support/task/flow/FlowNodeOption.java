package com.chua.common.support.task.flow;

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
