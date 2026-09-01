package com.chua.deeplearning.support.onnx.florence2;

import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

@Spi("florence2")
public class Florence2ChatClient extends AbstractLocalChatClient {

    public Florence2ChatClient(ChatClientSetting setting) {
        super("florence2", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
