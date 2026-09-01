package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.common.support.ai.audio.AudioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zipformer-zh 流式转录门面测试。
 *
 * <p>验证 {@code AudioClient} 流式 API（feedAudio/getResult/complete）
 * 在真实中文语音上的表现。</p>
 */
public class ZipformerZhStreamingFacadeTest {

    private static final String TEST_WAV_RESOURCE = "audio/asr/zipformer-zh/test-zh.wav";

    @Test
    @DisplayName("流式门面：feedAudio + getResult + complete 全流程")
    public void should_stream_transcribe_real_speech() throws Exception {
        Path wav = extractResource(TEST_WAV_RESOURCE);
        try {
            float[] samples = AudioUtils.loadMono16k(wav);
            int chunkSize = 2560; // 160ms @ 16kHz

            AudioClient client = AudioClient.create("zipformer-zh", "");
            StringBuilder incremental = new StringBuilder();

            for (int offset = 0; offset < samples.length; offset += chunkSize) {
                int len = Math.min(chunkSize, samples.length - offset);
                float[] chunk = java.util.Arrays.copyOfRange(samples, offset, offset + len);
                client.feedAudio(chunk);

                String result = client.getResult();
                if (result != null && !result.isEmpty()) {
                    incremental.append(result);
                }
            }

            String finalText = client.complete();
            assertNotNull(finalText, "complete() must not return null");
            assertTrue(finalText.length() > 0, "streaming text must not be empty");
            System.out.println("[ZipformerZh Streaming Facade] text=\"" + finalText + "\"");
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    @Test
    @DisplayName("流式门面：streamingTranscribe 便捷方法")
    public void should_use_streamingTranscribe_convenience() throws Exception {
        Path wav = extractResource(TEST_WAV_RESOURCE);
        try {
            float[] samples = AudioUtils.loadMono16k(wav);
            AudioClient client = AudioClient.create("zipformer-zh", "");
            String text = client.streamingTranscribe(samples);
            assertNotNull(text, "streamingTranscribe must not return null");
            assertTrue(text.length() > 0, "streamingTranscribe text must not be empty");
            System.out.println("[ZipformerZh Streaming Convenience] text=\"" + text + "\"");
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    private static Path extractResource(String resourcePath) throws IOException {
        try (InputStream in = ZipformerZhStreamingFacadeTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("resource not found: " + resourcePath);
            Path tmp = Files.createTempFile("zipformer-stream-", ".wav");
            Files.copy(in, tmp);
            tmp.toFile().deleteOnExit();
            return tmp;
        }
    }
}
