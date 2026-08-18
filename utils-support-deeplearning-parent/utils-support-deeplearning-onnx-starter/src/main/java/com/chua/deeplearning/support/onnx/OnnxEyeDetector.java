package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.EyeDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxEyeDetector implements EyeDetector {

    /** 模型名称 */
    /** 模型名称 */
    private String modelName;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    public OnnxEyeDetector(String apiKey) {
    }

    @Override
    public EyeDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "ultra-face";
    }

    @Override
    public EyeDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<PredictRectangle> detect(byte[] imageData) {
        return EyeDetector.create(resolveModel()).device(device).detect(imageData);
    }

}
