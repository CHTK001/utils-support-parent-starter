package com.chua.common.support.ai.calibration;

/**
 * 纯分数校准器接口
 * <p>
 * 职责：将原始相似度分数（通常0~1）映射为0~100的校准分。
 * 不含任何训练/拟合方法，参数通过构造函数或builder注入。
 * @author CH
 */
public interface PureCalibrator {

    /**
     * 对单个原始分数进行校准
     *
     * @param rawScore 原始相似度分数，取值范围通常在0~1之间
     * @return 校准后的分数，取值范围0~100
     */
    double calibrate(double rawScore);

    /**
     * 批量校准
     *
     * @param rawScores 原始分数数组，每个元素取值范围通常在0~1之间
     * @return 校准后的分数数组，每个元素取值范围0~100
     */
    default double[] calibrateBatch(double[] rawScores) {
        double[] result = new double[rawScores.length];
        for (int i = 0; i < rawScores.length; i++) {
            result[i] = calibrate(rawScores[i]);
        }
        return result;
    }

    /**
     * 获取校准器名称
     *
     * @return 校准器名称字符串
     */
    String getName();

    /**
     * 获取校准器描述
     *
     * @return 校准器功能描述字符串
     */
    String getDescription();
}