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
    /** BeforeNEGmean */
    private double beforeNegMean;

    /** 校准前负样本标准差 */
    /** BeforeNEGSTD */
    private double beforeNegStd;

    /** 校准前正样本均值 */
    /** BeforePOSmean */
    private double beforePosMean;

    /** 校准前正样本标准差 */
    /** BeforePOSSTD */
    private double beforePosStd;

    /** 校准前分离度 */
    /** Beforeseparation */
    private double beforeSeparation;

    /** 校准后负样本均值 */
    /** AfterNEGmean */
    private double afterNegMean;

    /** 校准后负样本标准差 */
    /** AfterNEGSTD */
    private double afterNegStd;

    /** 校准后正样本均值 */
    /** AfterPOSmean */
    private double afterPosMean;

    /** 校准后正样本标准差 */
    /** AfterPOSSTD */
    private double afterPosStd;

    /** 校准后分离度 */
    /** Afterseparation */
    private double afterSeparation;

    /** 分离度提升比例 */
    /** Separationimprovement */
    private double separationImprovement;

    /** 校准后负样本 90 分位 */
    /** AfterNEGpercentile90 */
    private double afterNegPercentile90;

    /** 校准后正样本 10 分位 */
    /** AfterPOSpercentile10 */
    private double afterPosPercentile10;
}
