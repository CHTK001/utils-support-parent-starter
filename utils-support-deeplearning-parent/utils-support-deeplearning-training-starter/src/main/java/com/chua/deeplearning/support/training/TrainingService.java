package com.chua.deeplearning.support.training;

import com.chua.deeplearning.support.training.infer.ImageClassification;
import com.chua.deeplearning.support.training.infer.FeatureExtraction;
import com.chua.deeplearning.support.training.infer.ImageComparison;

import java.util.List;

/**
 * 图像分类模型训练与推理服务接口。
 *
 * <p>提供完整的图像分类模型训练（ResNet50 迁移学习）和推理能力：</p>
 * <ul>
 *     <li>模型训练（基于预训练 ResNet50 的迁移学习）</li>
 *     <li>图片分类推理</li>
 *     <li>图片特征提取（512 维向量）</li>
 *     <li>图片 1:1 相似度比对</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see TrainingArgument
 * @see TrainingResult
 * @see TrainingListener
 */
public interface TrainingService {

    /**
     * 创建训练服务实例（默认配置）。
     *
     * @return 训练服务实例
     */
    static TrainingService create() {
        return new TrainingServiceImpl();
    }

    /**
     * 执行图像分类模型训练（ResNet50 迁移学习）。
     *
     * <p>训练数据目录结构应为：
     * {@code dataRootPath/class1/img1.jpg, dataRootPath/class2/img2.jpg ...}</p>
     *
     * @param argument 训练参数配置
     * @param listener 训练进度监听器（可为 null）
     * @return 训练结果
     * @throws Exception 训练过程中发生异常
     */
    TrainingResult train(TrainingArgument argument, TrainingListener listener) throws Exception;

    /**
     * 使用训练后的模型进行图片分类。
     *
     * @param modelPath 训练后模型文件路径
     * @param imageData 图片数据（byte 数组）
     * @param labels    类别标签列表
     * @return 分类结果字符串
     * @throws Exception 推理异常
     */
    String classify(String modelPath, byte[] imageData, List<String> labels) throws Exception;

    /**
     * 使用训练后的模型提取图片特征（512 维向量）。
     *
     * @param modelPath 训练后模型文件路径
     * @param imageData 图片数据（byte 数组）
     * @return 512 维特征向量
     * @throws Exception 推理异常
     */
    float[] extractFeature(String modelPath, byte[] imageData) throws Exception;

    /**
     * 使用训练后的模型进行图片 1:1 比对。
     *
     * <p>返回相似度分数（0.0 ~ 1.0），1.0 表示完全相同。</p>
     *
     * @param modelPath 训练后模型文件路径
     * @param imageData1 第一张图片数据
     * @param imageData2 第二张图片数据
     * @param labels     类别标签列表
     * @return 相似度分数
     * @throws Exception 推理异常
     */
    float compare(String modelPath, byte[] imageData1, byte[] imageData2, List<String> labels) throws Exception;

    /**
     * 获取分类推理工具。
     *
     * @return 分类推理实例
     */
    ImageClassification imageClassification();

    /**
     * 获取特征提取推理工具。
     *
     * @return 特征提取实例
     */
    FeatureExtraction featureExtraction();

    /**
     * 获取图片比对推理工具。
     *
     * @return 图片比对实例
     */
    ImageComparison imageComparison();
}
