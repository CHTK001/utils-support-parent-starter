package com.chua.deeplearning.support.weka.rf;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.classifier.ClassifierTask;
import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.FeatureColumn;
import com.chua.deeplearning.support.weka.data.ModelDomain;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.result.ClassificationResult;
import com.chua.deeplearning.support.weka.result.EvaluationReport;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import weka.classifiers.evaluation.Evaluation;
import weka.core.Instances;

/**
 * 随机森林分类场景。
 *
 * <p>输入：带标签数据（{@link WekaInstanceData#classification}），输出：模型、分类预测结果、评估报告。
 * 同时实现 {@link ClassifierTask} SPI（扩展名 {@code weka-random-forest}），
 * 调用方可通过接口 + 自动特征类型推断使用，无需手工声明特征列。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 1. 构建训练数据：特征列 + 行数据（列名 -> 值）+ 标签列
 * List<FeatureColumn> features = List.of(
 *         FeatureColumn.numeric("age"),
 *         FeatureColumn.categorical("city"));
 * List<Map<String, Object>> rows = List.of(
 *         Map.of("age", 35, "city", "北京", "label", "high"),
 *         Map.of("age", 22, "city", "上海", "label", "low"));
 * WekaInstanceData data = WekaInstanceData.classification(features, "label", rows);
 *
 * // 2. 训练（options 可传 null 使用默认参数）
 * WekaRandomForestClassifier classifier = new WekaRandomForestClassifier();
 * RandomForestModel model = classifier.train(data, RandomForestOptions.defaults());
 *
 * // 3. 单条 / 批量预测
 * ClassificationResult one = classifier.predict(model, Map.of("age", 41, "city", "广州"));
 * one.label();                 // 预测标签
 * one.probabilities();        // 各类别概率
 *
 * // 4. 评估（K 折交叉验证，默认 10 折）
 * EvaluationReport report = classifier.evaluate(model, data);
 * report.accuracyPct();       // 准确率（%）
 * report.kappa();            // Kappa 一致性
 *
 * // 5. 模型落盘 / 恢复
 * model.save(Path.of("rf-model.ser"));
 * RandomForestModel loaded = RandomForestModel.load(Path.of("rf-model.ser"));
 * }</pre>
 *
 * @see <a href="https://www.cs.waikato.ac.nz/ml/weka/">Weka 官方文档</a>
 * @author CH
 * @since 4.0.0.42
 */
