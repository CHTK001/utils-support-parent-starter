package com.chua.deeplearning.support.weka.rf;

import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.result.EvaluationReport;
import com.chua.deeplearning.support.weka.result.RegressionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import weka.classifiers.evaluation.Evaluation;
import weka.core.Instances;
import weka.core.Instance;

/**
 * 随机森林回归场景。
 *
 * <p>输入：带数值目标的数据（{@link WekaInstanceData#regression}），输出：模型、回归预测值、评估报告。</p>
 *
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
     * @throws WekaException 数据缺少目标列或训练失败
     */
    public RandomForestModel train(WekaInstanceData data, RandomForestOptions options) {
        if (!data.hasTarget()) {
            throw new WekaException("回归场景需要目标列: withTargetColumn(...)");
        }
        RandomForestModel model = RandomForestModel.create(
                options == null ? RandomForestOptions.defaults() : options,
                true, data.getFeatures(), data.getTargetColumn(), data.nominalValues());
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
        Instances ins = model.instancesFor(List.of(row));
        return new RegressionResult(model.predictRaw(ins.instance(0)));
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
        Instances ins = model.instancesFor(rows);
        List<RegressionResult> results = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            results.add(new RegressionResult(model.predictRaw(ins.instance(i))));
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
     * @throws WekaException 评估失败
     */
    public EvaluationReport evaluate(RandomForestModel model, WekaInstanceData data, int numFolds) {
        Instances ins = data.toWekaInstances();
        int folds = Math.max(2, Math.min(numFolds, ins.numInstances()));
        if (folds > ins.numInstances()) {
            throw new WekaException("数据量不足，无法进行 " + folds + " 折交叉验证");
        }
        long start = System.currentTimeMillis();
        try {
            Evaluation evaluation = new Evaluation(ins);
            evaluation.crossValidateModel(model.classifier(), ins, folds,
                    new Random(model.getOptions().getSeed()));
            return new EvaluationReport(true, ins.numInstances(), folds,
                    0, 0, evaluation.rootMeanSquaredError(), evaluation.meanAbsoluteError(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new WekaException("模型评估失败: " + e.getMessage(), e);
        }
    }
}
