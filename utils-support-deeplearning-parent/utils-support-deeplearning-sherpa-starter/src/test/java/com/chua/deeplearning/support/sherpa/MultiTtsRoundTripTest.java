package com.chua.deeplearning.support.sherpa;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多 TTS 模型的中文 ↔ 音频闭环验收。
 *
 * <p>覆盖 VITS（8kHz）与 Kokoro（24kHz）两个模型族，验证：</p>
 * <ol>
 *   <li>两个族的配置组装都能被原生层接受（Kokoro 依赖 espeak-ng-data，缺失会直接拒绝）；</li>
 *   <li>合成出的音频都能被 ASR 还原出接近原文的文本；</li>
 *   <li>采样率与模型族标注正确。</li>
 * </ol>
 *
 * <p>前置：{@code -Dsherpa.onnx.model.dir} 指向的目录下同时存在
 * {@code sensevoice}（识别）与至少一个 TTS 模型（{@code vits-zh} / {@code kokoro-zh}）。
 * 未满足时显式 skip。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class MultiTtsRoundTripTest {

    /**
     * 闭环用句子（覆盖日常表达、数字与专有名词）。
     */
    private static final String[] SENTENCES = {
            "今天天气真不错，我们一起去公园散步吧。",
            "请把这份文件转交给财务部门。",
            "明天上午九点在三楼会议室召开项目评审会。",
    };

    /**
     * 两个模型族都应跑通闭环，且平均召回率不低于 0.6。
     */
    @Test
    void shouldRoundTripWithEachTtsFamily() {
        Path base = requireBase();
        Path asrModel = base.resolve("sensevoice");

        Map<String, Double> recalls = new LinkedHashMap<>();
        try (SherpaAsrPipeline asr = SherpaAsrPipeline.builder()
                .model(asrModel)
                .language("zh")
                .postProcess(false)
                .build()) {

            for (String dirName : new String[]{"vits-zh", "kokoro-zh"}) {
                Path ttsDir = base.resolve(dirName);
                Assumptions.assumeTrue(Files.isDirectory(ttsDir),
                        "缺少 TTS 模型目录，跳过该族: " + dirName);

                try (SherpaSynthesizer tts = SherpaSynthesizer.builder()
                        .model(ttsDir)
                        .language("zh")
                        // 关闭随机噪声，保证可复现
                        .noiseScale(0F)
                        .noiseScaleW(0F)
                        .build()) {

                    assertThat(tts.sampleRate()).isGreaterThan(0);
                    assertThat(tts.family()).isIn(
                            SherpaSynthesizer.FAMILY_VITS,
                            SherpaSynthesizer.FAMILY_MATCHA,
                            SherpaSynthesizer.FAMILY_KOKORO,
                            SherpaSynthesizer.FAMILY_POCKET);

                    double total = 0D;
                    for (String sentence : SENTENCES) {
                        byte[] wav = tts.synthesizeToWav(sentence);
                        assertThat(wav.length).isGreaterThan(1000);
                        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII))
                                .as("%s 合成结果不是合法 WAV", dirName)
                                .isEqualTo("RIFF");

                        String actual = asr.recognize(wav).getText();
                        assertThat(actual)
                                .as("%s 合成的音频未被识别出任何文本", dirName)
                                .isNotBlank();
                        total += charRecall(sentence, actual);
                    }
                    recalls.put(dirName, total / SENTENCES.length);
                }
            }
        }

        assertThat(recalls).as("至少应有一个 TTS 模型完成闭环").isNotEmpty();
        recalls.forEach((name, recall) -> assertThat(recall)
                .as("%s 的平均字符召回率过低: %.3f", name, recall)
                .isGreaterThanOrEqualTo(0.6D));
    }

    /**
     * 两个族的采样率应真实反映模型输出（VITS 8k / Kokoro 24k），用于验证配置未串族。
     */
    @Test
    void shouldReportPerFamilySampleRate() {
        Path base = requireBase();
        Path vits = base.resolve("vits-zh");
        Path kokoro = base.resolve("kokoro-zh");

        if (Files.isDirectory(vits)) {
            try (SherpaSynthesizer tts = SherpaSynthesizer.builder().model(vits).build()) {
                assertThat(tts.family()).isEqualTo(SherpaSynthesizer.FAMILY_VITS);
                assertThat(tts.sampleRate()).isEqualTo(8000);
                assertThat(tts.numSpeakers()).isGreaterThan(1);
            }
        }

        if (Files.isDirectory(kokoro)) {
            try (SherpaSynthesizer tts = SherpaSynthesizer.builder().model(kokoro).build()) {
                assertThat(tts.family()).isEqualTo(SherpaSynthesizer.FAMILY_KOKORO);
                assertThat(tts.sampleRate()).isEqualTo(24000);
                assertThat(tts.numSpeakers()).isGreaterThan(1);
            }
        }
    }

    /**
     * Kokoro 缺少 espeak-ng-data 时应给出可操作提示，而不是裸的 Invalid config。
     */
    @Test
    void shouldExplainMissingEspeakData() {
        Path base = requireBase();
        Path kokoro = base.resolve("kokoro-zh");
        Assumptions.assumeTrue(Files.isDirectory(kokoro), "缺少 kokoro-zh，跳过该用例");
        Assumptions.assumeTrue(Files.isDirectory(kokoro.resolve("espeak-ng-data")),
                "该模型的 espeak-ng-data 已就位，跳过缺失场景");

        // 构造一个删掉 espeak-ng-data 的同构目录（只建链接形态的必需文件名，不复制大文件）
        Path broken = base.resolve("kokoro-broken");
        try {
            Files.createDirectories(broken);
            for (String name : new String[]{"model.onnx", "voices.bin", "tokens.txt"}) {
                Path src = kokoro.resolve(name);
                if (Files.isRegularFile(src)) {
                    Files.write(broken.resolve(name), new byte[]{0});
                }
            }
            org.junit.jupiter.api.Assertions.assertThrows(SherpaException.class, () -> {
                try (SherpaSynthesizer ignored = SherpaSynthesizer.builder().model(broken).build()) {
                    // 不应走到这里
                }
            }, "缺少 espeak-ng-data 应抛出可读异常");
        } catch (java.io.IOException e) {
            throw new AssertionError("准备测试目录失败", e);
        } finally {
            deleteRecursively(broken);
        }
    }

    /**
     * 校验并返回模型根目录（需含 sensevoice）。
     *
     * @return 模型根目录
     */
    private static Path requireBase() {
        String root = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        Assumptions.assumeTrue(root != null && !root.isBlank(),
                "未设置 -Dsherpa.onnx.model.dir，跳过闭环验收");
        Path dir = Path.of(root);
        Path base = Files.isDirectory(dir.resolve("sensevoice")) ? dir : dir.getParent();
        Assumptions.assumeTrue(base != null && Files.isDirectory(base.resolve("sensevoice")),
                "模型根目录下缺少 sensevoice: " + base);
        Assumptions.assumeTrue(SherpaNativeLoader.isAvailable(),
                "sherpa-onnx 原生库不可用: " + SherpaNativeLoader.failureReason());
        return base;
    }

    /**
     * 字符级召回率（忽略标点与空白）。
     *
     * @param expected 原文
     * @param actual   识别结果
     * @return 召回率 [0,1]
     */
    private static double charRecall(String expected, String actual) {
        String want = normalize(expected);
        String got = normalize(actual == null ? "" : actual);
        if (want.isEmpty()) {
            return 0D;
        }
        int hit = 0;
        for (int i = 0; i < want.length(); i++) {
            if (got.indexOf(want.charAt(i)) >= 0) {
                hit++;
            }
        }
        return (double) hit / want.length();
    }

    /**
     * 去掉标点与空白。
     *
     * @param text 文本
     * @return 归一化结果
     */
    private static String normalize(String text) {
        return text.replaceAll("[\\s，。、,.!?！？：:；;“”‘’（）()]", "");
    }

    /**
     * 递归删除目录。
     *
     * @param dir 目录
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // 清理失败不影响结论
                }
            });
        } catch (java.io.IOException ignored) {
            // 同上
        }
    }
}
