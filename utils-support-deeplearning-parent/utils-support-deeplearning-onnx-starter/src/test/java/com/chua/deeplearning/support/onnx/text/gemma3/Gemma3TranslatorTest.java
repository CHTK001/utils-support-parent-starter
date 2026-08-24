package com.chua.deeplearning.support.onnx.text.gemma3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemma3Translator 冒烟测试（嵌入式模型推理，较重）。
 *
 * <p>默认跳过；本地执行：{@code mvn test -DskipTests=false -Dgemma.test=true}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class Gemma3TranslatorTest {

    @Test
    @DisplayName("嵌入式 gemma-3-270m 对话冒烟")
    @EnabledIfSystemProperty(named = "gemma.test", matches = ".+")
    void testChatSmoke() throws Exception {
        try (Gemma3Translator translator = new Gemma3Translator()) {
            String reply = translator.chat("用一句话介绍你自己");
            System.out.println("[Gemma3] 回复: " + reply);
            assertNotNull(reply, "回复不应为 null");
            assertFalse(reply.isBlank(), "回复不应为空白");
        }
    }
}