@Spi("weka-random-forest")
public class WekaRandomForestClassifier implements ClassifierTask, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 训练分类模型。
     *
     * @param data    带标签的数据
     * @param options 随机森林参数，传 {@code null} 使用默认值
     * @return 训练完成的模型
     * @throws WekaException 数据缺少标签列、数据行不足或训练失败
     */
    public RandomForestModel train(WekaInstanceData data, RandomForestOptions options) {
        Objects.requireNonNull(data, "data must not be null");
        if (!data.hasLabel()) {
            throw new WekaException("分类场景需要标签列: withLabelColumn(...)");
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
     * @param row   预测数据行（列名 -> 值，可缺省标签列）
     * @return 分类结果（标签 + 概率分布）
     * @throws WekaException 模型未训练或预测失败
     */
    public ClassificationResult predict(RandomForestModel model, Map<String, Object> row) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(row, "row must not be null");
        var instances = model.instancesFor(List.of(row));
        var instance = instances.instance(0);
        var labels = model.getDomain().nominalValues().getOrDefault(model.getDomain().targetName(), List.of());
        var distribution = model.predictDistribution(instance);
        if (distribution.isEmpty()) {
            var index = (int) model.predictRaw(instance);
            return new ClassificationResult(labels.get(index), index, 1.0, Map.of());
        }
        var dist = distribution.get();
        var index = 0;
        for (int i = 1; i < dist.length; i++) {
            if (dist[i] > dist[index]) {
                index = i;
            }
        }
        var probabilities = new LinkedHashMap<String, Double>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            probabilities.put(labels.get(i), dist[i]);
        }
        return new ClassificationResult(labels.get(index), index, dist[index], probabilities);
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
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(rows, "rows must not be null");
        var instances = model.instancesFor(rows);
        double[][] distributions;
        try {
            distributions = model.getForest().distributionsForInstances(instances);
        } catch (Exception e) {
            throw new WekaException("随机森林批量预测失败: " + e.getMessage(), e);
        }
        var labels = model.getDomain().nominalValues().getOrDefault(model.getDomain().targetName(), List.of());
        var results = new ArrayList<ClassificationResult>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var distribution = distributions[i];
            if (distribution == null) {
                var index = (int) model.predictRaw(instances.instance(i));
                results.add(new ClassificationResult(labels.get(index), index, 1.0, Map.of()));
                continue;
            }
            var best = 0;
            for (int c = 1; c < distribution.length; c++) {
                if (distribution[c] > distribution[best]) {
                    best = c;
                }
            }
            var probabilities = new LinkedHashMap<String, Double>(labels.size());
            for (int c = 0; c < labels.size(); c++) {
                probabilities.put(labels.get(c), distribution[c]);
            }
            results.add(new ClassificationResult(labels.get(best), best, distribution[best], probabilities));
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
            return new EvaluationReport(false, instances.numInstances(), folds,
                    evaluation.pctCorrect(), evaluation.kappa(), 0, 0, System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new WekaException("模型评估失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过 {@link ClassifierTask} SPI 训练（自动推断特征列类型）。
     *
     * <p>特征类型推断规则：某列全部非空值均为 {@link Number}（或可解析为数值的字符串）时为数值列，
     * 否则为类别列；行数据中的动态列结构属运行时 schema，故以 Map 承载（P3C 动态场景豁免）。</p>
     *
     * @param labelColumn 标签列名，不能为 null / 空白
     * @param samples    样本行（列名 -> 值），至少 2 行
     * @return SPI 模型（可对预测行做预测 / 评估 / 保存）
     * @throws WekaException 参数非法或训练失败
     */
    @Override
    public Model train(String labelColumn, List<Map<String, Object>> samples) {
        Objects.requireNonNull(labelColumn, "labelColumn must not be null");
        if (labelColumn.isBlank()) {
            throw new WekaException("标签列名不能为空白");
        }
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.size() < 2) {
            throw new WekaException("样本行数不足: 至少 2 行，实际 " + samples.size());
        }
        var features = inferFeatures(samples, labelColumn);
        var model = train(WekaInstanceData.classification(features, labelColumn, samples),
                RandomForestOptions.defaults());
        return new TaskModel(this, model);
    }

    /**
     * 从样本行推断特征列定义（排除标签列，保持首次出现顺序）。
     *
     * @param samples    样本行
     * @param labelColumn 标签列名（排除项）
     * @return 特征列定义列表
     */
    private static List<FeatureColumn> inferFeatures(List<Map<String, Object>> samples, String labelColumn) {
        var names = new LinkedHashSet<String>();
        for (var row : samples) {
            if (row != null) {
                row.keySet().stream().filter(name -> !name.equals(labelColumn)).forEach(names::add);
            }
        }
        if (names.isEmpty()) {
            throw new WekaException("样本行不包含任何特征列（仅标签列?）");
        }
        var features = new ArrayList<FeatureColumn>(names.size());
        for (var name : names) {
            var numeric = samples.stream()
                    .filter(row -> row != null && row.get(name) != null)
                    .map(row -> row.get(name))
                    .allMatch(WekaRandomForestClassifier::isNumericValue);
            features.add(numeric ? FeatureColumn.numeric(name) : FeatureColumn.categorical(name));
        }
        return features;
    }

    /**
     * 判断单个值是否可作为数值列内容（Number 或可解析的字符串）。
     *
     * @param value 原始值
     * @return true 表示数值语义
     */
    private static boolean isNumericValue(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String text) {
            try {
                Double.parseDouble(text.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * {@link ClassifierTask.Model} 适配器：将 {@link RandomForestModel} 能力透出为 SPI 模型。
     *
     * <p>仅持有无状态任务实例与可序列化模型，整体可随 SPI 契约 {@link Serializable}。</p>
     */
    private static final class TaskModel implements Model, Serializable {

        private static final long serialVersionUID = 1L;

        /** 无状态分类场景对象（可序列化） */
        private final WekaRandomForestClassifier task;

        /** 训练好的模型 */
        private final RandomForestModel model;

        private TaskModel(WekaRandomForestClassifier task, RandomForestModel model) {
            this.task = task;
            this.model = model;
        }

        @Override
        public Result predict(Map<String, Object> row) {
            var result = task.predict(model, row);
            return new Result(result.label(), result.confidence(), result.probabilities());
        }

        @Override
        public List<Result> predictBatch(List<Map<String, Object>> rows) {
            Objects.requireNonNull(rows, "rows must not be null");
            var results = new ArrayList<Result>(rows.size());
            for (var row : task.predictBatch(model, rows)) {
                results.add(new Result(row.label(), row.confidence(), row.probabilities()));
            }
            return results;
        }

        @Override
        public Report evaluate(List<Map<String, Object>> samples) {
            Objects.requireNonNull(samples, "samples must not be null");
            var domain = model.getDomain();
            var data = WekaInstanceData.classification(domain.features(), domain.targetName(), samples);
            var report = task.evaluate(model, data);
            return new Report(report.numInstances(), report.numFolds(), report.accuracyPct(), report.kappa());
        }

        @Override
        public void save(Path file) {
            model.save(file);
        }
    }
}
