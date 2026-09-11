package com.chua.deeplearning.support.weka.rf;

import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.result.ClassificationResult;
import com.chua.deeplearning.support.weka.result.EvaluationReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import weka.classifiers.evaluation.Evaluation;
import weka.core.Instances;
import weka.core.Instance;

/**
 * 随机森林分类场景。
 *
 * <p>输入：带标签数据（{@link WekaInstanceData#classification}），输出：模型、分类预测结果、评估报告。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WekaRandomForestClassifier {

    /**
     * 训练分类模型。
     *
     * @param data    带标签的数据
     * @param options 随机森林参数，传 {@code null} 使用默认值
     * @return 训练完成的模型
     * @throws WekaException 数据缺少标签列或训练失败
     */
    public RandomForestModel train(WekaInstanceData data, RandomForestOptions options) {
        if (!data.hasLabel()) {
            throw new WekaException("分类场景需要标签列: withLabelColumn(...)");
        }
        RandomForestModel model = RandomForestModel.create(
                options == null ? RandomForestOptions.defaults() : options,
                false, data.getFeatures(), data.getLabelColumn(), data.nominalValues());
        model.train(data.toWekaInstances());
        return model;
    }

    /**
     * 预测单条数据。
     *
     * @param model 已训练模型
     * @param row   预测数据行（列名 -> 值，可缺省标签列）
     * @return 分类结果（标签 + 概率分布）
     * @throws WekaException 模型未训练或预测失败
     */
    public ClassificationResult predict(RandomForestModel model, Map<String, Object> row) {
        Instances ins = model.instancesFor(List.of(row));
        Instance instance = ins.instance(0);
        double[] distribution = model.predictDistribution(instance);
        List<String> labels = model.getNominalValues().getOrDefault(model.getTargetName(), List.of());
        if (distribution == null) {
            int index = (int) model.predictRaw(instance);
            return new ClassificationResult(labels.get(index), index, 1.0, Map.of());
        }
        int index = 0;
        for (int i = 1; i < distribution.length; i++) {
            if (distribution[i] > distribution[index]) {
                index = i;
            }
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            probabilities.put(labels.get(i), distribution[i]);
        }
        return new ClassificationResult(labels.get(index), index, distribution[index], probabilities);
    }

    /**
     * 批量预测（单次构建实例，逐条取分布）。
     *
     * @param model 已训练模型
     * @param rows  预测数据行
     * @return 分类结果列表（与输入顺序一致）
     * @throws WekaException 模型未训练或预测失败
     */
    public List<ClassificationResult> predictBatch(RandomForestModel model, List<Map<String, Object>> rows) {
        Instances ins = model.instancesFor(rows);
        double[][] distributions;
        try {
            distributions = model.classifier().distributionsForInstances(ins);
        } catch (Exception e) {
            throw new WekaException("随机森林批量预测失败: " + e.getMessage(), e);
        }
        List<String> labels = model.getNominalValues().getOrDefault(model.getTargetName(), List.of());
        List<ClassificationResult> results = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            double[] distribution = distributions[i];
            if (distribution == null) {
                int index = (int) model.predictRaw(ins.instance(i));
                results.add(new ClassificationResult(labels.get(index), index, 1.0, Map.of()));
                continue;
            }
            int index = 0;
            for (int c = 1; c < distribution.length; c++) {
                if (distribution[c] > distribution[index]) {
                    index = c;
                }
            }
            Map<String, Double> probabilities = new LinkedHashMap<>();
            for (int c = 0; c < labels.size(); c++) {
                probabilities.put(labels.get(c), distribution[c]);
            }
            results.add(new ClassificationResult(labels.get(index), index, distribution[index], probabilities));
        }
        return results;
    }

    /**
     * 评估模型（K 折交叉验证，默认 10 折）。
     *
     * @param model 已训练模型
     * @param data  评估数据
     * @return 评估报告（准确率 + Kappa）
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
            return new EvaluationReport(false, ins.numInstances(), folds,
                    evaluation.pctCorrect(), evaluation.kappa(), 0, 0, System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new WekaException("模型评估失败: " + e.getMessage(), e);
        }
    }
}
