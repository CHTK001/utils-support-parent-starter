package com.chua.deeplearning.support.onnx.florence2;

import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
* Florence-2 视觉语言模型本地聊天客户端实现。
*
* <p>通过 {@link #models()} 暴露 Florence-2 支持的所有模型定义，
* 由 {@link com.chua.common.support.spi.ServiceProvider} 管理多实现路由。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("florence2")
public class Florence2ChatClient extends AbstractLocalChatClient {

    /**
    * florence2对话客户端。
    * @param setting setting
     */
    public Florence2ChatClient(ChatClientSetting setting) {
        super("florence2", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
