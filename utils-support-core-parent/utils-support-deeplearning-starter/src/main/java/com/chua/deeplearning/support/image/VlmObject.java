package com.chua.deeplearning.support.image;

import com.chua.common.support.utils.StringUtils;

/**
 * 结构化视觉推理返回的单个目标。
 *
 * <p>框任务填 {@link #box()}，点任务填 {@link #point()}，二者可同时存在；
 * 计数任务的数量等于目标个数（不另设 count 字段，避免与列表长度不一致）。
 *
 * @param label 目标标签（模型未给出时统一为 {@code object}）
 * @param box   归一化框，点任务/无框任务为 {@code null}
 * @param point 归一化点，框任务/无点任务为 {@code null}
 * @author CH
 * @since 4.0.0.51
 */
public record VlmObject(String label, VlmBox box, VlmPoint point) {

    /**
     * 规范构造器：空标签归一为 {@code object}。
     *
     * @param label 目标标签
     * @param box   归一化框
     * @param point 归一化点
     */
    public VlmObject {
        label = StringUtils.isBlank(label) ? "object" : label.trim();
    }

    /**
     * 构造仅含框的目标。
     *
     * @param label 目标标签
     * @param box   归一化框
     * @return 目标
     */
    public static VlmObject ofBox(String label, VlmBox box) {
        return new VlmObject(label, box, null);
    }

    /**
     * 构造仅含点的目标。
     *
     * @param label 目标标签
     * @param point 归一化点
     * @return 目标
     */
    public static VlmObject ofPoint(String label, VlmPoint point) {
        return new VlmObject(label, null, point);
    }

    /**
     * 是否带有效框。
     *
     * @return 有框返回 true
     */
    public boolean hasBox() {
        return box != null && box.isValid();
    }

    /**
     * 是否带点。
     *
     * @return 有点返回 true
     */
    public boolean hasPoint() {
        return point != null;
    }
}