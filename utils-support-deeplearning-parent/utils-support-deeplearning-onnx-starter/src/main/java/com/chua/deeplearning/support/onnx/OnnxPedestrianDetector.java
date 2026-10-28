package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.PedestrianDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*
* @author CH
* @since 4.0.0.42
*/

@Slf4j
public class OnnxPedestrianDetector implements PedestrianDetector {

    /**
     * 模型名称
    */
    private String modelName;
    /**
     * 设备类型
    */
    private String device = "cpu";

    /**
     * 创建 onnxpedestriandetector 实例
     * @param apiKey API密钥
     */
    public OnnxPedestrianDetector(String apiKey) {
    }

    /**
     * 模型
    */
    @Override
    public PedestrianDetector model(String model) {
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
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 指定，可用模型: "
                    + ImageDetector.listModels());
        }
        return modelName;
    }

    /**
     * Device
    */
    @Override
    public PedestrianDetector device(String device) {
        this.device = device;
        return this;
    }

    /**
     * Detect
    */
    @Override
    public List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(resolveModel()).device(device).detect(imageData);
    }

}


