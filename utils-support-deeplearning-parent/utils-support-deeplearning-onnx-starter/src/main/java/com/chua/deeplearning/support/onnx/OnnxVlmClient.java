package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.UnderstandResult;
import com.chua.deeplearning.support.image.UnderstandTask;
import com.chua.deeplearning.support.image.VlmClient;
import com.chua.deeplearning.support.translator.ITranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OnnxVlmClient implements VlmClient {
    private static final Logger log = LoggerFactory.getLogger(OnnxVlmClient.class);
    private String modelName = "florence2";

    @Override
    public VlmClient model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    public UnderstandResult understand(byte[] imageData, UnderstandTask task) {
        try {
            ModelRegistry.Entry entry = ModelRegistry.get(modelName);
            if (entry == null) throw new IllegalStateException("Model not registered: " + modelName);
            ITranslator<?, ?> t = (ITranslator<?, ?>) Class.forName(entry.translatorClassName()).getDeclaredConstructor().newInstance();
            String result = t.translate(new Object[]{imageData, task.prompt()});
            return new UnderstandResult(task, result);
        } catch (Exception e) {
            log.error("[OnnxVlmClient] Failed: {}", e.getMessage(), e);
            throw new RuntimeException("Image understanding failed: " + e.getMessage(), e);
        }
    }
}