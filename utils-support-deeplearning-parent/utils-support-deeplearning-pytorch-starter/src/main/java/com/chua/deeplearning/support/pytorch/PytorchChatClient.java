package com.chua.deeplearning.support.pytorch;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 pytorch (DJL) 的本地文本对话客户端。
 * <p>
 * 调度 pytorch 引擎下已注册的文本生成模型（如 NLLB / Opus 翻译、文本-特征 等），
 * 统一以 {@link ChatClient} 对外提供文本生成能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("pytorch")
public class PytorchChatClient extends AbstractLocalChatClient {

    /**
     * 构造 pytorch 对话客户端。
     *
     * @param setting 客户端配置
     */
    public PytorchChatClient(ChatClientSetting setting) {
        super("pytorch", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, String.class);
    }
}
