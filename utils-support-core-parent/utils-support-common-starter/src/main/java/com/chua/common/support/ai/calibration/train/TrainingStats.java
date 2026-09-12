package com.chua.common.support.ai.calibration.train;

import lombok.Builder;
import lombok.Data;

/**
* 训练效果统计（校准前后对比）。
*
* @author CH
* @since 2026/07/31
 */
@Data
@Builder
public class TrainingStats {

    /** 校准前负样本均值 */
    private double beforeNegMean;

    /** 校准前负样本标准差 */
    private double beforeNegStd;

    /** 校准前正样本均值 */
    private double beforePosMean;

    /** 校准前正样本标准差 */
    private double beforePosStd;

    /** 校准前分离度 */
    private double beforeSeparation;

    /** 校准后负样本均值 */
    private double afterNegMean;

    /** 校准后负样本标准差 */
    private double afterNegStd;

    /** 校准后正样本均值 */
    private double afterPosMean;

    /** 校准后正样本标准差 */
    private double afterPosStd;

    /** 校准后分离度 */
    private double afterSeparation;

    /** 分离度提升比例 */
    private double separationImprovement;

    /** 校准后负样本 90 分位 */
    private double afterNegPercentile90;

    /** 校准后正样本 10 分位 */
    private double afterPosPercentile10;
}
