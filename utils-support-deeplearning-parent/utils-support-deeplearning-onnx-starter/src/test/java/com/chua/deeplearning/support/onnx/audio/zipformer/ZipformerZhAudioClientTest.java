package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.common.support.ai.audio.AudioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Zipformer-zh 纯中文流式 ASR 端到端测试。
 *
 * <p>使用 16kHz 16-bit PCM WAV 正弦波验证模型能否正常加载并转写，
 * 中文语音输入（沉默/正弦波）应返回非 null 文本或空字符串。</p>
 */
public class ZipformerZhAudioClientTest {

    @Test
    @DisplayName("Zipformer-zh 转写正弦波 WAV 不应异常")
    public void should_transcribe_sine_wav_without_error() throws Exception {
        Path wav = makeSineWav(2.0, 440.0);
        try {
            AudioClient client = AudioClient.create("zipformer-zh", "");
            String text = client.transcribe(wav);
            assertNotNull(text, "transcribe must not return null");
            System.out.println("[ZipformerZh E2E] text=\"" + text + "\"");
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    @Test
    @DisplayName("Zipformer-zh SPI 解析验证")
    public void should_resolve_zipformer_zh_spi() {
        AudioClient client = AudioClient.create("zipformer-zh", "");
        assertNotNull(client, "zipformer-zh SPI must resolve");
        System.out.println("[ZipformerZh SPI] resolved: " + client.getClass().getSimpleName());
    }

    private static Path makeSineWav(double durationSec, double frequencyHz) throws IOException {
        int sampleRate = 16000;
        int sampleCount = (int) (sampleRate * durationSec);
        Path tmp = Files.createTempFile("zipformer-zh-e2e-", ".wav");

        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
            int byteRate = sampleRate * 2;
            int dataSize = sampleCount * 2;

            raf.writeBytes("RIFF");
            raf.writeInt(Integer.reverseBytes(36 + dataSize));
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            raf.writeInt(Integer.reverseBytes(16));
            raf.writeShort(Short.reverseBytes((short) 1));
            raf.writeShort(Short.reverseBytes((short) 1));
            raf.writeInt(Integer.reverseBytes(sampleRate));
            raf.writeInt(Integer.reverseBytes(byteRate));
            raf.writeShort(Short.reverseBytes((short) 2));
            raf.writeShort(Short.reverseBytes((short) 16));
            raf.writeBytes("data");
            raf.writeInt(Integer.reverseBytes(dataSize));

            for (int i = 0; i < sampleCount; i++) {
                double t = (double) i / sampleRate;
                double sample = Math.sin(2 * Math.PI * frequencyHz * t) * 0.3;
                short v = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, (int) (sample * 32767.0)));
                raf.writeShort(Short.reverseBytes(v));
            }
        }
        tmp.toFile().deleteOnExit();
        return tmp;
    }
}
