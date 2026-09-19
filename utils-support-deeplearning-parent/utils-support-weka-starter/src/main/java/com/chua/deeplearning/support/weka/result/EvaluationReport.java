package com.chua.deeplearning.support.weka.result;

/**
 * 模型评估报告。
 *
 * <p>评估方式为 K 折交叉验证：
 * 分类场景输出准确率与 Kappa，回归场景输出 RMSE 与 MAE。</p>
 *
 * @param regression   是否回归评估
 * @param numInstances 数据实例总数
 * @param numFolds     交叉验证折数
 * @param accuracyPct  分类准确率（%），回归场景为 0
 * @param kappa        Kappa 一致性系数，回归场景为 0
 * @param rmse         均方根误差（回归），分类场景为 0
 * @param mae          平均绝对误差（回归），分类场景为 0
 * @param durationMs   评估耗时（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record EvaluationReport(boolean regression, int numInstances, int numFolds,
        double accuracyPct, double kappa, double rmse, double mae, long durationMs) {

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
