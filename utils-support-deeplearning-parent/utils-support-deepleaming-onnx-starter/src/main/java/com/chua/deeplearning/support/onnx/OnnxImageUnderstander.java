package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageUnderstander;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 图像理解门面（基于 Florence-2 等多模态模型）。
 *
 * <p>实现 {@link ImageUnderstander} 接口，支持图像描述、OCR、物体检测等任务。</p>
 *
 * <pre>{@code
 * String caption = OnnxImageUnderstander.create()
 *     .understand(imageBytes, "<CAPTION>");
 *
 * String ocr = OnnxImageUnderstander.create()
 *     .understand(imageBytes, "<OCR>");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxImageUnderstander implements ImageUnderstander {

    private String modelName = "florence2";

    /** 创建默认实例 */
    public static OnnxImageUnderstander create() {
        return new OnnxImageUnderstander();
    }

    @Override
    public ImageUnderstander model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    public String understand(byte[] imageData, String taskPrompt) {
        try {
            var translator = com.chua.deeplearning.support.engine.ModelRegistry
                    .getTranslator(modelName, com.chua.deeplearning.support.translator.ITranslator.class);
            if (translator == null) {
                throw new IllegalStateException("图像理解模型未注册: " + modelName);
            }
            @SuppressWarnings("unchecked")
            var t = (com.chua.deeplearning.support.translator.ITranslator<Object[], String>) translator;
            return t.translate(new Object[]{imageData, taskPrompt});
        } catch (Exception e) {
            log.error("[OnnxImageUnderstander] 推理失败: {}", e.getMessage(), e);
            throw new RuntimeException("图像理解失败: " + e.getMessage(), e);
        }
    }
}