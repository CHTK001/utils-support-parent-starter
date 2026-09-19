package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 ONNX Runtime 的本地对话客户端。
 * <p>
 * 调度 onnx 引擎下已注册的文本生成模型
 * （如 minimind、gpt2、bart/t5 系列等），统一以 {@link ChatClient} 对外提供对话能力。
 * </p>
 *
 * <p>用法：
 * <pre>{@code
 *   String answer = ChatClient.create("onnx", "")
 *       .model("minimind")
 *       .chatSync("你好，请介绍一下自己。");
 * }</pre>("你好，请介绍一下自己。");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("onnx")
public class OnnxChatClient extends AbstractLocalChatClient {

    /**
     * 构造 ONNX 对话客户端。
     *
     * @param setting 客户端配置
     */
    public OnnxChatClient(ChatClientSetting setting) {
        super("onnx", setting);
    }

    @Override
    /**
     * 模型
    */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, String.class);
    }
}
