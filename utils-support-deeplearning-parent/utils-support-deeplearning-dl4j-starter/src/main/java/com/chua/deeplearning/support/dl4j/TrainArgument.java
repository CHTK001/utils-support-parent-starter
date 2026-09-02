package com.chua.deeplearning.support.dl4j;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 训练超参数配置。
 *
 * <p>对应 AIAS 2_training_platform 的 {@code TrainArgument}，包含迁移学习训练所需的
 * 全部超参数：迭代周期、批次大小、分类数量、类别标签以及学习率等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainArgument implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 迭代周期（epochs）。
     */
    private Integer epoch;

    /**
     * 批次大小（batch size）。
     */
    private Integer batchSize;

    /**
     * 分类数量（类别数，迁移学习输出层维度）。
     */
    private Integer nClasses;

    /**
     * 图像分类标签（训练完成后由数据目录自动推导并回填）。
     */
    private String classLabels;

    /**
     * 目标检测分类标签（预留）。
     */
    private String detLabels;

    /**
     * 续训练模型路径。指定后从该已训练模型继续微调（不指定则使用默认预训练模型）。
     */
    private String resumeModelPath;

    /**
     * 学习率（默认 1e-3）。
     */
    private Double learningRate;

    /**
     * 动量（默认 0.9）。
     */
    private Double lrMomentum;

    /**
     * 训练集占比（0~100，剩余作为测试集，默认 70）。
     */
    private Integer trainPercent;
}
