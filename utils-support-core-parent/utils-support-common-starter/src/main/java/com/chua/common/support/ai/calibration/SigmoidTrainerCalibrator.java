package com.chua.common.support.ai.calibration;

import com.chua.common.support.ai.calibration.train.TrainingData;
import com.chua.common.support.ai.calibration.train.TrainingStats;
import com.chua.common.support.lang.json.Json;
import lombok.Builder;
import lombok.Getter;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
* Sigmoid 可训练校准器
* <p>
* 【用途】 实现 TrainerPureCalibrator 接口，自身就是 PureCalibrator。
* 支持生成三个目录的训练数据、自动训练拟合参数、保存/加载模型。
* <p>
* 【公式】 score' = 100 / (1 + e^{-k * (raw - t)})
* <p>
* 【参数对象】 SigmoidParams
* k – 陡度，越大过渡越陡（建议10~30）
* t – 阈值，决定分界线位置（建议0.7~0.85）
* <p>
* 【典型用法】
* <pre>
* // 方式一：生成模拟数据训练
* SigmoidTrainerCalibrator cal = SigmoidTrainerCalibrator.builder()
*     .k(15.0).t(0.75)
*     .generateTrainingData(200, 200, 400, 42L)
*     .train()
*     .saveModel("sigmoid_model.json")
*     .build();
*
* double score = cal.calibrate(0.85); // 约90分
*
* // 方式二：加载已训练模型
* SigmoidTrainerCalibrator loaded = SigmoidTrainerCalibrator.builder()
*     .loadModel("sigmoid_model.json")
*     .build();
*
* double score2 = loaded.calibrate(0.85);
* </pre>
* <p>
*
* @author CH
* @since 4.0.0.42
 */
@Getter
public class SigmoidTrainerCalibrator implements TrainerPureCalibrator {

    // ==================== 内部状态 ====================

    /**
    * 内部持有的 Sigmoid 纯校准器
     */
    private final SigmoidPureCalibrator calibrator;

    /**
    * 训练数据（三个目录的分数）
     */
    private TrainingData trainingData;

    /**
    * 训练效果统计
     */
    private TrainingStats trainingStats;

    /**
    * 是否已训练
     */
    private boolean trained = false;

    // ==================== 参数对象 ====================

    /**
    * Sigmoid 参数对象
    * <p>
    * 包含 Sigmoid 校准器的全部可调参数。
    * 用于替代 Map，明确告知用户有哪些参数可用。
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    public static class SigmoidParams {
        /**
        * 陡度参数，默认15.0
         */
        @Builder.Default
        /** K */
        private double k = 15.0;

