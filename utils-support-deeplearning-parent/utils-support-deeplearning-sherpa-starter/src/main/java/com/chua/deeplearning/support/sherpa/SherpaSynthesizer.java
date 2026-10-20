package com.chua.deeplearning.support.sherpa;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * sherpa-onnx 离线语音合成器。
 *
 * <p>支持 sherpa-onnx 的三类离线 TTS 模型族：VITS（含 piper / vits-icefall、多说话人）、
 * Matcha（acoustic + vocoder 双模型）与 Kokoro（多音色，需 voices.bin）。
 * 模型族按目录内文件命名自动判定，也可通过 {@link Builder#modelFamily(String)} 显式指定。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * try (SherpaSynthesizer tts = SherpaSynthesizer.builder()
 *         .model("/models/vits-icefall-zh")
 *         .speaker(3)                 // 多说话人模型可指定音色
 *         .speed(1.0f)
 *         .build()) {
 *     byte[] wav = tts.synthesizeToWav("你好，世界");
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SherpaSynthesizer implements AutoCloseable {

    /**
     * TTS 模型族短标识：vits。
     */
    public static final String FAMILY_VITS = "vits";

    /**
     * TTS 模型族短标识：matcha。
     */
    public static final String FAMILY_MATCHA = "matcha";

    /**
     * TTS 模型族短标识：kokoro。
     */
    public static final String FAMILY_KOKORO = "kokoro";

    /**
     * 底层合成器。
     */
    private final OfflineTts tts;

    /**
     * 模型目录。
     */
    private final Path modelDir;

    /**
     * 模型族。
     */
    private final String family;

    /**
     * 说话人 ID。
     */
    private final int speakerId;

    /**
     * 语速倍率。
     */
    private final float speed;

    /**
     * 构造合成器。
     *
     * @param tts       底层合成器
     * @param modelDir  模型目录
     * @param family    模型族
     * @param speakerId 说话人 ID
     * @param speed     语速倍率
     */
    private SherpaSynthesizer(OfflineTts tts, Path modelDir, String family, int speakerId, float speed) {
        this.tts = tts;
        this.modelDir = modelDir;
        this.family = family;
        this.speakerId = speakerId;
        this.speed = speed;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 合成语音并返回 WAV 字节（16 位单声道 PCM）。
     *
     * @param text 待合成文本
     * @return WAV 字节
     */
    public byte[] synthesizeToWav(String text) {
        GeneratedAudio audio = generate(text);
        return SherpaWavUtils.toWav(audio.getSamples(), audio.getSampleRate());
    }

    /**
     * 合成语音并返回 WAV 字节（默认说话人、默认语速）。
     *
     * @param text      待合成文本
     * @param speakerId 说话人 ID（多说话人模型有效）
     * @param speed     语速倍率（1.0 为原速）
     * @return WAV 字节
     */
    public byte[] synthesizeToWav(String text, int speakerId, float speed) {
        GeneratedAudio audio = generate(text, speakerId, speed);
        return SherpaWavUtils.toWav(audio.getSamples(), audio.getSampleRate());
    }

    /**
     * 合成语音并写入 WAV 文件。
     *
     * @param text     待合成文本
     * @param wavFile  目标文件
     * @return 写入的字节数
     */
    public long synthesizeToFile(String text, Path wavFile) {
        byte[] wav = synthesizeToWav(text);
        try {
            Path parent = wavFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(wavFile, wav);
            return wav.length;
        } catch (Exception e) {
            throw new SherpaException("写入 WAV 文件失败: " + wavFile, e);
        }
    }

    /**
     * 合成语音并返回原始浮点采样。
     *
     * @param text 待合成文本
     * @return 合成音频（含采样率）
     */
    public GeneratedAudio generate(String text) {
        return generate(text, speakerId, speed);
    }

    /**
     * 合成语音并返回原始浮点采样。
     *
     * @param text      待合成文本
     * @param speakerId 说话人 ID
     * @param speed     语速倍率
     * @return 合成音频（含采样率）
     */
    public GeneratedAudio generate(String text, int speakerId, float speed) {
        if (text == null || text.isBlank()) {
            throw new SherpaException("待合成文本为空");
        }
        int effectiveSpeaker = Math.max(0, Math.min(speakerId, Math.max(0, numSpeakers() - 1)));
        float effectiveSpeed = speed > 0F ? speed : 1F;
        try {
            return tts.generate(text, effectiveSpeaker, effectiveSpeed);
        } catch (RuntimeException e) {
            throw new SherpaException("sherpa-onnx 语音合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 模型支持的说话人数（单说话人模型返回 1）。
     *
     * @return 说话人数
     */
    public int numSpeakers() {
        try {
            return Math.max(1, tts.getNumSpeakers());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * 模型输出采样率。
     *
     * @return 采样率（Hz）
     */
    public int sampleRate() {
        return tts.getSampleRate();
    }

    /**
     * 模型目录。
     *
     * @return 模型目录
     */
    public Path modelDir() {
        return modelDir;
    }

    /**
     * 模型族。
     *
     * @return vits / matcha / kokoro
     */
    public String family() {
        return family;
    }

    /**
     * 释放原生资源。
     */
    @Override
    public void close() {
        try {
            tts.release();
        } catch (RuntimeException e) {
            log.debug("[sherpa] 释放合成器失败: {}", e.getMessage());
        }
    }

    /**
     * 按目录内文件命名判定 TTS 模型族，无法判定时按 VITS 处理。
     *
     * @param dir  模型目录
     * @param hint 显式指定的模型族，可为 {@code null}
     * @return 模型族短标识
     */
    static String detectFamily(Path dir, String hint) {
        if (hint != null && !hint.isBlank()) {
            return hint.trim().toLowerCase(Locale.ROOT);
        }
        if (dir == null) {
            return FAMILY_VITS;
        }
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("kokoro")) {
            return FAMILY_KOKORO;
        }
        if (name.contains("matcha")) {
            return FAMILY_MATCHA;
        }
        if (SherpaModelResolver.findFile(dir, "voices") != null) {
            return FAMILY_KOKORO;
        }
        if (SherpaModelResolver.findFile(dir, "vocoder") != null) {
            return FAMILY_MATCHA;
        }
        return FAMILY_VITS;
    }

    /**
     * 语音合成器构建器。
     */
    public static final class Builder {

        /**
         * 模型标识或目录。
         */
        private String model;

        /**
         * 模型族（vits / matcha / kokoro）。
         */
        private String family;

        /**
         * 说话人 ID。
         */
        private int speakerId;

        /**
         * 语速倍率。
         */
        private float speed = 1F;

        /**
         * 合成线程数。
         */
        private int numThreads;

        /**
         * 推理 provider。
         */
        private String provider;

        /**
         * 单句最大长度（句子数，1 表示不切分）。
         */
        private int maxNumSentences = 1;

        /**
         * 句间静音比例。
         */
        private float silenceScale = 0.2F;

        /**
         * 设置模型标识或目录。
         *
         * @param model 模型标识 / 目录
         * @return 当前构建器
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 设置模型目录。
         *
         * @param model 目录
         * @return 当前构建器
         */
        public Builder model(Path model) {
            this.model = model == null ? null : model.toString();
            return this;
        }

        /**
         * 设置模型族。
         *
         * @param family vits / matcha / kokoro
         * @return 当前构建器
         */
        public Builder modelFamily(String family) {
            this.family = family;
            return this;
        }

        /**
         * 设置说话人 ID。
         *
         * @param speakerId 说话人 ID
         * @return 当前构建器
         */
        public Builder speaker(int speakerId) {
            this.speakerId = speakerId;
            return this;
        }

        /**
         * 设置语速倍率。
         *
         * @param speed 语速倍率（1.0 原速）
         * @return 当前构建器
         */
        public Builder speed(float speed) {
            this.speed = speed;
            return this;
        }

        /**
         * 设置线程数。
         *
         * @param numThreads 线程数
         * @return 当前构建器
         */
        public Builder numThreads(int numThreads) {
            this.numThreads = numThreads;
            return this;
        }

        /**
         * 设置推理 provider。
         *
         * @param provider cpu / cuda 等
         * @return 当前构建器
         */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * 设置单句最大长度。
         *
         * @param maxNumSentences 句子数
         * @return 当前构建器
         */
        public Builder maxNumSentences(int maxNumSentences) {
            this.maxNumSentences = maxNumSentences;
            return this;
        }

        /**
         * 设置句间静音比例。
         *
         * @param silenceScale 比例
         * @return 当前构建器
         */
        public Builder silenceScale(float silenceScale) {
            this.silenceScale = silenceScale;
            return this;
        }

        /**
         * 构建合成器。
         *
         * @return 合成器实例
         * @throws SherpaException 模型或原生库不可用
         */
        public SherpaSynthesizer build() {
            Path dir = SherpaModelResolver.resolveDirectory(model);
            String detected = detectFamily(dir, family);
            SherpaNativeLoader.ensureLoaded();

            OfflineTtsModelConfig.Builder modelConfig = OfflineTtsModelConfig.builder()
                    .setNumThreads(SherpaModelResolver.effectiveThreads(numThreads))
                    .setDebug(false)
                    .setProvider(SherpaModelResolver.effectiveProvider(provider));

            switch (detected) {
                case FAMILY_MATCHA -> {
                    Path acoustic = SherpaModelResolver.findFile(dir, "acoustic");
                    Path vocoder = SherpaModelResolver.findFile(dir, "vocoder");
                    if (acoustic == null || vocoder == null) {
                        throw new SherpaException("Matcha 模型目录缺少 acoustic/vocoder onnx: " + dir);
                    }
                    modelConfig.setMatcha(OfflineTtsMatchaModelConfig.builder()
                            .setAcousticModel(acoustic.toString())
                            .setVocoder(vocoder.toString())
                            .setTokens(requireTokens(dir))
                            .setLexicon(optionalFile(dir, "lexicon"))
                            .setDataDir(optionalDir(dir, "espeak-ng-data"))
                            .build());
                }
                case FAMILY_KOKORO -> {
                    Path model3 = SherpaModelResolver.findFile(dir, "model");
                    Path voices = SherpaModelResolver.findFile(dir, "voices");
                    if (model3 == null || voices == null) {
                        throw new SherpaException("Kokoro 模型目录缺少 model/voices: " + dir);
                    }
                    modelConfig.setKokoro(OfflineTtsKokoroModelConfig.builder()
                            .setModel(model3.toString())
                            .setVoices(voices.toString())
                            .setTokens(requireTokens(dir))
                            .setLexicon(optionalFile(dir, "lexicon"))
                            .setDataDir(optionalDir(dir, "espeak-ng-data"))
                            .build());
                }
                default -> {
                    Path model3 = SherpaModelResolver.findFile(dir, "model");
                    if (model3 == null) {
                        throw new SherpaException("VITS 模型目录缺少 model*.onnx: " + dir);
                    }
                    modelConfig.setVits(OfflineTtsVitsModelConfig.builder()
                            .setModel(model3.toString())
                            .setTokens(requireTokens(dir))
                            .setLexicon(optionalFile(dir, "lexicon"))
                            .setDataDir(optionalDir(dir, "espeak-ng-data"))
                            .build());
                }
            }

            OfflineTtsConfig config = OfflineTtsConfig.builder()
                    .setModel(modelConfig.build())
                    .setMaxNumSentences(maxNumSentences)
                    .setSilenceScale(silenceScale)
                    .build();
            OfflineTts tts;
            try {
                tts = new OfflineTts(config);
            } catch (RuntimeException e) {
                throw new SherpaException("创建 sherpa-onnx 合成器失败（模型族 " + detected
                        + "，目录 " + dir + "）: " + e.getMessage(), e);
            }
            return new SherpaSynthesizer(tts, dir, detected, speakerId, speed);
        }

        /**
         * 取必需 tokens 文件路径。
         *
         * @param dir 模型目录
         * @return tokens 路径
         * @throws SherpaException 缺失
         */
        private static String requireTokens(Path dir) {
            Path tokens = SherpaModelResolver.findTokens(dir);
            if (tokens == null) {
                throw new SherpaException("TTS 模型目录缺少 tokens.txt: " + dir);
            }
            return tokens.toString();
        }

        /**
         * 取可选文件路径（缺失返回空串，交给 sherpa-onnx 走默认逻辑）。
         *
         * @param dir     模型目录
         * @param keyword 文件名关键字
         * @return 文件路径或空串
         */
        private static String optionalFile(Path dir, String keyword) {
            Path found = SherpaModelResolver.findFile(dir, keyword);
            return found == null ? "" : found.toString();
        }

        /**
         * 取可选目录路径（如 espeak-ng-data，缺失返回空串）。
         *
         * @param dir  模型目录
         * @param name 子目录名
         * @return 目录路径或空串
         */
        private static String optionalDir(Path dir, String name) {
            Path candidate = dir.resolve(name);
            if (Files.isDirectory(candidate)) {
                return candidate.toString();
            }
            try (var stream = Files.walk(dir, 2)) {
                return stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                        .findFirst()
                        .map(Path::toString)
                        .orElse("");
            } catch (Exception e) {
                return "";
            }
        }
    }

    /**
     * 判断目录是否为可用的 TTS 模型目录（供能力探测使用）。
     *
     * @param dir 目录
     * @return true 表示存在 tokens 与至少一个 onnx
     */
    public static boolean isTtsModelDir(Path dir) {
        return dir != null && Files.isDirectory(dir) && SherpaModelResolver.isUsableModelDir(dir);
    }
}
