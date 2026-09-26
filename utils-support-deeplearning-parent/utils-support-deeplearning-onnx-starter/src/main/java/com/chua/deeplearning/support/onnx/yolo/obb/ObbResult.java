package com.chua.deeplearning.support.onnx.yolo.obb;

import java.util.List;
import java.util.Objects;

/**
 * OBB
 * <p>
 *                            OBB - Oriented Bounding Box
 *
 * @param rotatedBoxList OBB
 *
 * @author CH
 * @since 2025-01-22
 */
public record ObbResult(
        List<YoloRotatedBox> rotatedBoxList
) {

    /**
     * 规范构造器：OBB 列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。两个构造点
     * （{@code Yolo26ObbTranslator} / {@code Yolo11OddTranslator}）传入的都是
     * {@code rotatedNMS} 刚产出的列表、构造后不再被修改，元素亦非 空，
     * 因此可直接 {@link List#copyOf}。</p>
     *
     * @param rotatedBoxList OBB
     */
    public ObbResult {
        rotatedBoxList = List.copyOf(Objects.requireNonNull(rotatedBoxList, "rotatedBoxList 不能为 null"));
    }
}
