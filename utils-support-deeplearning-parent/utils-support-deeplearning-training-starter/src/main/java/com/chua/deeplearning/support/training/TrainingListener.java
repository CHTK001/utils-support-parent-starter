package com.chua.deeplearning.support.training;

/**
 * 训练进度监听器。
 *
 * <p>在训练过程中接收进度回调，可用于实现训练可视化、日志记录等。
 * 在每个 epoch 完成后以及训练结束后触发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TrainingListener {

    /**
     * 训练开始时触发。
     *
     * @param totalEpochs 总训练轮数
     */
    void onTrainingStart(int totalEpochs);

    /**
     * 每个 epoch 结束后触发。
     *
     * @param currentEpoch 当前 epoch 编号（从 1 开始）
     * @param totalEpochs  总训练轮数
     * @param trainLoss    训练损失
     * @param evalAccuracy 评估准确率（若已评估，否则为 -1）
     */
    void onEpochEnd(int currentEpoch, int totalEpochs, double trainLoss, double evalAccuracy);

    /**
     * 训练结束时触发。
     *
     * @param result 训练结果
     */
    void onTrainingEnd(TrainingResult result);
}
