package com.chua.common.support.task.classifier;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 分类建模任务接口（SPI）。
 *
 * <p>定义「输入行数据 -> 输出预测 / 评估结果」的分类建模契约，
 * 调用方依赖本接口即可解耦具体算法实现（Weka 随机森林等模块提供实现）。
 * 行数据以「列名 -> 值」传递，特征列类型由实现从数据自动推断
 * （非空值均可解析为数值时为数值列，否则为类别列）。
 * 行数据属运行时动态 schema（各调用方列结构不同），故以 {@code Map} 承载（P3C 动态场景豁免）。</p>
 *
 * <p>使用流程：
 * <ol>
 *   <li>准备样本行：每行一个对象（Map），值可为 Number / String / null（缺失）</li>
 *   <li>{@link #train(String, List)} 训练，得到 {@link Model}</li>
 *   <li>{@link Model#predict(Map)} 预测新数据行</li>
 *   <li>{@link Model#evaluate(List)} 交叉验证评估</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ClassifierTask {

    /**
     * 训练分类模型。
     *
     * <p>实现须校验：{@code labelColumn} 非 null 非空白、{@code samples} 非 null 且至少 2 行；
     * 校验失败或训练失败时抛出实现对应的运行时异常。</p>
     *
     * @param labelColumn 标签列名（行数据中的答案列，名义值）
     * @param samples     样本行（列名 -> 值），至少 2 行且标签需有多个不同取值
     * @return 训练好的模型
     * @throws IllegalArgumentException 参数非法（标签列为空 / 样本为空）
     */
    Model train(String labelColumn, List<Map<String, Object>> samples);

    /**
     * 训练好的分类模型。
     *
     * <p>模型由 {@link #train} 产出，可预测、评估、保存；
     * 实现类须声明 {@code serialVersionUID} 支持落盘序列化。</p>
     */
    interface Model extends Serializable {

        /**
         * 预测单行数据。
         *
         * @param row 预测数据行（列名 -> 值，可缺省标签列），不能为 null
         * @return 预测结果（标签 + 置信度 + 概率分布）
         * @throws RuntimeException 实现对应的运行时异常（模型未训练或预测失败）
         */
        Result predict(Map<String, Object> row);

        /**
         * 批量预测。
         *
         * @param rows 预测数据行（与输入顺序一致），不能为 null
         * @return 预测结果列表（与输入顺序一致）
         * @throws RuntimeException 实现对应的运行时异常（模型未训练或预测失败）
         */
        List<Result> predictBatch(List<Map<String, Object>> rows);

        /**
         * 评估模型（K 折交叉验证，数据须包含标签列）。
         *
         * @param samples 评估数据行
         * @return 评估报告
         */
        Report evaluate(List<Map<String, Object>> samples);

        /**
         * 保存模型到磁盘。
         *
         * @param file 目标文件
         */
        void save(Path file);
    }

    /**
     * 分类预测结果。
     *
     * @param label         预测标签
     * @param confidence    预测置信度（0.0 ~ 1.0）
     * @param probabilities 各类别概率分布（可能为空 Map）
     */
    record Result(String label, double confidence, Map<String, Double> probabilities) {
    }

    /**
     * 模型评估报告。
     *
     * @param numInstances 数据实例总数
     * @param numFolds     交叉验证折数
     * @param accuracyPct  准确率（%）
     * @param kappa        Kappa 一致性系数
     */
    record Report(int numInstances, int numFolds, double accuracyPct, double kappa) {
    }
}
