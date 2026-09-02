package com.chua.deeplearning.support.dl4j.infer;

import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;

import java.io.IOException;

/**
 * 图片 1:1 比对。
 *
 * <p>使用训练后的 DL4J ResNet50 模型提取两张图片的特征向量，
 * 通过余弦相似度计算相似度分数。移植自 AIAS 2_training_platform 的图片比对能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see FeatureExtraction#cosineSimilarity(float[], float[])
 */
@Slf4j
public class ImageComparison {

    private ImageComparison() {
    }

    /**
     * 比对两张图片的相似度。
     *
     * @param modelPath  训练后模型文件路径（.zip 格式）
     * @param image1     第一张 OpenCV Mat 图片
     * @param image2     第二张 OpenCV Mat 图片
     * @return 相似度分数（0.0 ~ 1.0，1.0 表示完全相同）
     * @throws IOException 模型加载或图片处理异常
     */
    public static float compare(String modelPath, Mat image1, Mat image2) throws IOException {
        float[] feature1 = FeatureExtraction.predict(modelPath, image1);
        float[] feature2 = FeatureExtraction.predict(modelPath, image2);
        return FeatureExtraction.cosineSimilarity(feature1, feature2);
    }

    /**
     * 比对两张图片的相似度，返回详细结果。
     *
     * @param modelPath 训练后模型文件路径（.zip 格式）
     * @param image1    第一张 OpenCV Mat 图片
     * @param image2    第二张 OpenCV Mat 图片
     * @return 比对结果
     * @throws IOException 模型加载或图片处理异常
     */
    public static ComparisonResult compareWithDetails(String modelPath, Mat image1, Mat image2) throws IOException {
        float[] feature1 = FeatureExtraction.predict(modelPath, image1);
        float[] feature2 = FeatureExtraction.predict(modelPath, image2);
        float similarity = FeatureExtraction.cosineSimilarity(feature1, feature2);

        return new ComparisonResult(similarity, feature1, feature2, similarity > 0.5f);
    }

    /**
     * 图片比对结果。
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ComparisonResult {

        /**
         * 相似度分数（0.0 ~ 1.0）。
         */
        private final float similarity;

        /**
         * 第一张图片的特征向量。
         */
        private final float[] feature1;

        /**
         * 第二张图片的特征向量。
         */
        private final float[] feature2;

        /**
         * 是否匹配（相似度 > 0.5）。
         */
        private final boolean match;
    }
}
