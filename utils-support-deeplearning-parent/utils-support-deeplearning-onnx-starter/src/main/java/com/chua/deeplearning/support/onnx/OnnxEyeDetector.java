package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.EyeDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

@Slf4j
public class OnnxEyeDetector implements EyeDetector {

    /** 模型名称 */
    private String modelName;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
      * 创建 onnxeyedetector 实例
     * @param apiKey API密钥
     */
    public OnnxEyeDetector(String apiKey) {
    }

    @Override
    /** 模型 */
    public EyeDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "ultra-face";
    }

    @Override
    /** Device */
    public EyeDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Detect */
    public List<PredictRectangle> detect(byte[] imageData) {
        return EyeDetector.create(resolveModel()).device(device).detect(imageData);
    }

}


