package com.chua.deeplearning.support.dl4j.infer;

import com.chua.deeplearning.support.dl4j.train.ResNet50Model;
import lombok.extern.slf4j.Slf4j;
import org.datavec.image.loader.NativeImageLoader;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 图片分类推理。
 *
 * <p>加载训练后的 DL4J ResNet50 模型，对图片进行分类推理，返回类别名称及置信度。
 * 移植自 AIAS 2_training_platform 的 {@code ImageClassification}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ImageClassification {

    /**
     * 默认图片大小。
     */
    public static final int IMG_SIZE = 224;

    private ImageClassification() {
    }

    /**
     * 使用训练后的模型进行图片分类。
     *
     * @param modelPath 训练后模型文件路径（.zip 格式）
     * @param image     OpenCV Mat 图片
     * @param labels    类别标签列表
     * @return 分类预测结果
     * @throws IOException 模型加载或图片处理异常
     */
    public static ClassPrediction predict(String modelPath, Mat image, List<String> labels) throws IOException {
        ResNet50Model model = new ResNet50Model();
        model.setLabels(labels);

        File modelFile = new File(modelPath);
        if (modelFile.exists()) {
            log.info("[DL4J] 加载模型: {}", modelPath);
            model.loadModel(modelFile);
        } else {
            throw new IOException("模型文件不存在: " + modelPath);
        }

        return classify(model, image, labels);
    }

    /**
     * 使用已加载模型进行图片分类。
     *
     * @param model  已加载的 ResNet50 模型
     * @param image  OpenCV Mat 图片
     * @param labels 类别标签列表
     * @return 分类预测结果
     * @throws IOException 图片处理异常
     */
    public static ClassPrediction classify(ResNet50Model model, Mat image, List<String> labels) throws IOException {
        NativeImageLoader loader = new NativeImageLoader(
                model.getWidth(), model.getHeight(), model.getNChannels());
        INDArray ds = loader.asMatrix(image);

        INDArray predictions = model.getComputationGraph().outputSingle(ds);
        int predictedClassIndex = predictions.argMax(1).getInt(0);
        String modelPrediction = labels.get(predictedClassIndex);
        float topXProb = predictions.getFloat(0, predictedClassIndex);

        return new ClassPrediction(modelPrediction, topXProb);
    }
}
