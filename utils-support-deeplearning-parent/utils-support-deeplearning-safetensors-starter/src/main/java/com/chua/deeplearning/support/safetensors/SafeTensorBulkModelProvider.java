package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.BulkModelProvider;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * safetensor 模型批量提供器，通过本地 safetensor服务 网关（{@code localhost:8765}）加载远端模型。
 * <p>
 * 将 {@link SafeTensorModelRegistry#allModels()} 中所有模型转换为 {@link TranslatorModelDefinition}，
 * 每个模型绑定一个 {@link SafeTensorModelTranslator} 通过 HTTP 调用对应推理能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class SafeTensorBulkModelProvider implements BulkModelProvider {

    /**
     * 本地 safetensor服务 网关主机
     */
    private static final String HOST = "localhost";

    /**
     * 本地 safetensor服务 网关端口
     */
    private static final int PORT = 8765;

    /**
     * 返回首个可用模型定义。
     *
     * @return TranslatorModelDefinition 或 空
     */
    @Override
    public TranslatorModelDefinition getDefinition() {
        List<TranslatorModelDefinition> all = getAll();
        return all.isEmpty() ? null : all.getFirst();
    }

    /**
     * @return SafeTensorModelRegistry 中所有模型对应的 translator模型definition 列表
     */
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

    /**
     * 为给定模型条目构造 safetensor模型translator 实例。
     *
     * @param entry 模型条目
     * @return ITranslator 实例
     */
    private ITranslator<?, ?> createTranslator(SafeTensorModelRegistry.ModelEntry entry) {
        return new SafeTensorModelTranslator(HOST, PORT, entry.id(), entry.type());
    }
}
