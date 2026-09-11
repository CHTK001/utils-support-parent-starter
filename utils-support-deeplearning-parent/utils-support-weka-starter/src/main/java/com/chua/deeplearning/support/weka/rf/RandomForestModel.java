package com.chua.deeplearning.support.weka.rf;

import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.FeatureColumn;
import com.chua.deeplearning.support.weka.data.ModelDomain;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.result.FeatureImportance;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.Instance;

/**
 * 随机森林模型包装（基于 Weka {@link RandomForest}）。
 *
 * <p>持有训练好的分类器与训练参数、建模域快照，
 * 支持 JDK 序列化落盘 / 恢复，以及单条、批量预测与特征重要性分析。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * RandomForestModel model = new WekaRandomForestClassifier().train(data, RandomForestOptions.defaults());
 * model.predictRaw(instance);          // 底层原始预测（分类=标签索引 / 回归=数值）
 * model.predictDistribution(instance); // 类别概率分布（回归为 Optional.empty）
 * model.featureImportances(trainIns);  // 特征重要性排名
 * model.save(Path.of("model.ser"));    // 序列化保存
 * RandomForestModel loaded = RandomForestModel.load(Path.of("model.ser"));
 * }</pre>
 *
 * @see <a href="https://www.cs.waikato.ac.nz/ml/weka/">Weka 官方文档</a>
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public final class RandomForestModel implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 底层 Weka 随机森林分类器 */
    private final RandomForest forest;

    /** 训练参数快照 */
    private final RandomForestOptions options;

    /** 建模域快照（特征列 / 目标列 / 回归标记 / 名义取值） */
    private final ModelDomain domain;

    /** 创建时间戳（毫秒） */
    private final long createdAtMillis;

    private RandomForestModel(RandomForest forest, RandomForestOptions options, ModelDomain domain) {
        this.forest = forest;
        this.options = options;
        this.domain = domain;
        this.createdAtMillis = System.currentTimeMillis();
    }

    /**
     * 创建空壳（未训练）随机森林模型。
     *
     * @param options 训练参数
     * @param domain  建模域快照
     * @return 模型实例
     */
    public static RandomForestModel create(RandomForestOptions options, ModelDomain domain) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
        var rf = new RandomForest();
        rf.setNumIterations(options.getNumTrees());
        rf.setSeed(options.getSeed());
        if (options.getBagSizePercent() > 0) {
            rf.setBagSizePercent(options.getBagSizePercent());
        }
        if (options.getNumFeatures() > 0) {
            rf.setNumFeatures(options.getNumFeatures());
        }
        if (options.getMaxDepth() > 0) {
            rf.setMaxDepth(options.getMaxDepth());
        }
        rf.setBreakTiesRandomly(options.isBreakTiesRandomly());
        rf.setComputeAttributeImportance(true);
        return new RandomForestModel(rf, options, domain);
    }

    /**
     * 训练模型。
     *
     * @param trainingData 训练实例
     * @throws WekaException 训练失败
     */
    public void train(Instances trainingData) {
        Objects.requireNonNull(trainingData, "trainingData must not be null");
        try {
            forest.buildClassifier(trainingData);
        } catch (Exception e) {
            throw new WekaException("随机森林训练失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按建模域快照构建预测实例。
     *
     * @param rows 预测数据行
     * @return 实例容器
     */
    public Instances instancesFor(List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "rows must not be null");
        var attrs = new ArrayList<Attribute>(domain.features().size() + 1);
        for (var feature : domain.features()) {
            if (feature.getType() == FeatureColumn.FeatureType.NUMERIC) {
                attrs.add(new Attribute(feature.getName()));
            } else {
                attrs.add(new Attribute(feature.getName(),
                        domain.nominalValues().getOrDefault(feature.getName(), List.of(""))));
            }
        }
        if (domain.regression()) {
            attrs.add(new Attribute(domain.targetName()));
        } else {
            attrs.add(new Attribute(domain.targetName(),
                    domain.nominalValues().getOrDefault(domain.targetName(), List.of(""))));
        }
        var ins = new Instances("RandomForestModel", attrs, 0);
        ins.setClassIndex(attrs.size() - 1);
        for (var row : rows) {
            ins.add(new DenseInstance(1.0, WekaInstanceData.toAttributeValues(ins, row)));
        }
        return ins;
    }

    /**
     * 单条原始预测。
     *
     * @param instance 预测实例
     * @return 分类场景为标签索引，回归场景为预测值
     * @throws WekaException 预测失败
     */
    public double predictRaw(Instance instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        try {
            return forest.classifyInstance(instance);
        } catch (Exception e) {
            throw new WekaException("随机森林预测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 单条预测的概率分布。
     *
     * @param instance 预测实例
     * @return 类别概率分布；回归场景无分布，返回 {@link Optional#empty()}
     * @throws WekaException 预测失败
     */
    public Optional<double[]> predictDistribution(Instance instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        try {
            return Optional.ofNullable(forest.distributionForInstance(instance));
        } catch (Exception e) {
            throw new WekaException("随机森林预测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算特征重要性（平均不纯度下降，并按排名归一化）。
     *
     * @param trainingData 训练实例
     * @return 按重要性降序排列的重要性列表
     * @throws WekaException 计算失败
     */
    public List<FeatureImportance> featureImportances(Instances trainingData) {
        Objects.requireNonNull(trainingData, "trainingData must not be null");
        double[] raw;
        try {
            raw = forest.computeAverageImpurityDecreasePerAttribute(new double[trainingData.numAttributes()]);
        } catch (weka.core.WekaException e) {
            throw new WekaException("特征重要性计算失败: " + e.getMessage(), e);
        }
        double max = 0;
        for (int i = 0; i < domain.features().size(); i++) {
            max = Math.max(max, raw[i]);
        }
        var unranked = new ArrayList<FeatureImportance>(domain.features().size());
        for (int i = 0; i < domain.features().size(); i++) {
            unranked.add(new FeatureImportance(domain.features().get(i).getName(), raw[i],
                    max > 0 ? raw[i] / max : 0.0, 0));
        }
        unranked.sort(Comparator.comparingDouble(FeatureImportance::importance).reversed());
        var ranked = new ArrayList<FeatureImportance>(unranked.size());
        for (int i = 0; i < unranked.size(); i++) {
            var item = unranked.get(i);
            ranked.add(new FeatureImportance(item.feature(), item.importance(),
                    item.normalizedImportance(), i + 1));
        }
        return ranked;
    }

    /**
     * 保存模型到磁盘（JDK 序列化）。
     *
     * @param file 目标文件
     * @throws WekaException 写入失败
     */
    public void save(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        try (var oos = new ObjectOutputStream(new FileOutputStream(file.toFile()))) {
            oos.writeObject(this);
        } catch (IOException e) {
            throw new WekaException("模型保存失败: " + file, e);
        }
    }

    /**
     * 从磁盘加载模型。
     *
     * @param file 模型文件
     * @return 模型实例
     * @throws WekaException 读取失败或文件不是本模块序列化的模型
     */
    public static RandomForestModel load(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        try (var ois = new ObjectInputStream(new FileInputStream(file.toFile()))) {
            return (RandomForestModel) ois.readObject();
        } catch (Exception e) {
            throw new WekaException("模型加载失败: " + file, e);
        }
    }
}
