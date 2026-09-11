package com.chua.deeplearning.support.weka.rf;

import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.ModelDomain;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.result.EvaluationReport;
import com.chua.deeplearning.support.weka.result.RegressionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import weka.classifiers.evaluation.Evaluation;
import weka.core.Instances;

/**
 * 随机森林回归场景。
 *
 * <p>输入：带数值目标的数据（{@link WekaInstanceData#regression}），输出：模型、回归预测值、评估报告。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 目标列 target 为数值（销量、金额、风险分等）
 * List<FeatureColumn> features = List.of(FeatureColumn.numeric("price"));
 * List<Map<String, Object>> rows = List.of(
 *         Map.of("price", 9.9, "target", 120.5),
 *         Map.of("price", 19.9, "target", 88.2));
 * WekaInstanceData data = WekaInstanceData.regression(features, "target", rows);
 *
 * WekaRandomForestRegressor regressor = new WekaRandomForestRegressor();
 * RandomForestModel model = regressor.train(data, null);
 *
 * RegressionResult result = regressor.predict(model, Map.of("price", 12.5));
 * result.predictedValue();  // 预测值
 *
 * EvaluationReport report = regressor.evaluate(model, data);
 * report.rmse();            // 均方根误差
 * report.mae();             // 平均绝对误差
 * }</pre>
 *
 * @see <a href="https://www.cs.waikato.ac.nz/ml/weka/">Weka 官方文档</a>
 * @author CH
 * @since 4.0.0.42
 */
public class WekaRandomForestRegressor {

    /**
     * 训练回归模型。
     *
     * @param data    带目标列的数据
     * @param options 随机森林参数，传 {@code null} 使用默认值
     * @return 训练完成的模型
     * @throws WekaException 数据缺少目标列、数据行不足或训练失败
     */
    public RandomForestModel train(WekaInstanceData data, RandomForestOptions options) {
        Objects.requireNonNull(data, "data must not be null");
        if (!data.hasTarget()) {
            throw new WekaException("回归场景需要目标列: withTargetColumn(...)");
        }
        var model = RandomForestModel.create(options == null ? RandomForestOptions.defaults() : options,
                ModelDomain.of(data));
        model.train(data.toWekaInstances());
        return model;
    }

    /**
     * 预测单条数据。
     *
     * @param model 已训练模型
     * @param row   预测数据行（列名 -> 值，可缺省目标列）
     * @return 回归预测值
     * @throws WekaException 模型未训练或预测失败
     */
    public RegressionResult predict(RandomForestModel model, Map<String, Object> row) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(row, "row must not be null");
        var instances = model.instancesFor(List.of(row));
        return new RegressionResult(model.predictRaw(instances.instance(0)));
    }

    /**
     * 批量预测。
     *
     * @param model 已训练模型
     * @param rows  预测数据行
     * @return 回归预测值列表（与输入顺序一致）
     * @throws WekaException 模型未训练或预测失败
     */
    public List<RegressionResult> predictBatch(RandomForestModel model, List<Map<String, Object>> rows) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(rows, "rows must not be null");
        var instances = model.instancesFor(rows);
        var results = new ArrayList<RegressionResult>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            results.add(new RegressionResult(model.predictRaw(instances.instance(i))));
        }
        return results;
    }

    /**
     * 评估模型（K 折交叉验证，默认 10 折）。
     *
     * @param model 已训练模型
     * @param data  评估数据
     * @return 评估报告（RMSE + MAE）
     * @throws WekaException 评估失败
     */
    public EvaluationReport evaluate(RandomForestModel model, WekaInstanceData data) {
        return evaluate(model, data, 10);
    }

    /**
     * 评估模型（K 折交叉验证）。
     *
     * @param model    已训练模型
     * @param data     评估数据
     * @param numFolds 折数（至少 2，推荐 10）
     * @return 评估报告
     * @throws WekaException 数据量不足或评估失败
     */
    public EvaluationReport evaluate(RandomForestModel model, WekaInstanceData data, int numFolds) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(data, "data must not be null");
        var instances = data.toWekaInstances();
        var folds = Math.max(2, Math.min(numFolds, instances.numInstances()));
        if (folds > instances.numInstances()) {
            throw new WekaException("数据量不足，无法进行 " + folds + " 折交叉验证");
        }
        long start = System.currentTimeMillis();
        try {
            var evaluation = new Evaluation(instances);
            evaluation.crossValidateModel(model.getForest(), instances, folds,
                    new Random(model.getOptions().getSeed()));
            return new EvaluationReport(true, instances.numInstances(), folds,
                    0, 0, evaluation.rootMeanSquaredError(), evaluation.meanAbsoluteError(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new WekaException("模型评估失败: " + e.getMessage(), e);
        }
    }
}
