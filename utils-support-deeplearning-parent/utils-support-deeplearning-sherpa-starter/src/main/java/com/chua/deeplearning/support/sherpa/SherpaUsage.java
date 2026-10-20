package com.chua.deeplearning.support.sherpa;

import java.util.Locale;

/**
 * sherpa-onnx 模型的使用形态。
 *
 * <p>sherpa-onnx 的模型在<b>导出阶段</b>就分成了两类，二者不可互换：</p>
 * <ul>
 *   <li><b>离线（offline）</b>：整段音频一次喂入、一次解码，可用上下文做更强的建模，
 *       准确率高，但必须等整段音频就绪才能出结果。对应 {@link SherpaRecognizer} /
 *       {@link SherpaAsrPipeline}。</li>
 *   <li><b>流式（streaming，即“实时”）</b>：按 chunk 增量编码、边喂边出字，延迟低，
 *       但每个 chunk 只能看到有限上下文，准确率通常略低于同规模离线模型。对应
 *       {@link SherpaStreamingRecognizer}。</li>
 * </ul>
 *
 * <p><b>判别要点</b>：看模型目录名／官方仓库名里有没有 {@code streaming}。
 * 例如 {@code sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20} 是流式，
 * {@code sherpa-onnx-paraformer-zh-small-2024-03-09} 是离线。两者文件名都可能是
 * {@code encoder/decoder/joiner.int8.onnx}，<b>千万不要只看文件名</b>——
 * 把流式模型喂给离线识别器会解码失败或输出乱码。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum SherpaUsage {

    /**
     * 离线识别：整段音频一次解码（非实时，准确率优先）。
     */
    OFFLINE("offline", "离线", "整段音频一次解码，准确率优先；用于录音文件转写"),

    /**
     * 流式识别：按 chunk 增量解码（实时，低延迟优先）。
     */
    STREAMING("streaming", "实时/流式", "边喂边出字，低延迟；用于麦克风实时识别"),

    /**
     * 语音合成：文本转音频（无离线/流式之分，sherpa-onnx 仅提供离线 TTS）。
     */
    TTS("tts", "语音合成", "文本转音频，sherpa-onnx 只提供离线合成");

    /**
     * 短标识，同时用作模型标识前缀（如 {@code sherpa-offline-xxx}）。
     */
    private final String code;

    /**
     * 中文名。
     */
    private final String label;

    /**
     * 用途说明。
     */
    private final String description;

    /**
     * 构造枚举项。
     *
     * @param code        短标识
     * @param label       中文名
     * @param description 用途说明
     */
    SherpaUsage(String code, String label, String description) {
        this.code = code;
        this.label = label;
        this.description = description;
    }

    /**
     * 获取短标识。
     *
     * @return 短标识
     */
    public String code() {
        return code;
    }

    /**
     * 获取中文名。
     *
     * @return 中文名
     */
    public String label() {
        return label;
    }

    /**
     * 获取用途说明。
     *
     * @return 用途说明
     */
    public String description() {
        return description;
    }

    /**
     * 按短标识解析（忽略大小写）。
     *
     * @param text 短标识，可为 {@code null}
     * @return 枚举项；无法识别返回 {@code null}
     */
    public static SherpaUsage of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        for (SherpaUsage usage : values()) {
            if (usage.code.equals(normalized)) {
                return usage;
            }
        }
        return null;
    }

    /**
     * 按模型标识或目录名推断使用形态。
     *
     * <p>判据优先级：{@code tts} 关键词 → {@code streaming} 关键词 → 其余视为离线。
     * 之所以把 {@code streaming} 作为唯一负向判据，是因为 sherpa-onnx 的流式模型
     * 命名一定会带该词，而离线模型不会。</p>
     *
     * @param modelIdOrDir 模型标识或目录名，可为 {@code null}
     * @return 推断结果
     */
    public static SherpaUsage infer(String modelIdOrDir) {
        if (modelIdOrDir == null) {
            return OFFLINE;
        }
        String lower = modelIdOrDir.toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = lower.lastIndexOf('/');
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        if (name.contains("vits") || name.contains("tts") || name.contains("matcha") || name.contains("kokoro")) {
            return TTS;
        }
        if (name.contains("streaming") || name.contains("online")) {
            return STREAMING;
        }
        return OFFLINE;
    }
}
