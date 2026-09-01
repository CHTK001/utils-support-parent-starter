package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.UnderstandResult;
import com.chua.deeplearning.support.image.UnderstandTask;
import com.chua.deeplearning.support.image.VirtualClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地多模态理解客户端（基于 ONNX Runtime，如 Florence-2）。
 *
 * <p>实现 {@link VirtualClient} 接口，支持图像描述、OCR、物体检测等任务。
 * 模型本地运行，无需云端 API。</p>
 *
 * <pre>{@code
 * UnderstandResult result = VirtualClient.create("florence2")
 *     .understand(imageBytes, UnderstandTask.CAPTION);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxVirtualClient implements VirtualClient {

    private String modelName = "florence2";

    @Override
    public VirtualClient model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    public UnderstandResult understand(byte[] imageData, UnderstandTask task) {
        try {
            var t = (com.chua.deeplearning.support.translator.ITranslator<Object[], String>)
                com.chua.deeplearning.support.engine.ModelRegistry.getTranslator(modelName, com.chua.deeplearning.support.translator.ITranslator.class);
            if (t == null) throw new IllegalStateException("图像理解模型未注册: " + modelName);
            String result = t.translate(new Object[]{imageData, task.prompt()});
            return new UnderstandResult(task, result);
        } catch (Exception e) {
            log.error("[OnnxVirtualClient] 推理失败: {}", e.getMessage(), e);
            throw new RuntimeException("图像理解失败: " + e.getMessage(), e);
        }
    }
}