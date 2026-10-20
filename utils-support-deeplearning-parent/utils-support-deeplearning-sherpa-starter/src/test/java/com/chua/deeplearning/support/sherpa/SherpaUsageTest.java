package com.chua.deeplearning.support.sherpa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SherpaUsage} 契约测试。
 *
 * <p>「离线 / 实时」判别是本模块最容易踩错的地方：两类模型文件名完全相同，
 * 选错会导致原生层解码失败。这里把判别规则固化下来，防止后续改动把规则弄丢。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class SherpaUsageTest {

    /**
     * 目录名带 streaming 的必须判为实时，无论是否含 zipformer/transducer。
     */
    @Test
    void shouldDetectStreamingByDirectoryName() {
        assertThat(SherpaUsage.infer("streaming-zipformer"))
                .isEqualTo(SherpaUsage.STREAMING);
        assertThat(SherpaUsage.infer("sherpa-streaming-zipformer-zh-en"))
                .isEqualTo(SherpaUsage.STREAMING);
        assertThat(SherpaUsage.infer("/opt/models/sherpa/streaming-zipformer"))
                .isEqualTo(SherpaUsage.STREAMING);
        assertThat(SherpaUsage.infer("my-online-asr"))
                .isEqualTo(SherpaUsage.STREAMING);
    }

    /**
     * 不含 streaming 关键词的识别模型一律视为离线。
     */
    @Test
    void shouldDefaultToOffline() {
        assertThat(SherpaUsage.infer("sensevoice")).isEqualTo(SherpaUsage.OFFLINE);
        assertThat(SherpaUsage.infer("paraformer-zh")).isEqualTo(SherpaUsage.OFFLINE);
        assertThat(SherpaUsage.infer("whisper-tiny")).isEqualTo(SherpaUsage.OFFLINE);
        assertThat(SherpaUsage.infer("zipformer-zh-en")).isEqualTo(SherpaUsage.OFFLINE);
        assertThat(SherpaUsage.infer(null)).isEqualTo(SherpaUsage.OFFLINE);
    }

    /**
     * 合成模型应被识别为 TTS，避免被当成识别模型列出。
     */
    @Test
    void shouldDetectTtsModels() {
        assertThat(SherpaUsage.infer("vits-icefall-zh")).isEqualTo(SherpaUsage.TTS);
        assertThat(SherpaUsage.infer("sherpa-tts-vits-zh")).isEqualTo(SherpaUsage.TTS);
        assertThat(SherpaUsage.infer("matcha-zh")).isEqualTo(SherpaUsage.TTS);
        assertThat(SherpaUsage.infer("kokoro-zh")).isEqualTo(SherpaUsage.TTS);
    }

    /**
     * 判定应只看最后一级目录名，避免父目录里的关键词误伤。
     */
    @Test
    void shouldOnlyInspectLeafDirectoryName() {
        // 父目录含 streaming，但模型目录本身是离线的
        assertThat(SherpaUsage.infer("/data/streaming-backup/sensevoice"))
                .isEqualTo(SherpaUsage.OFFLINE);
        // Windows 路径分隔符同样适用
        assertThat(SherpaUsage.infer("D:\\models\\streaming-zipformer"))
                .isEqualTo(SherpaUsage.STREAMING);
    }

    /**
     * 短标识解析应忽略大小写，非法值返回 null。
     */
    @Test
    void shouldParseCode() {
        assertThat(SherpaUsage.of("OFFLINE")).isEqualTo(SherpaUsage.OFFLINE);
        assertThat(SherpaUsage.of(" streaming ")).isEqualTo(SherpaUsage.STREAMING);
        assertThat(SherpaUsage.of("tts")).isEqualTo(SherpaUsage.TTS);
        assertThat(SherpaUsage.of("unknown")).isNull();
        assertThat(SherpaUsage.of(null)).isNull();
    }

    /**
     * 每个枚举项都应带可读的中文名与用途说明（供界面直接展示）。
     */
    @Test
    void shouldProvideReadableMetadata() {
        for (SherpaUsage usage : SherpaUsage.values()) {
            assertThat(usage.code()).isNotBlank();
            assertThat(usage.label()).isNotBlank();
            assertThat(usage.description()).isNotBlank();
        }
        assertThat(SherpaUsage.OFFLINE.label()).isEqualTo("离线");
        assertThat(SherpaUsage.STREAMING.label()).contains("实时");
    }
}
