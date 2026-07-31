package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.BulkModelProvider;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class SafeTensorBulkModelProvider implements BulkModelProvider {

    private static final String HOST = "localhost";
    private static final int PORT = 8765;

    @Override
    public TranslatorModelDefinition getDefinition() {
        List<TranslatorModelDefinition> all = getAll();
        return all.isEmpty() ? null : all.get(0);
    }

    @Override
    public List<TranslatorModelDefinition> getAll() {
        try {
            return SafeTensorModelRegistry.allModels().stream()
                    .map(entry -> {
                        try {
                            String modelId = entry.id();
                            ITranslator<?, ?> translator = createTranslator(entry);
                            ModelDefinition definition = ModelDefinition.builder()
                                    .id(modelId)
                                    .name(modelId)
                                    .provider(entry.source())
                                    .description(entry.description())
                                    .build();
                            return TranslatorModelDefinition.builder()
                                    .modelDefinition(definition)
                                    .translator(translator)
                                    .build();
                        } catch (Exception e) {
                            log.warn("[SafeTensor] 注册模型失败 [{}]: {}", entry.id(), e.getMessage());
                            return null;
                        }
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("[SafeTensor] getAll() failed: {}", e.getMessage());
            return List.of();
        }
    }

    private ITranslator<?, ?> createTranslator(SafeTensorModelRegistry.ModelEntry entry) {
        return new SafeTensorModelTranslator(HOST, PORT, entry.id(), entry.type());
    }
}
