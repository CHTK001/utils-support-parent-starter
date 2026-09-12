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

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
    * 迭代周期（轮次）。
     */
    private Integer epoch;

    /**
    * 批次大小（批量 大小）。
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

    /**
    * 创建默认训练超参数配置。
    *
    * @return 默认配置
     */
    public static TrainArgument defaults() {
        return TrainArgument.builder()
                .epoch(10)
                .batchSize(2)
                .nClasses(3)
                .learningRate(1e-3)
                .lrMomentum(0.9)
                .trainPercent(70)
                .build();
    }

    /**
    * 合并另一个超参数配置中的非空字段到当前对象（部分更新语义）。
    *
    * <p>用于更新超参数时只覆盖调用方显式传入的字段，
    * 保留当前对象上已有的默认值/历史值。</p>
    *
    * @param source 源配置，仅非空字段生效
    * @return 合并后的当前对象
     */
    public TrainArgument merge(TrainArgument source) {
        if (source == null) {
            return this;
        }
        if (source.epoch != null) {
            this.epoch = source.epoch;
        }
        if (source.batchSize != null) {
            this.batchSize = source.batchSize;
        }
        if (source.nClasses != null) {
            this.nClasses = source.nClasses;
        }
        if (source.classLabels != null) {
            this.classLabels = source.classLabels;
        }
        if (source.detLabels != null) {
            this.detLabels = source.detLabels;
        }
        if (source.resumeModelPath != null) {
            this.resumeModelPath = source.resumeModelPath;
        }
        if (source.learningRate != null) {
            this.learningRate = source.learningRate;
        }
        if (source.lrMomentum != null) {
            this.lrMomentum = source.lrMomentum;
        }
        if (source.trainPercent != null) {
            this.trainPercent = source.trainPercent;
        }
        return this;
    }
}
