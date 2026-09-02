package com.chua.deeplearning.support.training;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 模型训练超参数配置。
 *
 * <p>定义图像分类模型迁移学习训练所需的全部超参数，
 * 包括训练轮数、批量大小、分类数、学习率等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingArgument {

    /**
     * 训练轮数（默认 10）。
     */
    @Builder.Default
    private int epoch = 10;

    /**
     * 批量大小（默认 8）。
     */
    @Builder.Default
    private int batchSize = 8;

    /**
     * 分类数（默认 2）。
     */
    @Builder.Default
    private int nClasses = 2;

    /**
     * 学习率（默认 0.001）。
     */
    @Builder.Default
    private double learningRate = 1e-3;

    /**
     * 动量（默认 0.9）。
     */
    @Builder.Default
    private double momentum = 0.9;

    /**
     * 图片宽度（默认 224）。
     */
    @Builder.Default
    private int imageWidth = 224;

    /**
     * 图片高度（默认 224）。
     */
    @Builder.Default
    private int imageHeight = 224;

    /**
     * 图片通道数（默认 3，RGB）。
     */
    @Builder.Default
    private int nChannels = 3;

    /**
     * 训练数据占总数据百分比（默认 70）。
     */
    @Builder.Default
    private int trainPercent = 70;

    /**
     * 预训练模型路径（可选，为 null 时从 URL 下载）。
     */
    private String modelPath;

    /**
     * 预训练模型下载 URL（可选）。
     */
    private String modelUrl;

    /**
     * 模型保存路径（绝对路径）。
     */
    private String savePath;

    /**
     * 训练数据根目录（图片分类数据集）。
     * <p>数据集目录结构应为：root/class1/img1.jpg, root/class2/img2.jpg ...</p>
     */
    private String dataRootPath;

    /**
     * 类别标签列表（逗号分隔字符串，训练完成后填充）。
     */
    private String classLabels;
}
