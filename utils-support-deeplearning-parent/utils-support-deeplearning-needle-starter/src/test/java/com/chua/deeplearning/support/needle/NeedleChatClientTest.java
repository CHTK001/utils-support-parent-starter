package com.chua.deeplearning.support.needle;

import com.chua.common.support.ai.chat.ChatClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Needle 对话客户端单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class NeedleChatClientTest {

    @Test
    void testSpiRegistration() {
        ChatClient client = ChatClient.create("needle", "");
        assertThat(client).isInstanceOf(NeedleChatClient.class);
    }

    @Test
    void testModels() {
        ChatClient client = ChatClient.create("needle", "");
        var models = client.models();
        assertThat(models).isNotEmpty();
        assertThat(models.get(0).getProvider()).isEqualTo("cactus-compute");
    }

    @Test
    void testChat() {
        NeedleChatClient client = new NeedleChatClient(null);
        String answer = client.chatSync("你好");
        System.out.println("Needle 回复: " + answer);
        assertThat(answer).isNotBlank();
    }
}