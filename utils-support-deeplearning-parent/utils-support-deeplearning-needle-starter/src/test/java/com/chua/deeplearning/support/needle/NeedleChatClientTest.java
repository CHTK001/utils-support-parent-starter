package com.chua.deeplearning.support.needle;

import com.chua.common.support.ai.chat.ChatClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Needle 对话客户端单元测试。
 *
 * <p>验证 SPI 注册与模型元数据；真实推理依赖 classpath 内置引擎或
 * {@code NEEDLE_LIB_PATH}。</p>
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
}