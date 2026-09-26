package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageCaptioning;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*/

@Slf4j
public class OnnxImageCaptioning implements ImageCaptioning {

    /**
     * 模型名称
    */
    private String modelName;

    /**
     * 创建 onnx镜像captioning 实例
     * @param apiKey API密钥
     */
    public OnnxImageCaptioning(String apiKey) {
    }

    @Override
    /**
     * 模型
    */
    public ImageCaptioning model(String model) {
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
                    + ImageCaptioning.listModels());
        }
        return modelName;
    }

    @Override
    /**
     * Describe
    */
    public String describe(byte[] imageData) {
        return ImageCaptioning.create(resolveModel()).describe(imageData);
    }

}


