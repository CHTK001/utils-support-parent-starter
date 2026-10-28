package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageEnhancer;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*
* @author CH
* @since 4.0.0.42
*/

@Slf4j
public class OnnxImageEnhancer implements ImageEnhancer {

    /**
     * 模型名称
    */
    private String modelName;

    /**
     * 创建 onnx镜像enhancer 实例
     * @param apiKey API密钥
     */
    public OnnxImageEnhancer(String apiKey) {
    }

    /**
     * 模型
    */
    @Override
    public ImageEnhancer model(String model) {
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
                    + ImageEnhancer.listModels());
        }
        return modelName;
    }

    /**
     * 增强
    */
    @Override
    public byte[] enhance(byte[] imageData) {
        return ImageEnhancer.create(resolveModel()).enhance(imageData);
    }

}


