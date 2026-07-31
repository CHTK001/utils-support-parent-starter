package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class Gemma4Translator implements ITranslator<String, String>, AutoCloseable {

    private static final String DEFAULT_MODEL_ID = "gemma-4-e2b";

    private volatile LlamaModel model;
    private volatile boolean initialized;

    @Override
    public String name() {
        return "gemma-4-e2b";
    }

    @Override
    public String translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[Gemma4] loading: {}", modelPath);
                        ModelParameters modelParams = new ModelParameters().setModel(modelPath.toString());
                        model = new LlamaModel(modelParams);
                        initialized = true;
                        log.info("[Gemma4] loaded: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[Gemma4] load failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            return model.complete(new InferenceParameters(input));
        } catch (Exception e) {
            log.warn("[Gemma4] inference failed: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                log.warn("[Gemma4] close failed: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}