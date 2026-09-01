package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.VlmUnderstanding;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX Florence-2 多模态理解门面类。
 *
 * <p>实现 {@link VlmUnderstanding} 接口，委托给 {@code florence2} 模型的 ONNX Translator。</p>
 *
 * <pre>{@code
 * String result = OnnxFlorence2.create()
 *     .understand(imageBytes, "<CAPTION>");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxFlorence2 implements VlmUnderstanding {

    private String modelName = "florence2";

    /** 创建默认实例 */
    public static OnnxFlorence2 create() {
        return new OnnxFlorence2();
    }

    @Override
    public VlmUnderstanding model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    public String understand(byte[] imageData, String taskPrompt) {
        try {
            var translator = com.chua.deeplearning.support.engine.ModelRegistry
                    .getTranslator(modelName, com.chua.deeplearning.support.translator.ITranslator.class);
            if (translator == null) {
                throw new IllegalStateException("Florence-2 模型未注册: " + modelName);
            }
            @SuppressWarnings("unchecked")
            var t = (com.chua.deeplearning.support.translator.ITranslator<Object[], String>) translator;
            return t.translate(new Object[]{imageData, taskPrompt});
        } catch (Exception e) {
            log.error("[OnnxFlorence2] 推理失败: {}", e.getMessage(), e);
            throw new RuntimeException("Florence-2 推理失败: " + e.getMessage(), e);
        }
    }
}