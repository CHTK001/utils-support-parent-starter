package com.chua.deeplearning.support.tinyai;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.classifier.ClassifierTask;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TinyAI 神经网络分类场景。
 *
 * <p>输入：带标签行数据（列名 -> 值），输出：模型、分类预测结果、评估报告。
 * 同时实现 {@link ClassifierTask} SPI（扩展名 {@code tinyai-mlp}），
 * 调用方可通过接口 + 自动特征类型推断使用，无需手工声明特征列。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * List<Map<String, Object>> rows = List.of(
 *         Map.of("age", 35, "city", "北京", "label", "high"),
 *         Map.of("age", 22, "city", "上海", "label", "low"));
 * TinyAiMlpClassifier classifier = new TinyAiMlpClassifier();
 * TinyAiMlpModel model = classifier.train(ModelDomain.of(rows, "label"), MlpOptions.defaults());
 *
 * var one = classifier.predict(model, Map.of("age", 41, "city", "广州"));
 * one.label();          // 预测标签
 * one.probabilities(); // 各类别概率
 *
 * var report = classifier.evaluate(model, rows);
 * report.accuracyPct(); // 准确率（%）
 *
 * model.save(Path.of("tinyai-mlp.ser"));
 * TinyAiMlpModel loaded = TinyAiMlpModel.load(Path.of("tinyai-mlp.ser"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("tinyai-mlp")
public class TinyAiMlpClassifier implements ClassifierTask, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 训练引擎（无状态，可序列化）。
     */
    private final TinyAiEngine engine = new TinyAiEngine();

    /**
     * 训练分类模型（显式样本版）。
     *
     * @param domain 数据域，不能为 null
     * @param samples 样本行，不能为 null
     * @param options 训练参数，null 用默认值
     * @return 训练完成的模型
     */
    public TinyAiMlpModel train(ModelDomain domain, List<Map<String, Object>> samples, MlpOptions options) {
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(samples, "samples must not be null");
        var opts = options == null ? MlpOptions.defaults() : options;
        try {
            return new TinyAiMlpModel(engine.train(domain, samples, opts), opts, domain);
        } catch (TinyAiException e) {
            throw e;
        } catch (Exception e) {
            throw new TinyAiException("TinyAI 训练失败: " + e.getMessage(), e);
        }
    }

    /**
     * 预测单条数据。
     *
     * @param model 已训练模型，不能为 null
     * @param row 预测数据行（可缺省标签列），不能为 null
     * @return 分类结果（标签 + 概率分布）
     */
    public TinyAiMlpModel.ClassificationResult predict(TinyAiMlpModel model, Map<String, Object> row) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(row, "row must not be null");
        try {
            return model.predict(row);
        } catch (TinyAiException e) {
            throw e;
        } catch (Exception e) {
            throw new TinyAiException("TinyAI 预测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量预测（逐条推理，保持输入顺序）。
     *
     * @param model 已训练模型，不能为 null
     * @param rows 预测数据行，不能为 null
     * @return 分类结果列表（与输入顺序一致）
     */
    public List<TinyAiMlpModel.ClassificationResult> predictBatch(TinyAiMlpModel model,
            List<Map<String, Object>> rows) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(rows, "rows must not be null");
        var results = new ArrayList<TinyAiMlpModel.ClassificationResult>(rows.size());
        for (var row : rows) {
            results.add(predict(model, row));
        }
        return results;
    }

    /**
     * 在给定样本上评估（训练集复测口径：准确率 + Kappa）。
     *
     * @param model 已训练模型，不能为 null
     * @param samples 评估样本行（须含标签列），不能为 null
     * @return 评估报告
     */
    public EvaluationReport evaluate(TinyAiMlpModel model, List<Map<String, Object>> samples) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.isEmpty()) {
            throw new TinyAiException("评估样本不能为空");
        }
        var domain = model.getDomain();
        var labels = domain.labels();
        var confusion = new int[labels.size()][labels.size()];
        var hit = 0;
        var total = 0;
        for (var row : samples) {
            if (row == null) {
                continue;
            }
            var expected = row.get(domain.targetName());
            var expectedIndex = expected == null ? -1 : domain.labelIndex(String.valueOf(expected));
            if (expectedIndex < 0) {
                continue;
            }
            var predicted = predict(model, row).labelIndex();
            confusion[expectedIndex][predicted]++;
            total++;
            if (expectedIndex == predicted) {
                hit++;
            }
        }
        if (total == 0) {
            throw new TinyAiException("评估样本无有效标签行");
        }
        var accuracy = (double) hit / total;
        return new EvaluationReport(false, total, 1, accuracy * 100.0, kappa(confusion, total), 0, 0, 0);
    }

    /**
     * K 折交叉验证评估：每折独立训练评估，汇总准确率。
     *
     * @param domain 数据域，不能为 null
     * @param samples 样本行，不能为 null
     * @param options 训练参数，null 用默认值
     * @param numFolds 折数（至少 2，超过样本数时按样本数处理）
     * @return 评估报告
     */
    public EvaluationReport crossValidate(ModelDomain domain, List<Map<String, Object>> samples,
            MlpOptions options, int numFolds) {
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(samples, "samples must not be null");
        var rows = samples.stream().filter(Objects::nonNull).toList();
        if (rows.size() < 2) {
            throw new TinyAiException("交叉验证至少需要 2 行有效样本，实际 " + rows.size());
        }
        var folds = Math.max(2, Math.min(numFolds, rows.size()));
        var hits = 0;
        var total = 0;
        for (var fold = 0; fold < folds; fold++) {
            var trainRows = new ArrayList<Map<String, Object>>();
            var testRows = new ArrayList<Map<String, Object>>();
            for (var i = 0; i < rows.size(); i++) {
                (i % folds == fold ? testRows : trainRows).add(rows.get(i));
            }
            if (trainRows.isEmpty() || testRows.isEmpty()) {
                continue;
            }
            var model = train(domain, trainRows, options);
            for (var row : testRows) {
                var expected = row.get(domain.targetName());
                if (expected == null) {
                    continue;
                }
                total++;
                if (predict(model, row).label().equals(String.valueOf(expected))) {
                    hits++;
                }
            }
        }
        if (total == 0) {
            throw new TinyAiException("交叉验证无有效评估行");
        }
        var accuracy = (double) hits / total;
        // 交叉验证不产出完整混淆矩阵，Kappa 按“相对随机猜测”近似为 0，上报准确率为主。
        return new EvaluationReport(false, total, folds, accuracy * 100.0, 0.0, 0, 0, 0);
    }

    /**
     * 通过 {@link ClassifierTask} SPI 训练（自动推断特征列类型）。
     *
     * @param labelColumn 标签列名，不能为 null / 空白
     * @param samples 样本行，至少 2 行，不能为 null
     * @return SPI 模型（可预测 / 评估 / 保存）
     */
    @Override
    public Model train(String labelColumn, List<Map<String, Object>> samples) {
        Objects.requireNonNull(labelColumn, "labelColumn must not be null");
        if (labelColumn.isBlank()) {
            throw new TinyAiException("标签列名不能为空白");
        }
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.size() < 2) {
            throw new TinyAiException("样本行数不足: 至少 2 行，实际 " + samples.size());
        }
        var domain = ModelDomain.of(samples, labelColumn);
        return new TaskModel(this, train(domain, samples, MlpOptions.defaults()));
    }

    /**
     * 由混淆矩阵算 Kappa。
     */
    private static double kappa(int[][] confusion, int total) {
        var classes = confusion.length;
        var rowSum = new int[classes];
        var colSum = new int[classes];
        var agree = 0;
        for (var i = 0; i < classes; i++) {
            for (var j = 0; j < classes; j++) {
                rowSum[i] += confusion[i][j];
                colSum[j] += confusion[i][j];
                if (i == j) {
                    agree += confusion[i][j];
                }
            }
        }
        var observed = (double) agree / total;
        var expected = 0.0;
        for (var i = 0; i < classes; i++) {
            expected += (double) rowSum[i] * colSum[i] / total / total;
        }
        return Math.abs(1.0 - expected) < 1e-12 ? 0.0 : (observed - expected) / (1.0 - expected);
    }

    /**
     * {@link ClassifierTask.Model} 适配器：将 {@link TinyAiMlpModel} 透出为 SPI 模型。
     */
    private static final class TaskModel implements Model, Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 无状态分类场景对象（可序列化）。
         */
        private final TinyAiMlpClassifier task;

        /**
         * 训练好的模型。
         */
        private final TinyAiMlpModel model;

        /**
         * 构造适配器。
         *
         * @param task 任务
         * @param model 模型
         */
        private TaskModel(TinyAiMlpClassifier task, TinyAiMlpModel model) {
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
            var report = task.evaluate(model, samples);
            return new Report(report.numInstances(), report.numFolds(), report.accuracyPct(), report.kappa());
        }

        @Override
        public void save(Path file) {
            model.save(file);
        }
    }

    /**
     * 评估报告（与 weka 模块 {@code EvaluationReport} 同构的子集）。
     *
     * @param regression 回归标记（分类恒 false）
     * @param numInstances 实例总数
     * @param numFolds 折数（复测口径为 1）
     * @param accuracyPct 准确率（%）
     * @param kappa Kappa 一致性系数
     * @param rmse 均方根误差（分类为 0）
     * @param mae 平均绝对误差（分类为 0）
     * @param elapsedMillis 耗时毫秒
     */
    public record EvaluationReport(boolean regression, int numInstances, int numFolds, double accuracyPct,
            double kappa, double rmse, double mae, long elapsedMillis) implements Serializable {

        /**
         * 兼容 weka 侧 Map 构造口径的快照。
         *
         * @return 快照
         */
        public Map<String, Object> snapshot() {
            var map = new LinkedHashMap<String, Object>();
            map.put("numInstances", numInstances);
            map.put("numFolds", numFolds);
            map.put("accuracyPct", accuracyPct);
            map.put("kappa", kappa);
            return map;
        }
    }
}
