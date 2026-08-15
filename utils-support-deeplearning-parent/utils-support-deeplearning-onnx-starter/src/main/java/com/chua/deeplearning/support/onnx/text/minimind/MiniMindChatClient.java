package com.chua.deeplearning.support.onnx.text.minimind;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * MiniMind 聊天客户端（SPI provider="minimind"）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniMindChatClient implements ChatClient {

    private final ChatClientSetting setting;
    private MiniMindTranslator translator;

    public MiniMindChatClient(ChatClientSetting setting) {
        this.setting = setting;
    }

    private synchronized MiniMindTranslator translator() {
        if (translator == null) {
            translator = new MiniMindTranslator();
        }
        return translator;
    }

    @Override
    public String chatSync(String prompt) {
        try {
            return translator().translate(prompt);
        } catch (Exception e) {
            throw new RuntimeException("[minimind] chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }
}