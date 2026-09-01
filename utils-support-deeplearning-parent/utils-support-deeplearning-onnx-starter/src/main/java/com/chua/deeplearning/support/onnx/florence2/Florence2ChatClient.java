package com.chua.deeplearning.support.onnx.florence2;

import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * Florence-2 多模态理解对话客户端（ONNX）。
 * <p>
 * 支持图像描述、OCR、物体检测等任务。通过任务提示符控制：
 * {@code <CAPTION>}、{@code <OCR>}、{@code <DETAILED_CAPTION>}、{@code <OD>}
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
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
