package com.chua.deeplearning.support.weka.rf;

import com.chua.deeplearning.support.weka.WekaException;
import com.chua.deeplearning.support.weka.data.FeatureColumn;
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
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.Instance;

/**
 * 随机森林模型包装（基于 Weka {@link RandomForest}）。
 *
 * <p>持有训练好的分类器与训练参数、特征快照、名义取值快照，
 * 支持 JDK 序列化落盘 / 恢复，以及单条、批量预测与特征重要性分析。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RandomForestModel implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 底层 Weka 随机森林分类器 */
    private final RandomForest rf;

    /** 训练参数快照 */
    private final RandomForestOptions options;

    /** 特征列定义（与训练数据顺序一致） */
    private final List<FeatureColumn> features;

    /** 目标列名（标签列或回归目标列） */
    private final String targetName;

    /** 是否回归模型（目标为数值） */
    private final boolean regression;

    /** 名义列（含目标列）取值快照，预测时按相同顺序构建实例 */
    private final Map<String, List<String>> nominalValues;

    /** 创建时间戳（毫秒） */
    private final long createdAtMillis;

    private RandomForestModel(RandomForest rf, RandomForestOptions options, List<FeatureColumn> features,
            String targetName, boolean regression, Map<String, List<String>> nominalValues) {
        this.rf = rf;
        this.options = options;
        this.features = List.copyOf(features);
        this.targetName = targetName;
        this.regression = regression;
        this.nominalValues = Map.copyOf(nominalValues);
        this.createdAtMillis = System.currentTimeMillis();
    }

    /**
     * 创建空壳（未训练）随机森林模型。
     *
     * @param options       训练参数
     * @param regression    是否回归
     * @param features      特征列定义
     * @param targetName    目标列名
     * @param nominalValues 名义取值快照
     * @return 模型实例
     */
    public static RandomForestModel create(RandomForestOptions options, boolean regression,
            List<FeatureColumn> features, String targetName, Map<String, List<String>> nominalValues) {
        RandomForest rf = new RandomForest();
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
        return new RandomForestModel(rf, options, features, targetName, regression, nominalValues);
    }

    /**
     * 训练模型。
     *
     * @param trainingData 训练实例
     * @throws WekaException 训练失败
     */
    public void train(Instances trainingData) {
        try {
            rf.buildClassifier(trainingData);
        } catch (Exception e) {
            throw new WekaException("随机森林训练失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按模型快照构建预测实例。
     *
     * @param rows 预测数据行
     * @return 实例容器
     */
    public Instances instancesFor(List<Map<String, Object>> rows) {
        ArrayList<Attribute> attrs = new ArrayList<>(features.size() + 1);
        for (FeatureColumn feature : features) {
            if (feature.getType() == FeatureColumn.FeatureType.NUMERIC) {
                attrs.add(new Attribute(feature.getName()));
            } else {
                attrs.add(new Attribute(feature.getName(), nominalValues.getOrDefault(feature.getName(), List.of(""))));
            }
        }
        if (regression) {
            attrs.add(new Attribute(targetName));
        } else {
            attrs.add(new Attribute(targetName, nominalValues.getOrDefault(targetName, List.of(""))));
        }
        Instances ins = new Instances("RandomForestModel", attrs, 0);
        ins.setClassIndex(attrs.size() - 1);
        for (Map<String, Object> row : rows) {
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
        try {
            return rf.classifyInstance(instance);
        } catch (Exception e) {
            throw new WekaException("随机森林预测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 单条预测的概率分布。
     *
     * @param instance 预测实例
     * @return 类别概率分布，回归场景为 {@code null}
     * @throws WekaException 预测失败
     */
    public double[] predictDistribution(Instance instance) {
        try {
            return rf.distributionForInstance(instance);
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
        double[] raw;
        try {
            raw = rf.computeAverageImpurityDecreasePerAttribute(new double[trainingData.numAttributes()]);
        } catch (weka.core.WekaException e) {
            throw new WekaException("特征重要性计算失败: " + e.getMessage(), e);
        }
        double max = 0;
        for (int i = 0; i < features.size(); i++) {
            max = Math.max(max, raw[i]);
        }
        List<FeatureImportance> unranked = new ArrayList<>(features.size());
        for (int i = 0; i < features.size(); i++) {
            unranked.add(new FeatureImportance(
                    features.get(i).getName(),
                    raw[i],
                    max > 0 ? raw[i] / max : 0.0));
        }
        unranked.sort(Comparator.comparingDouble(FeatureImportance::getImportance).reversed());
        List<FeatureImportance> ranked = new ArrayList<>(unranked.size());
        for (int i = 0; i < unranked.size(); i++) {
            FeatureImportance item = unranked.get(i);
            ranked.add(new FeatureImportance(item.getFeature(), item.getImportance(),
                    item.getNormalizedImportance(), i + 1));
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
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file.toFile()))) {
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
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file.toFile()))) {
            return (RandomForestModel) ois.readObject();
        } catch (Exception e) {
            throw new WekaException("模型加载失败: " + file, e);
        }
    }

    /**
     * @return 底层 Weka 分类器
     */
    public RandomForest classifier() {
        return rf;
    }

    /**
     * @return 训练参数快照
     */
    public RandomForestOptions getOptions() {
        return options;
    }

    /**
     * @return 特征列定义
     */
    public List<FeatureColumn> getFeatures() {
        return features;
    }

    /**
     * @return 目标列名
     */
    public String getTargetName() {
        return targetName;
    }

    /**
     * @return 是否回归模型
     */
    public boolean isRegression() {
        return regression;
    }

    /**
     * @return 名义取值快照
     */
    public Map<String, List<String>> getNominalValues() {
        return nominalValues;
    }

    /**
     * @return 创建时间戳（毫秒）
     */
    public long getCreatedAtMillis() {
        return createdAtMillis;
    }
}
