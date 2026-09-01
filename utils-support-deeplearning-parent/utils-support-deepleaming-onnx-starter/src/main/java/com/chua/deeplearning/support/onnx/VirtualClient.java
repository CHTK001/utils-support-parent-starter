package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageUnderstander;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地多模态理解客户端（基于 ONNX Runtime，如 Florence-2）。
 *
 * <p>实现 {@link ImageUnderstander} 接口，支持图像描述、OCR、物体检测等任务。
 * 模型本地运行，无需云端 API。</p>
 *
 * <pre>{@code
 * String caption = VirtualClient.create()
 *     .understand(imageBytes, "<CAPTION>");
 *
 * String ocr = VirtualClient.create()
 *     .understand(imageBytes, "<OCR>");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VirtualClient implements ImageUnderstander {

    private String modelName = "florence2";

    /** 创建默认实例 */
    public static VirtualClient create() {
        return new VirtualClient();
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
            log.error("[VirtualClient] 推理失败: {}", e.getMessage(), e);
            throw new RuntimeException("图像理解失败: " + e.getMessage(), e);
        }
    }
}