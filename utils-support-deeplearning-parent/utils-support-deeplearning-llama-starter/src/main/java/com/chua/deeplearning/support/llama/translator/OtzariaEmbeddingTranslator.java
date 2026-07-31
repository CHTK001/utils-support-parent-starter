package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class OtzariaEmbeddingTranslator implements ITranslator<String, float[]>, AutoCloseable {

    private static final String DEFAULT_MODEL_ID = "otzaria-embedding";

    private volatile LlamaModel model;
    private volatile boolean initialized;

    @Override
    public String name() {
        return "otzaria-embedding";
    }

    @Override
    public float[] translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[OtzariaEmbedding] loading: {}", modelPath);
                        ModelParameters modelParams = new ModelParameters().setModel(modelPath.toString());
                        model = new LlamaModel(modelParams);
                        initialized = true;
                        log.info("[OtzariaEmbedding] loaded: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[OtzariaEmbedding] load failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            float[] embedding = model.embed(input);
            return embedding;
        } catch (Exception e) {
            log.warn("[OtzariaEmbedding] inference failed: {}", e.getMessage());
            return new float[0];
        }
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                log.warn("[OtzariaEmbedding] close failed: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}
