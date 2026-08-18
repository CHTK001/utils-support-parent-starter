package com.chua.deeplearning.support.ocr;

/**
 * OCR 方向检测结果。
 *
 * <p>描述方向分类模型的输出，包含方向名称（如 "0"、"180"）和对应概率。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DirectionInfo {

    /**
     * 方向名称（"0" / "90" / "180" / "270"）
     */
    private final String name;

    /**
     * 对应概率（0~1）
     */
    private final double probability;

    /**
     * 构造方向检测结果。
     *
     * @param name        方向名称
     * @param probability 对应概率
     */
    public DirectionInfo(String name, double probability) {
        this.name = name;
        this.probability = probability;
    }

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