package com.chua.deeplearning.support.mage;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.image.ImageClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Mage SPI 装配测试。
 *
 * <p>仅验证 SPI 注册与实例化链路（不发起网络请求）。
 */
class MageSpiTest {

    /**
     * 验证 provider="mage" 能解析到 {@link MageImageClient} 且模型清单完整。
     */
    @Test
    void imageClientSpiResolves() {
        ImageClient client = ImageClient.create("mage", "test-key");
        assertNotNull(client);
        assertEquals(6, client.models().size());
    }

    /**
     * 验证 provider="mage" 能解析到 {@link MageChatClient} 且模型清单非空。
     */
    @Test
    void chatClientSpiResolves() {
        ChatClient client = ChatClient.create("mage", "test-key");
        assertNotNull(client);
        assertFalse(client.models().isEmpty());
        client.close();
    }
}
