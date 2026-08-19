package com.chua.deeplearning.support.onnx.ocr.direction;

/**
 * OCR         
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DirectionInfo {

    /** 名称 */
    private final String name;
    /** 概率 */
    /** Probability */
    private final double probability;

    /**
     * 创建 DirectionInfo 实例
     * @param name name
     * @param double double
     */
    public DirectionInfo(String name, double probability) {
        this.name = name;
        this.probability = probability;
    }

    /** 获取Name */
    public String getName() {
        return name;
    }

    /** 获取Probability */
    public double getProbability() {
        return probability;
    }
}
