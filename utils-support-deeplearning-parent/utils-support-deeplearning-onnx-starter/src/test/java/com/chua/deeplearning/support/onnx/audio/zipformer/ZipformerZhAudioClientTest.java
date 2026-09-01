package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.common.support.ai.audio.VirtualClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zipformer-zh 纯中文流式 ASR 端到端测试。
 *
 * <p>使用 HuggingFace 官方测试音频（16kHz 16bit PCM，~5.6s 中文语音）验证完整管线：
 * embedded 模型加载 → 推理 → 返回非空中文文本。</p>
 */
public class ZipformerZhAudioClientTest {

    private static final String TEST_WAV_RESOURCE = "audio/asr/zipformer-zh/test-zh.wav";

    @Test
    @DisplayName("Zipformer-zh 转写真实中文语音：应返回非空文本")
    public void should_transcribe_real_chinese_speech() throws Exception {
        Path wav = extractResource(TEST_WAV_RESOURCE);
        try {
            VirtualClient client = VirtualClient.create("zipformer-zh", "");
            String text = client.transcribe(wav);
            assertNotNull(text, "transcribe must not return null");
            System.out.println("[ZipformerZh E2E] text=\"" + text + "\"");
            assertTrue(text.length() > 0, "text should not be empty for real speech audio");
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    @Test
    @DisplayName("Zipformer-zh SPI 解析验证")
    public void should_resolve_zipformer_zh_spi() {
        VirtualClient client = VirtualClient.create("zipformer-zh", "");
        assertNotNull(client, "zipformer-zh SPI must resolve");
        System.out.println("[ZipformerZh SPI] resolved: " + client.getClass().getSimpleName());
    }

    private static Path extractResource(String resourcePath) throws IOException {
        try (InputStream in = ZipformerZhAudioClientTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("resource not found: " + resourcePath);
            Path tmp = Files.createTempFile("zipformer-zh-test-", ".wav");
            Files.delete(tmp);
            try (InputStream is = ZipformerZhAudioClientTest.class.getClassLoader()
                    .getResourceAsStream(resourcePath)) {
                Files.copy(is, tmp);
            }
            tmp.toFile().deleteOnExit();
            return tmp;
        }
    }
}


