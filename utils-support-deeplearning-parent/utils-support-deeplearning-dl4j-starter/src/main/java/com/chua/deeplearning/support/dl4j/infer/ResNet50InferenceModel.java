package com.chua.deeplearning.support.dl4j.infer;

import com.chua.deeplearning.support.dl4j.train.ResNet50Model;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.datavec.image.loader.NativeImageLoader;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
* DL4J Rnet50 推理模型（有状态，OOP 风格）。
*
* <p>构造时加载模型一次，后续通过实例方法进行分类、特征提取和比对，
* 避免每次调用都重新加载模型。移植自 AIAS 2_培训假_platform 的推理能力。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Getter
public class ResNet50InferenceModel {

    /**
    * Rnet50 图片大小。
     */
    public static final int IMG_SIZE = 224;

    /**
    * 特征向量维度（Rnet50 flatten_1 层输出）。
     */
    public static final int FEATURE_DIMENSION = 2048;

    /**
    * 比对匹配阈值。
     */
    private static final float MATCH_THRESHOLD = 0.5f;

    private final ComputationGraph model; // 模型
    private final List<String> labels; // 标签
    private final int width; // width
    private final int height; // height
    private final int nChannels; // n通道
    private final NativeImageLoader loader; // 加载

    /**
    * 加载模型并指定类别标签。
    *
    * @param modelPath 模型文件路径
    * @param labels    类别标签列表
    * @throws IOException 模型加载失败
     */
    public ResNet50InferenceModel(String modelPath, List<String> labels) throws IOException {
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            throw new IOException("模型文件不存在: " + modelPath);
        }
        ResNet50Model resNet50Model = new ResNet50Model();
        this.model = resNet50Model.loadModel(modelFile);
        this.labels = labels;
        this.width = resNet50Model.getWidth();
        this.height = resNet50Model.getHeight();
        this.nChannels = resNet50Model.getNChannels();
        this.loader = new NativeImageLoader(width, height, nChannels);
        log.info("[DL4J] 推理模型已加载: {} ({}, labels={})", modelPath, model.summary(), labels.size());
    }

    /**
    * 加载模型（无需类别标签，仅特征提取场景）。
    *
    * @param modelPath 模型文件路径
    * @throws IOException 模型加载失败
     */
    public ResNet50InferenceModel(String modelPath) throws IOException {
        this(modelPath, List.of());
    }

    /**
    * 图片分类推理。
    *
    * @param image 打开cv Mat 图片
    * @return 分类预测结果
    * @throws IOException 图片处理异常
     */
    public ClassPrediction classify(Mat image) throws IOException {
        if (labels == null || labels.isEmpty()) {
            throw new IllegalStateException("分类需要标签列表，构造时未指定");
        }
        INDArray ds = loader.asMatrix(image);
        INDArray predictions = model.outputSingle(ds);
        int predictedClassIndex = predictions.argMax(1).getInt(0);
        String modelPrediction = labels.get(predictedClassIndex);
        float probability = predictions.getFloat(0, predictedClassIndex);
        return new ClassPrediction(modelPrediction, probability);
    }

    /**
    * 图片特征提取。
    *
    * @param image 打开cv Mat 图片
    * @return 2048 维特征向量
    * @throws IOException 图片处理异常
     */
    public float[] extractFeature(Mat image) throws IOException {
 // 移除 函数计算1000 输出层，以 flatten_1 为输出（2048 维）
        ComputationGraph embeddingModel = new TransferLearning.GraphBuilder(model)
                .removeVertexAndConnections("fc1000")
                .setOutputs("flatten_1")
                .build();

        INDArray ds = loader.asMatrix(image);
        INDArray embedding = embeddingModel.outputSingle(ds);
        float[] feature = embedding.toFloatVector();
        log.debug("[DL4J] 特征提取完成，维度: {}", feature.length);
        return feature;
    }

    /**
    * 图片 1:1 比对。
    *
    * @param image1 第一张图片
    * @param image2 第二张图片
    * @return 比对结果（含相似度、特征向量、是否匹配）
    * @throws IOException 图片处理异常
     */
    public ComparisonResult compare(Mat image1, Mat image2) throws IOException {
        float[] feature1 = extractFeature(image1);
        float[] feature2 = extractFeature(image2);
        float similarity = cosineSimilarity(feature1, feature2);
        return new ComparisonResult(similarity, feature1, feature2, similarity > MATCH_THRESHOLD);
    }

    /**
    * 计算两个特征向量的余弦相似度。
    *
    * @param a 特征向量 A
    * @param b 特征向量 B
    * @return 相似度（0.0 ~ 1.0）
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "特征向量维度不匹配: " + a.length + " vs " + b.length);
        }
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        int length = a.length;
        for (int i = 0; i < length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f;
        }
        return (float) ((dotProduct / Math.sqrt(normA) / Math.sqrt(normB) + 1) / 2.0f);
    }
}