package com.chua.deeplearning.support.onnx.audio.sensevoice;

import com.chua.deeplearning.support.onnx.audio.AsrPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SenseVoice-Small ASR 端到端测试。
 *
 * <p>使用 1kHz 正弦波 16kHz 单声道 wav 验证完整 fbank → LFR → CMVN → CTC 推理管线
 * 不抛异常。期望 text 不为 null，可能为空或乱码（sine 波无语义）。</p>
 */
public class SenseVoiceAsrEndToEndTest {

    @Test
    @DisplayName("SenseVoice 端到端: 1kHz 正弦波不应抛异常")
    public void should_transcribe_sine_wav_without_error() throws Exception {
        Path wav = makeSineWav(1.0D, 1000.0D);
        try {
            String text = AsrPipeline.builder("sensevoice-small")
                    .language("zh")
                    .build()
                    .transcribe(wav);
            assertNotNull(text, "transcribe must not return null");
            System.out.println("[SenseVoice E2E] text=" + text);
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    @Test
    @DisplayName("SenseVoice 端到端: 静音 wav 不应抛异常")
    public void should_transcribe_silence_without_error() throws Exception {
        Path wav = makeSineWav(0.5D, 0.0001D);
        try {
            String text = AsrPipeline.builder("sensevoice-small")
                    .language("auto")
                    .build()
                    .transcribe(wav);
            assertNotNull(text, "transcribe must not return null");
            System.out.println("[SenseVoice E2E silence] text=\"" + text + "\"");
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    /**
     * 手工生成 WAV 文件：单声道 16-bit PCM 16kHz，正弦波频率 + 振幅。
     */
    private static Path makeSineWav(double durationSec, double frequencyHz) throws IOException {
        int sampleRate = 16000;
        int sampleCount = (int) (sampleRate * durationSec);
        Path tmp = Files.createTempFile("sensevoice-e2e-", ".wav");

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
                double sample = Math.sin(2 * Math.PI * frequencyHz * t) * 0.3D;
                short v = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, (int) (sample * 32767.0D)));
                raf.writeShort(Short.reverseBytes(v));
            }
        }
        tmp.toFile().deleteOnExit();
        assertTrue(new File(tmp.toString()).length() > 44);
        return tmp;
    }
}