        /**
        * 阈值参数，默认0.75
         */
        @Builder.Default
        /** T */
        private double t = 0.75;
    }

    /**
    * 当前参数
     */
    private final SigmoidParams params;

    // ==================== 构造方法 ====================

    /**
    * 私有构造方法，通过 Builder 创建
     */
    private SigmoidTrainerCalibrator(SigmoidParams params,
                                     TrainingData trainingData,
                                     boolean trained) {
        this.params = params;
        this.calibrator = new SigmoidPureCalibrator(params.getK(), params.getT());
        this.trainingData = trainingData;
        this.trained = trained;
    }

    /**
    * Builder 类
     */
    @lombok.Builder
    public static SigmoidTrainerCalibrator build(
            double k,
            double t,
            TrainingData trainingData,
            boolean trained,
            String loadModelPath) {

        // 如果指定了加载模型路径，优先从文件加载
        if (loadModelPath != null && !loadModelPath.isEmpty()) {
            SigmoidTrainerCalibrator cal = new SigmoidTrainerCalibrator(
                    SigmoidParams.builder().k(k).t(t).build(),
                    null, false);
            cal.loadModel(loadModelPath);
            return cal;
        }

        SigmoidParams params = SigmoidParams.builder().k(k).t(t).build();
        return new SigmoidTrainerCalibrator(params, trainingData, trained);
    }

    // ==================== 实现 TrainerPureCalibrator ====================

    @Override
    /**
    * GenerateTrainingData
    * @param notSimilarCount notSimilarCount
    * @param lookSimilarCount lookSimilarCount
    * @param samePersonCount samePersonCount
    * @param seed seed
     */
    public TrainerPureCalibrator generateTrainingData(int notSimilarCount,
                                                      int lookSimilarCount,
                                                      int samePersonCount,
                                                      Long seed) {
        Random rng = (seed != null) ? new Random(seed) : new Random();

        // 不相似：均值0.40，标准差0.12（分数集中在0.1~0.7）
        List<Double> notSimilar = generateNormalSamples(notSimilarCount, 0.40, 0.12, rng);

        // 看似相似：均值0.68，标准差0.08（分数集中在0.5~0.85）
        List<Double> lookSimilar = generateNormalSamples(lookSimilarCount, 0.68, 0.08, rng);

        // 本人：均值0.86，标准差0.04（分数集中在0.75~0.95）
        List<Double> samePerson = generateNormalSamples(samePersonCount, 0.86, 0.04, rng);

        this.trainingData = TrainingData.builder()
                .notSimilarScores(notSimilar)
                .lookSimilarScores(lookSimilar)
                .samePersonScores(samePerson)
                .build();

        return this;
    }

    @Override
    /**
    * 设置TrainingData
    * @param notSimilarScores notSimilarScores
    * @param lookSimilarScores lookSimilarScores
    * @param samePersonScores samePersonScores
     */
    public TrainerPureCalibrator setTrainingData(List<Double> notSimilarScores,
                                                 List<Double> lookSimilarScores,
                                                 List<Double> samePersonScores) {
        this.trainingData = TrainingData.builder()
                .notSimilarScores(new ArrayList<>(notSimilarScores))
                .lookSimilarScores(new ArrayList<>(lookSimilarScores))
                .samePersonScores(new ArrayList<>(samePersonScores))
                .build();
        return this;
    }

    @Override
    /** Train */
    public TrainerPureCalibrator train() {
        if (trainingData == null || trainingData.isEmpty()) {
            throw new IllegalStateException("训练数据为空，请先调用 generateTrainingData() 或 setTrainingData()");
        }

        // 合并正负样本
        List<Double> posScores = trainingData.getPositiveScores();
        List<Double> negScores = trainingData.getNegativeScores();

        // 计算正负样本均值
        double posMean = posScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.85);
        double negMean = negScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.50);

        // 计算正负样本标准差
        double posStd = calculateStd(posScores, posMean);
        double negStd = calculateStd(negScores, negMean);

        // 计算校准前分离度
        double beforeSep = calculateSeparation(posMean, negMean, posStd, negStd);

        // 拟合参数：阈值取正负样本均值中点
        double newT = (posMean + negMean) / 2.0;

        // 陡度根据正负样本平均标准差估算
        double avgStd = (posStd + negStd) / 2.0;
        double newK = Math.max(5.0, 1.0 / Math.max(avgStd, 0.001));

        // 更新参数
        this.params.setK(newK);
        this.params.setT(newT);
        this.calibrator.setK(newK);
        this.calibrator.setT(newT);

        // 计算校准后统计
        double afterPosMean = posScores.stream()
                .mapToDouble(calibrator::calibrate)
                .average().orElse(50.0);
        double afterNegMean = negScores.stream()
                .mapToDouble(calibrator::calibrate)
                .average().orElse(50.0);

        double afterPosStd = calculateStd(
                posScores.stream().map(calibrator::calibrate).toList(),
                afterPosMean);
        double afterNegStd = calculateStd(
                negScores.stream().map(calibrator::calibrate).toList(),
                afterNegMean);

        double afterSep = calculateSeparation(afterPosMean, afterNegMean, afterPosStd, afterNegStd);

        // 计算分位数
        List<Double> afterNegSorted = negScores.stream()
                .map(calibrator::calibrate)
                .sorted().toList();
        List<Double> afterPosSorted = posScores.stream()
                .map(calibrator::calibrate)
                .sorted().toList();

        double afterNegP90 = afterNegSorted.get((int) (afterNegSorted.size() * 0.9));
        double afterPosP10 = afterPosSorted.get((int) (afterPosSorted.size() * 0.1));

        // 构建统计对象
        this.trainingStats = TrainingStats.builder()
                .beforeNegMean(negMean)
                .beforeNegStd(negStd)
                .beforePosMean(posMean)
                .beforePosStd(posStd)
                .beforeSeparation(beforeSep)
                .afterNegMean(afterNegMean)
                .afterNegStd(afterNegStd)
                .afterPosMean(afterPosMean)
                .afterPosStd(afterPosStd)
                .afterSeparation(afterSep)
                .separationImprovement(afterSep / Math.max(beforeSep, 0.001))
                .afterNegPercentile90(afterNegP90)
                .afterPosPercentile10(afterPosP10)
                .build();

        this.trained = true;
        return this;
    }

    @Override
    /** 保存Model */
    public TrainerPureCalibrator saveModel(String filePath) {
        if (!trained) {
            throw new IllegalStateException("模型尚未训练，请先调用 train()");
        }

        ModelData modelData = new ModelData();
        modelData.setAlgorithm("Sigmoid");
        modelData.setK(params.getK());
        modelData.setT(params.getT());

        try (FileWriter writer = new FileWriter(filePath)) {
            Json.toJson(modelData, writer);
        } catch (IOException e) {
            throw new RuntimeException("保存模型失败: " + filePath, e);
        }

        return this;
    }

    @Override
    /** 加载Model */
    public TrainerPureCalibrator loadModel(String filePath) {
        try (FileReader reader = new FileReader(filePath)) {
            ModelData modelData = Json.fromJson(reader, ModelData.class);
            if (modelData == null) {
                throw new RuntimeException("模型文件为空: " + filePath);
            }

            this.params.setK(modelData.getK());
            this.params.setT(modelData.getT());
            this.calibrator.setK(modelData.getK());
            this.calibrator.setT(modelData.getT());
            this.trained = true;

        } catch (IOException e) {
            throw new RuntimeException("加载模型失败: " + filePath, e);
        }

        return this;
    }

    @Override
    /** 获取TrainingData */
    public TrainingData getTrainingData() {
        return trainingData;
    }

    @Override
    /** 获取TrainingStats */
    public TrainingStats getTrainingStats() {
        return trainingStats;
    }

    // ==================== 实现 PureCalibrator ====================

    @Override
    /** Calibrate */
    public double calibrate(double rawScore) {
        return calibrator.calibrate(rawScore);
    }

    @Override
    /** 获取Name */
    public String getName() {
        return "Sigmoid可训练校准器";
    }

    @Override
    /** 获取Description */
    public String getDescription() {
        return "基于Sigmoid函数的可训练校准器。支持生成训练数据、自动拟合参数、保存/加载模型。";
    }

    // ==================== 内部工具方法 ====================

    /**
    * 用正态分布生成模拟分数
    *
    * @param count 样本数量
    * @param mean  均值
    * @param std   标准差
    * @param rng   随机数生成器
    * @return 分数列表
     */
    private List<Double> generateNormalSamples(int count, double mean, double std, Random rng) {
        List<Double> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double sample = rng.nextGaussian() * std + mean;
            // 截断到 [0, 1] 范围
            sample = Math.max(0.0, Math.min(1.0, sample));
            samples.add(Math.round(sample * 1000.0) / 1000.0);
        }
        return samples;
    }

    /**
    * 计算标准差
     */
    private double calculateStd(List<Double> values, double mean) {
        if (values.isEmpty()) {
            return 0.01;
        }
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
        return Math.sqrt(Math.max(variance, 0.0001));
    }

    /**
    * 计算分离度（Cohen's d）
     */
    private double calculateSeparation(double posMean, double negMean,
                                       double posStd, double negStd) {
        double pooledStd = Math.sqrt((posStd * posStd + negStd * negStd) / 2.0);
        if (pooledStd < 0.001) {
            return 10.0;
        }
        return (posMean - negMean) / pooledStd;
    }

    // ==================== 内部模型数据类 ====================

    /**
    * 模型数据（用于 JSON 序列化/反序列化）
     */
    @lombok.Data
    private static class ModelData {
        /** 算法名称 */
        private String algorithm;

        /** 陡度参数 */
        private double k;

        /** 阈值参数 */
        private double t;
    }
}
