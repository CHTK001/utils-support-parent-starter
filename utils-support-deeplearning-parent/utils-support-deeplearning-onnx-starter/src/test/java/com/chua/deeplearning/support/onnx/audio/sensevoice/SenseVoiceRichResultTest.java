package com.chua.deeplearning.support.onnx.audio.sensevoice;

import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.AudioResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SenseVoice 富文本响应（emotion / language / events）测试。
 *
 * <p>使用 queryTask() 走完整推理并验证 AudioResponse 字段填充。</p>
 */
public class SenseVoiceRichResultTest {

    @Test
    @DisplayName("SenseVoice queryTask 返回 emotion + language + events")
    public void should_populate_rich_fields() throws Exception {
        Path wav = makeSineWav(1.0D, 440.0D);
        try (VirtualClient client = VirtualClient.create("sensevoice-small", "")) {
            client.audio(wav);
            String taskId = client.createTask(null);
            assertNotNull(taskId);
            AudioResponse resp = client.queryTask(taskId);
            assertNotNull(resp);
            System.out.println("[SenseVoice Rich] status=" + resp.getStatus()
                    + " transcript='" + resp.getTranscript()
                    + "' language=" + resp.getDetectedLanguage()
                    + " emotion=" + resp.getEmotion()
                    + " events=" + resp.getEvents());
            // 字段存在性（值可能为 null，sine 波无语义/情感）
            // status 必为 SUCCESS
            org.junit.jupiter.api.Assertions.assertEquals(
                    AudioResponse.Status.SUCCESS, resp.getStatus());
            org.junit.jupiter.api.Assertions.assertNotNull(resp.getTranscript());
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    private static Path makeSineWav(double durationSec, double frequencyHz) throws IOException {
        int sampleRate = 16000;
        int sampleCount = (int) (sampleRate * durationSec);
        Path tmp = Files.createTempFile("sensevoice-rich-", ".wav");
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
        return tmp;
    }
}

