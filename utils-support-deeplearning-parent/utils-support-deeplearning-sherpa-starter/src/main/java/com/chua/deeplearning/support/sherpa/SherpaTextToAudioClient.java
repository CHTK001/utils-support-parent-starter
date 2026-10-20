package com.chua.deeplearning.support.sherpa;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.audio.TextToAudioResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.capability.ModelCapabilities;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 基于 sherpa-onnx 官方 JNI 的本地语音合成客户端。
 *
 * <p>模型族（VITS / Matcha / Kokoro）由目录内文件命名自动判定，也可用
 * {@code 族名:路径} 前缀显式指定，例如 {@code model("kokoro:/models/kokoro-zh")}。</p>
 *
 * <p>{@link #voice(String)} 的语义按模型能力解释：</p>
 * <ul>
 *   <li>纯数字 → 说话人 ID（多说话人 VITS / Kokoro 音色序号）；</li>
 *   <li>{@code speakers.txt} 中的名字 → 映射为其行号；</li>
 *   <li>其它值 → 忽略并回退 0 号说话人，同时记录告警。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 * byte[] wav = TextToAudioClient.create("sherpa", "")
 *         .model("/models/vits-icefall-zh")
 *         .voice("12")
 *         .synthesize("你好世界");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"sherpa", "sherpa-onnx", "sherpa-tts"})
public class SherpaTextToAudioClient implements TextToAudioClient {

    /**
     * 模型类型与路径的分隔符。
     */
    private static final char TYPE_SEPARATOR = ':';

    /**
     * 说话人列表文件名。
     */
    private static final String SPEAKERS_FILE = "speakers.txt";

    /**
     * 客户端配置。
     */
    private final TextToAudioClientSetting setting;

    /**
     * 当前模型标识或目录。
     */
    private String model;

    /**
     * 当前发音人（说话人 ID 或名称）。
     */
    private String voice;

    /**
     * 语速倍率。
     */
    private double speed = 1.0D;

    /**
     * 待合成文本。
     */
    private String text;

    /**
     * 懒加载的合成器。
     */
    private SherpaSynthesizer synthesizer;

