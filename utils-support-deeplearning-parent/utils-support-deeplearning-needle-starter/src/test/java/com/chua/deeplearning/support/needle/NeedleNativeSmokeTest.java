package com.chua.deeplearning.support.needle;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.needle.NeedleNative;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Needle 原生引擎冒烟测试。
 *
 * <p>需要真实动态库（classpath 内置引擎已随模块分发），验证 FFM 绑定可完成初始化与推理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class NeedleNativeSmokeTest {

    @Test
    void testNativeInference() {
        Assumptions.assumeTrue(isWindows(), "当前平台非 Windows，跳过本地引擎冒烟测试");

        ChatClient client = ChatClient.create("needle", "");
        String result = client.chatSync("Reply with the single word OK.");
        assertThat(result).isNotBlank();
        System.out.println("Needle 推理结果: " + result);
    }

    @Test
    void testReset() {
        NeedleNative.init("", "[]", null);
        NeedleNative.reset();
        NeedleNative.reset();
    }

    /**
     * 判断当前平台是否为 Windows。
     *
     * @return true 表示为 Windows
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}