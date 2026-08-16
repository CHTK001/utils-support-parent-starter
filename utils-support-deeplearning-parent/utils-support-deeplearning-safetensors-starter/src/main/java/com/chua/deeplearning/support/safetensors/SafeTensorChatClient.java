package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;

import java.util.ArrayList;
import java.util.List;

/**
 * SafeTensor 本地对话客户端（HTTP 网关）。
 * <p>
 * 通过本地 SafeTensorService（localhost:8765）调度 LLM / VLM 类模型，
 * 统一以 {@link ChatClient} 对外提供对话能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("safetensors")
public class SafeTensorChatClient extends AbstractLocalChatClient {

    /**
     * 构造 SafeTensor 对话客户端。
     *
     * @param setting 客户端配置
     */
    public SafeTensorChatClient(ChatClientSetting setting) {
        super("safetensors", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return SafeTensorModels.ofType("llm", "vlm");
    }
}

/**
 * SafeTensor 模型列表辅助。
 */
final class SafeTensorModels {

    private SafeTensorModels() {
    }

    /**
     * 按模型类型过滤模型定义。
     *
     * @param types 模型类型（llm / vlm / image_gen / text_embedding / asr / tts 等）
     * @return 模型定义列表
     */
    static List<ModelDefinition> ofType(String... types) {
        List<ModelDefinition> result = new ArrayList<>();
        for (SafeTensorModelRegistry.ModelEntry entry : SafeTensorModelRegistry.allModels()) {
            for (String type : types) {
                if (type.equals(entry.type())) {
                    result.add(ModelDefinition.builder()
                            .id(entry.id())
                            .name(entry.id())
                            .provider("safetensors")
                            .description(entry.description())
                            .build());
                    break;
                }
            }
        }
        return result;
    }
}
