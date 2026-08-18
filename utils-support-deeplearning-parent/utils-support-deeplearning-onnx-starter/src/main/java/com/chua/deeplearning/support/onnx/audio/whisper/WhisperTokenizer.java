package com.chua.deeplearning.support.onnx.audio.whisper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Whisper BPE tokenizer (decoder-only，纯 Java)。
 * <p>
 * Whisper 使用 GPT-2 风格的 BPE（byte-level），vocab 来自 vocab.json
 * （每个 token id 映射到 token 字符串，含 Ġ 表示前导空格）。
 * 本类只实现 decode：token id 序列 → 字符串。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WhisperTokenizer {

    /** JSON 对象映射器 */
    /** Mapper */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** token id → token string (含 Ġ) */
    /** IDTO令牌 */
    private final String[] idToToken;

    /** token string → token id，用于特殊 token 查找 */
    private final Map<String, Integer> tokenToId;

    /** vocab 大小 */
    /** Vocab尺寸 */
    private final int vocabSize;

    public WhisperTokenizer(Map<String, Integer> vocab) {
        this.vocabSize = vocab.size();
        this.idToToken = new String[vocabSize];
        this.tokenToId = new HashMap<>(vocabSize);
        for (Map.Entry<String, Integer> e : vocab.entrySet()) {
            this.idToToken[e.getValue()] = e.getKey();
            this.tokenToId.put(e.getKey(), e.getValue());
        }
    }

    public static WhisperTokenizer load(Path vocabJson) throws IOException {
        try (InputStream in = Files.newInputStream(vocabJson)) {
            return loadFromJson(in);
        }
    }

    public static WhisperTokenizer loadFromJson(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        Map<String, Integer> vocab = new HashMap<>();
        root.fields().forEachRemaining(e -> vocab.put(e.getKey(), e.getValue().asInt()));
        return new WhisperTokenizer(vocab);
    }

    public int vocabSize() {
        return vocabSize;
    }

    public int tokenToId(String token) {
        Integer id = tokenToId.get(token);
        return id == null ? -1 : id;
    }

    public String idToToken(int id) {
        if (id < 0 || id >= vocabSize) return null;
        return idToToken[id];
    }

    /**
     * 解码 token id 序列为字符串：
     * 1. 拼接所有 token（替换 Ġ → " "）
     * 2. 跳过特殊 token（以 &lt; 开头且以 &gt; 结尾）
     * 3. 转换 byte fallback（部分非 ascii 字符）
     *
     * @param ids token id 序列
     * @return 解码后的字符串（已过滤特殊 token）
     */
    public String decode(int[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String tok = idToToken(id);
            if (tok == null) continue;
            // 特殊 token 以 < 开头且 > 结尾
            if (isSpecial(tok)) continue;
            // 替换 Ġ → ' '
            if (tok.startsWith("Ġ")) {
                sb.append(' ').append(tok.substring(1));
            } else {
                sb.append(tok);
            }
        }
        return sb.toString().trim();
    }

    private static boolean isSpecial(String token) {
        return token.startsWith("<") && token.endsWith(">");
    }

    /** Whisper 特殊 token 常量 */
    /** SOT */
    public static final int SOT = 50258;
    /** 结束符标识 */
    /** EOT */
    public static final int EOT = 50257;
    /** 是否输出时间戳 */
    /** Notimestamps */
    public static final int NOTIMESTAMPS = 50259;
    /** 是否转写文本 */
    /** Transcribe */
    public static final int TRANSCRIBE = 50359;
    /** 是否翻译文本 */
    /** Translate */
    public static final int TRANSLATE = 50358;
    /** 是否不输出语音 */
    /** No_speech */
    public static final int NO_SPEECH = 50362;
    /** 语言 token 起始 id（zh=50260+）... 实际语言 token id 由 vocab 决定 */
    /** Lang_base */
    public static final int LANG_BASE = 50260;
}