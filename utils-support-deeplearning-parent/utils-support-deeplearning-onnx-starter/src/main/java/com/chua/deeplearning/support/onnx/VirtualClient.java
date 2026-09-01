package com.chua.deeplearning.support.onnx;
import com.chua.deeplearning.support.image.ImageUnderstander;
import com.chua.deeplearning.support.image.UnderstandResult;
import com.chua.deeplearning.support.image.UnderstandTask;
import lombok.extern.slf4j.Slf4j;
@Slf4j
public class VirtualClient implements ImageUnderstander {
    private String modelName = "florence2";
    public static VirtualClient create() { return new VirtualClient(); }
    @Override public ImageUnderstander model(String model) { this.modelName = model; return this; }
    @Override
    public UnderstandResult understand(byte[] imageData, UnderstandTask task) {
        try {
            var t = (com.chua.deeplearning.support.translator.ITranslator<Object[], String>)
                com.chua.deeplearning.support.engine.ModelRegistry.getTranslator(modelName, com.chua.deeplearning.support.translator.ITranslator.class);
            if (t == null) throw new IllegalStateException("图像理解模型未注册: " + modelName);
            String result = t.translate(new Object[]{imageData, task.prompt()});
            return new UnderstandResult(task, result);
        } catch (Exception e) {
            log.error("[VirtualClient] 推理失败: {}", e.getMessage(), e);
            throw new RuntimeException("图像理解失败: " + e.getMessage(), e);
        }
    }
}