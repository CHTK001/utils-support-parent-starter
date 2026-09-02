package com.chua.deeplearning.support.training;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 训练结果。
 *
 * <p>包含训练后的模型保存路径、类别标签、最佳评估指标等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingResult {

    /**
     * 是否训练成功。
     */
    @Builder.Default
    private boolean success = false;

    /**
     * 训练后模型保存路径（绝对路径）。
     */
    private String modelPath;

    /**
     * 训练后模型保存目录。
     */
    private String modelDir;

    /**
     * 模型名称（不含扩展名）。
     */
    private String modelName;

    /**
     * 类别标签列表（逗号分隔）。
     */
    private String classLabels;

    /**
     * 最佳评估准确率（0.0 ~ 1.0）。
     */
    @Builder.Default
    private double bestAccuracy = 0.0;

    /**
     * 最终训练损失。
     */
    @Builder.Default
    private double finalLoss = 0.0;

    /**
     * 总训练耗时（毫秒）。
     */
    @Builder.Default
    private long totalTrainingTimeMs = 0;

    /**
     * 总训练轮数。
     */
    @Builder.Default
    private int totalEpochs = 0;

    /**
     * 错误信息（训练失败时填充）。
     */
    private String errorMessage;

    /**
     * 获取模型文件的完整路径（含 .zip 扩展名）。
     *
     * @return 模型文件 Path
     */
    public Path getModelFilePath() {
        return Path.of(modelPath);
    }

    /**
     * 获取模型文件所在目录路径。
     *
     * @return 模型目录 Path
     */
    public Path getModelDirectory() {
        return Path.of(modelDir);
    }
}
