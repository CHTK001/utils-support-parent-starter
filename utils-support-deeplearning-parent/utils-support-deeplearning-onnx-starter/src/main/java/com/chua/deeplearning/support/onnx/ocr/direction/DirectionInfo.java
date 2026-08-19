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
    private final double probability;

    public DirectionInfo(String name, double probability) {
        this.name = name;
        this.probability = probability;
    }

    public String getName() {
        return name;
    }

    public double getProbability() {
        return probability;
    }
}
