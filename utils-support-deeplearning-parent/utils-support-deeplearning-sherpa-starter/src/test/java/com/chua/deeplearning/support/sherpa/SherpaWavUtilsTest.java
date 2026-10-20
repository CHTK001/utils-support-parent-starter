package com.chua.deeplearning.support.sherpa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SherpaWavUtils} 的纯 Java 契约测试（不触发原生库加载）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class SherpaWavUtilsTest {

    /**
     * WAV 封装后应能被自身解码还原（含幅度近似）。
     */
    @Test
    void shouldRoundTripWav() {
        int sampleRate = 16000;
        float[] original = new float[1600];
        for (int i = 0; i < original.length; i++) {
            original[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
        }
        byte[] wav = SherpaWavUtils.toWav(original, sampleRate);
        assertThat(wav).hasSizeGreaterThan(44);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");

        float[] decoded = SherpaWavUtils.readMono(wav, sampleRate);
        assertThat(decoded.length).isEqualTo(original.length);
        for (int i = 0; i < decoded.length; i++) {
            assertThat(decoded[i]).isCloseTo(original[i], org.assertj.core.data.Offset.offset(0.01F));
        }
    }

    /**
     * 重采样应保持时长比例并做线性插值。
     */
    @Test
    void shouldResampleLinearly() {
        float[] source = new float[1600];
        for (int i = 0; i < source.length; i++) {
            source[i] = i / 1600.0F;
        }
        float[] half = SherpaWavUtils.resample(source, 16000, 8000);
        assertThat(half).hasSize(800);
        assertThat(half[0]).isEqualTo(0F);
        assertThat(half[400]).isCloseTo(0.5F, org.assertj.core.data.Offset.offset(0.01F));

        float[] same = SherpaWavUtils.resample(source, 16000, 16000);
        // 采样率一致时应返回副本（不返回同一引用），且内容完全一致
        assertThat(same).isNotSameAs(source);
        assertThat(same).containsExactly(source);
    }

    /**
     * 分段拼接与切分应保持采样总数一致。
     */
    @Test
    void shouldSplitAndConcat() {
        float[] samples = new float[1000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = i;
        }
        List<float[]> chunks = SherpaWavUtils.split(samples, 300);
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(3)).hasSize(100);

        float[] merged = SherpaWavUtils.concat(chunks, 0);
        assertThat(merged).containsExactly(samples);

        float[] withGap = SherpaWavUtils.concat(chunks, 10);
        assertThat(withGap).hasSize(1000 + 30);
    }

    /**
     * 时长换算与非法输入处理。
     */
    @Test
    void shouldComputeDuration() {
        assertThat(SherpaWavUtils.durationSeconds(16000, 16000)).isEqualTo(1.0D);
        assertThat(SherpaWavUtils.durationSeconds(8000, 16000)).isEqualTo(0.5D);
        assertThat(SherpaWavUtils.durationSeconds(100, 0)).isZero();
    }

    /**
     * 空采样与空字节应给出明确异常。
     */
    @Test
    void shouldRejectEmptyInput() {
        assertThatThrownBy(() -> SherpaWavUtils.readMono(new byte[0], 16000))
                .isInstanceOf(SherpaException.class)
                .hasMessageContaining("音频字节为空");
    }

    /**
     * 18kHz 等非 16k 输入应被自动重采样到目标采样率。
     */
    @Test
    void shouldResampleOnDecode() {
        int sourceRate = 32000;
        float[] samples = new float[sourceRate];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / sourceRate) * 0.3F;
        }
        byte[] wav = SherpaWavUtils.toWav(samples, sourceRate);
        float[] decoded = SherpaWavUtils.readMono(wav, 16000);
        assertThat(decoded).hasSize(16000);
    }
}
