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
    * 创建 direction信息 实例
    * @param name 名称
    * @param probability double
    * @param probability probability
    */
    public DirectionInfo(String name, double probability) {
        this.name = name;
        this.probability = probability;
    }

    /**
     * 获取名称
     *
     * @return 获取名称的结果
     */
    public String getName() {
        return name;
    }

    /**
     * 获取Probability
     *
     * @return 获取probability的结果
     */
    public double getProbability() {
        return probability;
    }
}
