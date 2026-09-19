package com.chua.common.support.ai.calibration;

import com.chua.common.support.ai.calibration.train.TrainingData;
import com.chua.common.support.ai.calibration.train.TrainingStats;

import java.util.List;

/**
 * 可训练的纯校准器接口（二级接口）
 * <p>
 * 自身就是 PureCalibrator，可直接用于校准。
 * 支持生成三个目录的训练数据、训练、保存/加载模型，全程链式调用。
 * <p>
 * 三个目录：
 *   notSimilar  – 不相似（完全不同的人/图）
 *   lookSimilar – 看似相似（长得像但不是同一个人）
 *   samePerson  – 本人（同一个人的不同照片）
 * <p>
 * 典型用法（链式，以 Sigmoid 为例）：
 * <pre>
 * SigmoidTrainerCalibrator cal = SigmoidTrainerCalibrator.builder()
 *     .k(20.0).t(0.78)
 *     .generateTrainingData(200, 200, 200, 42L)
 *     .train()
 *     .saveModel("sigmoid_model.json")
 *     .build();
 *
 * double score = cal.calibrate(0.85);
 * </pre>
 * <p>
 * 加载已训练模型：
 * <pre>
 * SigmoidTrainerCalibrator cal = SigmoidTrainerCalibrator.builder()
 *     .loadModel("sigmoid_model.json")
 *     .build();
 * double score = cal.calibrate(0.85);
 * </pre>
 * <p>
 * @author CH
 * @since 4.0.0.42
 */
public interface TrainerPureCalibrator extends PureCalibrator {

    // ==================== 训练数据生成 ====================

    /**
     * 生成模拟训练数据（三个目录）
     * <p>
     * 根据各目录配置的均值和标准差，用正态分布生成模拟分数。
     * 生成的数据存储在内部的 TrainingData 对象中，供 train() 使用。
     *
     * @param notSimilarCount  不相似目录的样本数量
     * @param lookSimilarCount 看似相似目录的样本数量
     * @param samePersonCount  本人目录的样本数量
     * @param seed             随机种子（保证可复现，传null则每次不同）
     * @return 自身，支持链式调用
     */
    TrainerPureCalibrator generateTrainingData(int notSimilarCount,
                                               int lookSimilarCount,
                                               int samePersonCount,
                                               Long seed);

    /**
     * 使用外部提供的真实训练数据（替代 generateTrainingData）
     * <p>
     * 当你有自己的模型跑出来的真实分数时，用这个方法直接注入，
     * 不需要再调用 generateTrainingData。
     *
     * @param notSimilarScores  不相似目录的原始分数列表
     * @param lookSimilarScores 看似相似目录的原始分数列表
     * @param samePersonScores  本人目录的原始分数列表
     * @return 自身，支持链式调用
     */
    TrainerPureCalibrator setTrainingData(List<Double> notSimilarScores,
                                          List<Double> lookSimilarScores,
                                          List<Double> samePersonScores);

    // ==================== 训练 ====================

    /**
     * 训练：用当前训练数据自动拟合校准参数
     * <p>
     * 将三个目录的数据合并为：
     *   - 负样本 = 不相似 + 看似相似
     *   - 正样本 = 本人
     * 然后调用对应算法的拟合逻辑，更新内部参数。
     *
     * @return 自身，支持链式调用
     */
    TrainerPureCalibrator train();

    // ==================== 模型保存与加载 ====================

    /**
     * 保存训练好的模型参数到文件（JSON格式）
     *
     * @param filePath 文件路径
     * @return 自身，支持链式调用
     */
    TrainerPureCalibrator saveModel(String filePath);

    /**
     * 从文件加载已训练好的模型参数
     *
     * @param filePath 文件路径
     * @return 自身，支持链式调用
     */
    TrainerPureCalibrator loadModel(String filePath);

    // ==================== 训练数据访问 ====================

    /**
     * 获取训练数据（三个目录的分数）
     *
     * @return TrainingData 对象，包含三个目录的分数列表
     */
    TrainingData getTrainingData();

    /**
     * 获取训练效果统计（校准前后对比）
     *
     * @return 统计信息对象
     */
    TrainingStats getTrainingStats();
}
