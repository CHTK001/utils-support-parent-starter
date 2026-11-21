package com.chua.deeplearning.support.ocr;

/**
 * OCR 方向检测结果。
 *
 * <p>描述方向分类模型的输出，包含方向名称（如 "0"、"180"）和对应概率。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public record DirectionInfo(String name, double probability) {

    /**
     * 获取方向名称。
     *
     * @return 方向名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取对应概率。
     *
     * @return 概率值
     */
    public double getProbability() {
        return probability;
    }
}
