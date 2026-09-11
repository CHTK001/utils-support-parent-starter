package com.chua.deeplearning.support.weka.result;

import lombok.Getter;

/**
 * 模型评估报告。
 *
 * <p>评估方式为 K 折交叉验证：
 * 分类场景输出准确率与 Kappa，回归场景输出 RMSE 与 MAE。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public final class EvaluationReport {

    /** 是否回归评估 */
    private final boolean regression;

    /** 数据实例总数 */
    private final int numInstances;

    /** 交叉验证折数 */
    private final int numFolds;

    /** 分类准确率（%），回归场景为 0 */
    private final double accuracyPct;

    /** Kappa 一致性系数，回归场景为 0 */
    private final double kappa;

    /** 均方根误差（回归），分类场景为 0 */
    private final double rmse;

    /** 平均绝对误差（回归），分类场景为 0 */
    private final double mae;

    /** 评估耗时（毫秒） */
    private final long durationMs;

    /**
     * 构造评估报告。
     *
     * @param regression   是否回归评估
     * @param numInstances 数据实例总数
     * @param numFolds     交叉验证折数
     * @param accuracyPct  分类准确率（%）
     * @param kappa        Kappa 系数
     * @param rmse         均方根误差
     * @param mae          平均绝对误差
     * @param durationMs   评估耗时（毫秒）
     */
    public EvaluationReport(boolean regression, int numInstances, int numFolds,
            double accuracyPct, double kappa, double rmse, double mae, long durationMs) {
        this.regression = regression;
        this.numInstances = numInstances;
        this.numFolds = numFolds;
        this.accuracyPct = accuracyPct;
        this.kappa = kappa;
        this.rmse = rmse;
        this.mae = mae;
        this.durationMs = durationMs;
    }

    @Override
    public String toString() {
        if (regression) {
            return "回归评估: 实例=" + numInstances + ", 折数=" + numFolds
                    + ", rmse=" + String.format("%.4f", rmse) + ", mae=" + String.format("%.4f", mae)
                    + ", 耗时=" + durationMs + "ms";
        }
        return "分类评估: 实例=" + numInstances + ", 折数=" + numFolds
                + ", 准确率=" + String.format("%.2f", accuracyPct) + "%"
                + ", kappa=" + String.format("%.4f", kappa) + ", 耗时=" + durationMs + "ms";
    }
}
