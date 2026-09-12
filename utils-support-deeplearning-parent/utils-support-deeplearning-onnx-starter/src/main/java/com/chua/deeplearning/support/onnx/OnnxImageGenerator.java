package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageGenerator;
import lombok.extern.slf4j.Slf4j;

/**
   * ONNX 图像生成引擎（SPI 提供者="onnx"）。
 *
 * <p>注册表中无匹配类条件生成（classId）的模型，必须通过 {@code .model("模型ID")} 显式指定
 * 已注册模型，否则抛出异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxImageGenerator implements ImageGenerator {

    /**
     * 模型名称
     */
    private String modelName;

    /**
      * 创建 onnx镜像生成器 实例
     * @param apiKey API密钥
     */
    public OnnxImageGenerator(String apiKey) {
    }

    @Override
    /** 模型 */
    public ImageGenerator model(String model) {
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
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 显式指定，可用模型: " + ImageGenerator.listModels());
        }
        return modelName;
    }

    @Override
    /** Generate */
    public byte[] generate(long classId) {
        return ImageGenerator.create(resolveModel()).generate(classId);
    }

}
