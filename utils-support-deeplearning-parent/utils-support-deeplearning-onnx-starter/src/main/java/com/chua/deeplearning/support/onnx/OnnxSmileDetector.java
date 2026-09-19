package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.SmileDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 微笑检测引擎（SPI 提供者="onnx"）。
 *
 * <p>注册表中无专用微笑检测模型，必须通过 {@code .model("模型ID")} 显式指定
 * 已注册的情绪/人脸模型，否则抛出异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxSmileDetector implements SmileDetector {

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 运行设备
     */
    private String device = "cpu";

    /**
     * 创建 onnxsmiledetector 实例
     * @param apiKey API密钥
     */
    public OnnxSmileDetector(String apiKey) {
    }

    @Override
    /** 模型 */
    public SmileDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /**
    * 解析模型
    *
    * @return resolve模型的结果
    */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 显式指定，可用模型: " + SmileDetector.listModels());
        }
        return modelName;
    }

    @Override
    /** 模型路径 */
    public SmileDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public SmileDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Detect */
    public List<PredictRectangle> detect(byte[] imageData) {
        return SmileDetector.create(resolveModel()).modelPath(modelPath).device(device).detect(imageData);
    }

}
