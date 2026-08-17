package com.chua.deeplearning.support.needle;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Needle 对话客户端单元测试。
 *
 * <p>验证 SPI 注册、模型元数据与配置透传，不依赖原生动态库；
 * 真实推理依赖 {@code NEEDLE_LIB_PATH} 或缓存目录中的 libneedle 动态库。</p>
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
        List<com.chua.common.support.ai.chat.ModelDefinition> models = client.models();
        assertThat(models).isNotEmpty();
        assertThat(models.get(0).getProvider()).isEqualTo("cactus-compute");
    }

    @Test
    void testSystemTransmission() {
        NeedleChatClient client = (NeedleChatClient) ChatClient.create("needle", "");
        client.system("date: 2026-07-21 Tue 14:30");
        ChatClientSetting setting = ChatClientSetting.builder().model("needle2").maxTokens(128).build();
        NeedleChatClient configured = new NeedleChatClient(setting);
        assertThat(configured.models().get(0).getId()).isEqualTo("needle2");
    }
}
