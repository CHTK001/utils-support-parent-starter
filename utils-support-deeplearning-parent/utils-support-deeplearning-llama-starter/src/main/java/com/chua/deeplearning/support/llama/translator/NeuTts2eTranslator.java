package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class NeuTts2eTranslator implements ITranslator<String, byte[]>, AutoCloseable {

    private static final String DEFAULT_MODEL_ID = "neutts-2e";

    private volatile LlamaModel model;
    private volatile boolean initialized;

    @Override
    public String name() {
        return "neutts-2e";
    }

    @Override
    public byte[] translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[NeuTts2e] loading: {}", modelPath);
                        ModelParameters modelParams = new ModelParameters().setModel(modelPath.toString());
                        model = new LlamaModel(modelParams);
                        initialized = true;
                        log.info("[NeuTts2e] loaded: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[NeuTts2e] load failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            // TTS model outputs text tokens, convert to bytes (placeholder)
            String audioText = model.complete(new InferenceParameters(input));
            return audioText.getBytes();
        } catch (Exception e) {
            log.warn("[NeuTts2e] inference failed: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                log.warn("[NeuTts2e] close failed: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}