package com.chua.deeplearning.support.dl4j.infer;

import com.chua.deeplearning.support.dl4j.train.ResNet50Model;
import lombok.extern.slf4j.Slf4j;
import org.datavec.image.loader.NativeImageLoader;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;

/**
 * 图片特征提取。
 *
 * <p>加载训练后的 DL4J ResNet50 模型，移除输出层 fc1000，
 * 提取 flatten_1 层输出作为 2048 维特征向量。
 * 移植自 AIAS 2_training_platform 的 {@code FeatureExtraction}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FeatureExtraction {

    /**
     * 特征向量维度（ResNet50 flatten_1 层输出）。
     */
    public static final int FEATURE_DIMENSION = 2048;

    private FeatureExtraction() {
    }

    /**
     * 从图片中提取特征向量。
     *
     * @param modelPath 训练后模型文件路径（.zip 格式）
     * @param image     OpenCV Mat 图片
     * @return 特征向量（float 数组）
     * @throws IOException 模型加载或图片处理异常
     */
    public static float[] predict(String modelPath, Mat image) throws IOException {
        ResNet50Model resNet50Model = new ResNet50Model();

        // 1. 加载模型
        ComputationGraph model = resNet50Model.loadModel(new File(modelPath));

        // 2. 移除 fc1000 输出层，以 flatten_1 为输出（2048维）
        ComputationGraph embeddingModel = new TransferLearning.GraphBuilder(model)
                .removeVertexAndConnections("fc1000")
                .setOutputs("flatten_1")
                .build();

        // 3. 提取特征
        NativeImageLoader loader = new NativeImageLoader(
                resNet50Model.getWidth(), resNet50Model.getHeight(), resNet50Model.getNChannels());
        INDArray ds = loader.asMatrix(image);

        INDArray embedding = embeddingModel.outputSingle(ds);
        float[] feature = embedding.toFloatVector();

        log.debug("[DL4J] 特征提取完成，维度: {}", feature.length);
        return feature;
    }

    /**
     * 计算两个特征向量之间的余弦相似度。
     *
     * @param feature1 特征向量 1
     * @param feature2 特征向量 2
     * @return 余弦相似度（0.0 ~ 1.0，1.0 表示完全相同）
     */
    public static float cosineSimilarity(float[] feature1, float[] feature2) {
        if (feature1.length != feature2.length) {
            throw new IllegalArgumentException(
                    "特征向量维度不匹配: " + feature1.length + " vs " + feature2.length);
        }
        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;
        int length = feature1.length;
        for (int i = 0; i < length; i++) {
            dotProduct += feature1[i] * feature2[i];
            norm1 += feature1[i] * feature1[i];
            norm2 += feature2[i] * feature2[i];
        }
        if (norm1 == 0.0f || norm2 == 0.0f) {
            return 0.0f;
        }
        return (float) ((dotProduct / Math.sqrt(norm1) / Math.sqrt(norm2) + 1) / 2.0f);
    }
}
