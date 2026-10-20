package com.chua.deeplearning.support.sherpa;

import java.util.Locale;

/**
 * sherpa-onnx 支持的模型族类型。
 *
 * <p>sherpa-onnx 的离线识别器按「模型族」选择不同的配置分支（Paraformer / SenseVoice /
 * Whisper / Transducer / Zipformer-CTC 等），本枚举把该分支显式化，供
 * {@link SherpaModelResolver} 自动探测与调用方手工指定。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum SherpaModelType {

    /**
     * Paraformer 非自回归中文模型（单个 model.onnx / model.int8.onnx + tokens.txt）。
     */
    PARAFORMER("paraformer", "Paraformer 非自回归模型"),

    /**
     * SenseVoice 多语言模型（单个 model.int8.onnx + tokens.txt，自带语种/情感/事件标签）。
     */
    SENSEVOICE("sensevoice", "SenseVoice 多语言模型"),

    /**
     * Whisper 编码器/解码器双模型（*.encoder.onnx + *.decoder.onnx + tokens.txt）。
     */
    WHISPER("whisper", "Whisper 编解码模型"),

    /**
     * Zipformer Transducer 三模型（encoder + decoder + joiner + tokens.txt）。
     */
    TRANSDUCER("transducer", "Zipformer/Conformer Transducer 模型"),

    /**
     * Zipformer CTC 单模型（model.onnx + tokens.txt）。
     */
    ZIPFORMER_CTC("zipformer-ctc", "Zipformer CTC 模型");

    /**
     * 供 SPI 名称、系统属性与日志使用的短标识。
     */
    private final String code;

    /**
     * 面向使用者的中文说明。
     */
    private final String description;

    /**
     * 构造枚举项。
     *
     * @param code        短标识
     * @param description 中文说明
     */
    SherpaModelType(String code, String description) {
        this.code = code;
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
     * 获取中文说明。
     *
     * @return 说明文本
     */
    public String description() {
        return description;
    }

    /**
     * 按短标识解析枚举，大小写与 {@code -}/{@code _} 差异均忽略。
     *
     * @param text 短标识，可为 {@code null}
     * @return 匹配的枚举项；无法识别返回 {@code null}
     */
    public static SherpaModelType of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SherpaModelType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        if (normalized.contains("sense")) {
            return SENSEVOICE;
        }
        if (normalized.contains("whisper")) {
            return WHISPER;
        }
        if (normalized.contains("paraformer")) {
            return PARAFORMER;
        }
        if (normalized.contains("ctc")) {
            return ZIPFORMER_CTC;
        }
        if (normalized.contains("transducer") || normalized.contains("zipformer")) {
            return TRANSDUCER;
        }
        return null;
    }
}