    /**
     * 构造客户端。
     *
     * @param setting 客户端配置
     */
    public SherpaTextToAudioClient(TextToAudioClientSetting setting) {
        this.setting = setting;
        if (setting != null) {
            this.model = setting.getModel();
            this.voice = setting.getVoice();
            if (setting.getSpeed() != null && setting.getSpeed() > 0) {
                this.speed = setting.getSpeed();
            }
            this.text = setting.getText();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TextToAudioClient model(String model) {
        this.model = model;
        releaseSynthesizer();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TextToAudioClient voice(String voice) {
        this.voice = voice;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TextToAudioClient speed(Double speed) {
        if (speed != null && speed > 0) {
            this.speed = speed;
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TextToAudioClient text(String text) {
        this.text = text;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] synthesize(String text) {
        if (text != null) {
            this.text = text;
        }
        if (this.text == null || this.text.isBlank()) {
            throw new SherpaException("待合成文本为空");
        }
        SherpaSynthesizer engine = ensureSynthesizer();
        return engine.synthesizeToWav(this.text, resolveSpeakerId(engine), (float) speed);
    }

    /**
     * 确保合成器已就绪。
     *
     * @return 合成器
     */
    private SherpaSynthesizer ensureSynthesizer() {
        if (synthesizer == null) {
            synchronized (this) {
                if (synthesizer == null) {
                    String modelRef = model != null && !model.isBlank() ? model : "vits";
                    String family = resolveFamilyHint();
                    synthesizer = SherpaSynthesizer.builder()
                            .model(stripFamilyPrefix(modelRef))
                            .modelFamily(family)
                            .speed((float) speed)
                            .build();
                }
            }
        }
        return synthesizer;
    }

    /**
     * 解析模型族提示：优先 {@code 族名:路径} 前缀，其次配置的 {@code format} 字段。
     *
     * @return 族名，可为 {@code null}
     */
    private String resolveFamilyHint() {
        String modelRef = model == null ? "" : model;
        int index = modelRef.indexOf(TYPE_SEPARATOR);
        if (index > 0 && !(index == 1 && Character.isLetter(modelRef.charAt(0)))) {
            String prefix = modelRef.substring(0, index).toLowerCase(Locale.ROOT);
            if (SherpaSynthesizer.FAMILY_VITS.equals(prefix)
                    || SherpaSynthesizer.FAMILY_MATCHA.equals(prefix)
                    || SherpaSynthesizer.FAMILY_KOKORO.equals(prefix)) {
                return prefix;
            }
        }
        String format = setting == null ? null : setting.getFormat();
        if (format != null && !format.isBlank()) {
            String normalized = format.trim().toLowerCase(Locale.ROOT);
            if (SherpaSynthesizer.FAMILY_VITS.equals(normalized)
                    || SherpaSynthesizer.FAMILY_MATCHA.equals(normalized)
                    || SherpaSynthesizer.FAMILY_KOKORO.equals(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * 去掉 {@code 族名:路径} 中的族名前缀。
     *
     * @param modelRef 模型引用
     * @return 纯路径
     */
    private String stripFamilyPrefix(String modelRef) {
        if (modelRef == null) {
            return null;
        }
        int index = modelRef.indexOf(TYPE_SEPARATOR);
        if (index > 0 && !(index == 1 && Character.isLetter(modelRef.charAt(0)))) {
            String prefix = modelRef.substring(0, index).toLowerCase(Locale.ROOT);
            if (SherpaSynthesizer.FAMILY_VITS.equals(prefix)
                    || SherpaSynthesizer.FAMILY_MATCHA.equals(prefix)
                    || SherpaSynthesizer.FAMILY_KOKORO.equals(prefix)) {
                return modelRef.substring(index + 1).trim();
            }
        }
        return modelRef;
    }

    /**
     * 把 {@link #voice} 解析为说话人 ID。
     *
     * @param engine 合成器
     * @return 说话人 ID
     */
    private int resolveSpeakerId(SherpaSynthesizer engine) {
        if (voice == null || voice.isBlank()) {
            return 0;
        }
        String candidate = voice.trim();
        try {
            return Integer.parseInt(candidate);
        } catch (NumberFormatException ignored) {
            // 非数字：按 speakers.txt 中的名称查找行号
        }
        int index = indexOfSpeaker(engine.modelDir(), candidate);
        if (index >= 0) {
            return index;
        }
        log.warn("[sherpa] 未知说话人 {}，回退 speaker=0（可用说话人数 {}）", candidate, engine.numSpeakers());
        return 0;
    }

    /**
     * 在 speakers.txt 中查找说话人行号。
     *
     * @param modelDir  模型目录
     * @param name      说话人名称
     * @return 行号（从 0 开始）；未找到返回 -1
     */
    private static int indexOfSpeaker(Path modelDir, String name) {
        Path speakers = modelDir == null ? null : modelDir.resolve(SPEAKERS_FILE);
        if (speakers == null || !Files.isRegularFile(speakers)) {
            return -1;
        }
        try {
            List<String> lines = Files.readAllLines(speakers);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals(name)) {
                    return i;
                }
            }
        } catch (Exception e) {
            log.debug("[sherpa] 读取 speakers.txt 失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String createTask(String text) {
        if (text != null) {
            this.text = text;
        }
        return "sherpa-tts-" + UUID.randomUUID();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TextToAudioResponse queryTask(String taskId) {
        try {
            byte[] wav = synthesize(this.text);
            SherpaSynthesizer engine = synthesizer;
            return TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(TextToAudioResponse.Status.SUCCESS)
                    .audioBytes(wav)
                    .format("wav")
                    .sampleRate(engine == null ? SherpaWavUtils.DEFAULT_SAMPLE_RATE : engine.sampleRate())
                    .voice(voice)
                    .build();
        } catch (RuntimeException e) {
            log.error("[sherpa] 合成失败: {}", e.getMessage(), e);
            return TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(TextToAudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 获取当前模型可用说话人名称列表。
     *
     * @return 说话人名称列表；模型无 speakers.txt 时返回空列表
     */
    public List<String> speakerNames() {
        SherpaSynthesizer engine = ensureSynthesizer();
        Path speakers = engine.modelDir().resolve(SPEAKERS_FILE);
        if (!Files.isRegularFile(speakers)) {
            return List.of();
        }
        try {
            List<String> names = new ArrayList<>();
            for (String line : Files.readAllLines(speakers)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
            return names;
        } catch (Exception e) {
            log.debug("[sherpa] 读取 speakers.txt 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取当前模型的说话人数。
     *
     * @return 说话人数
     */
    public int numSpeakers() {
        return ensureSynthesizer().numSpeakers();
    }

    /**
     * 获取当前模型的输出采样率。
     *
     * @return 采样率（Hz）
     */
    public int sampleRate() {
        return ensureSynthesizer().sampleRate();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        releaseSynthesizer();
    }

    /**
     * 释放合成器。
     */
    private synchronized void releaseSynthesizer() {
        if (synthesizer != null) {
            synthesizer.close();
            synthesizer = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> result = new ArrayList<>();
        String root = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        if (root == null || root.isBlank()) {
            return result;
        }
        Path rootDir = Path.of(root);
        if (!Files.isDirectory(rootDir)) {
            return result;
        }
        try (var stream = Files.list(rootDir)) {
            stream.filter(Files::isDirectory)
                    .filter(SherpaSynthesizer::isTtsModelDir)
                    .sorted()
                    .forEach(dir -> result.add(ModelDefinition.builder()
                            .id(dir.getFileName().toString())
                            .name(dir.getFileName().toString())
                            .provider("sherpa")
                            .description("sherpa-onnx 语音合成模型（" + dir + "）")
                            .capabilities(List.of(ModelCapabilities.TTS))
                            .build()));
        } catch (Exception e) {
            log.debug("[sherpa] 枚举 TTS 模型目录失败: {}", e.getMessage());
        }
        return result;
    }
}
