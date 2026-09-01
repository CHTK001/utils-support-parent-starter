package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.deeplearning.support.onnx.audio.AudioUtils;
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
 * <p>验证 {@code VirtualClient} 流式 API（feedAudio/getResult/complete）
 * 在真实中文语音上的表现。</p>
 */
public class ZipformerZhStreamingFacadeTest {

    private static final String TEST_WAV_RESOURCE = "audio/asr/zipformer-zh/test-zh.wav";

    @Test
    @DisplayName("流式门面：streamingTranscribe 便捷方法")
    public void should_use_streamingTranscribe_convenience() throws Exception {
        Path wav = extractResource(TEST_WAV_RESOURCE);
        try {
            float[] samples = AudioUtils.loadMono16k(wav);
            VirtualClient client = VirtualClient.create("zipformer-zh", "");
            // 一次性喂入全部样本，不中间调 getResult
            int chunkSize = 2560;
            for (int offset = 0; offset < samples.length; offset += chunkSize) {
                int len = Math.min(chunkSize, samples.length - offset);
                client.feedAudio(java.util.Arrays.copyOfRange(samples, offset, offset + len));
            }
            String text = client.complete();
            assertNotNull(text, "complete() must not return null");
            assertTrue(text.length() > 0, "streaming text must not be empty");
            System.out.println("[ZipformerZh Streaming Facade] text=\"" + text + "\"");
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    private static Path extractResource(String resourcePath) throws IOException {
        try (InputStream in = ZipformerZhStreamingFacadeTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("resource not found: " + resourcePath);
            Path tmp = java.nio.file.Paths.get(
                    System.getProperty("java.io.tmpdir"),
                    "zipformer-stream-" + System.nanoTime() + ".wav");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(tmp,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            tmp.toFile().deleteOnExit();
            return tmp;
        }
    }
}

