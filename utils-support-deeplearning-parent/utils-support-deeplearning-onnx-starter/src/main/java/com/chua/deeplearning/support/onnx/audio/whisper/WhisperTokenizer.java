package com.chua.deeplearning.support.onnx.audio.whisper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Whisper BPE tokenizer（decoder-only，纯 Java）。
 * <p>
 * Whisper 使用 GPT-2 风格的 BPE（byte-level），vocab 来自 vocab.json
 * （每个 token id 映射到 token 字符串，含 Ġ 表示前导空格）。
 * 特殊 token（SOT/EOT/NOTIMESTAMP 等）来自 tokenizer.json 的 added_tokens。
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

    /** 完整 vocab 大小（51864 = BPE 50257 + 特殊 token 1607） */
    public static final int VOCAB_SIZE = 51864;

    /** token id → token string（含 Ġ 前导空格标记） */
    private final String[] idToToken;

    /** token string → token id，用于特殊 token 查找 */
    private final Map<String, Integer> tokenToId;

    /** vocab 大小 */
    private final int vocabSize;

    /**
     * 创建 WhisperTokenizer 实例
     *
     * @param idToToken token id → token string 数组
     * @param tokenToId token string → token id 映射
     * @param vocabSize vocab 大小
     */
    private WhisperTokenizer(String[] idToToken, Map<String, Integer> tokenToId, int vocabSize) {
        this.idToToken = idToToken;
        this.tokenToId = tokenToId;
        this.vocabSize = vocabSize;
    }

    /**
     * 从 vocab.json 加载（兼容旧版，不含特殊 token）。
     *
     * @param vocabJson vocab.json 路径
     * @return tokenizer 实例
     */
    public static WhisperTokenizer load(Path vocabJson) throws IOException {
        return load(vocabJson, vocabJson.resolveSibling("tokenizer.json"));
    }

    /**
     * 从 vocab.json + tokenizer.json 加载完整 vocab（含特殊 token）。
     *
     * @param vocabJson  vocab.json 路径
     * @param tokJson    tokenizer.json 路径
     * @return tokenizer 实例
     */
    public static WhisperTokenizer load(Path vocabJson, Path tokJson) throws IOException {
        // 1. 加载 BPE vocab（自动识别方向：id→token 或 token→id）
        Map<Integer, String> idToTokenMap = new HashMap<>();
        try (InputStream in = Files.newInputStream(vocabJson)) {
            JsonNode root = MAPPER.readTree(in);
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            root.fields().forEachRemaining(entries::add);
            boolean idKeyed = !entries.isEmpty() && isIntKey(entries.get(0).getKey());
            for (Map.Entry<String, JsonNode> e : entries) {
                if (idKeyed) {
                    try {
                        idToTokenMap.put(Integer.parseInt(e.getKey()), e.getValue().asText());
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                } else {
                    // token→id 格式（如 Xenova 导出），反转
                    try {
                        idToTokenMap.put(e.getValue().asInt(), e.getKey());
                    } catch (Exception ignored) {
                        // skip
                    }
                }
            }
        }

        // 2. 合并 tokenizer.json 的 added_tokens（覆盖重复 ID）
        if (Files.exists(tokJson)) {
            try (InputStream in = Files.newInputStream(tokJson)) {
                JsonNode root = MAPPER.readTree(in);
                JsonNode added = root.path("added_tokens");
                if (added.isArray()) {
                    for (JsonNode t : added) {
                        int id = t.path("id").asInt();
                        String content = t.path("content").asText();
                        idToTokenMap.put(id, content);
                    }
                }
            }
        }

        // 3. 构建定长 idToToken 数组（大小 = VOCAB_SIZE）
        String[] idToTokenArr = new String[VOCAB_SIZE];
        Map<String, Integer> tokenToIdMap = new HashMap<>(VOCAB_SIZE);
        for (Map.Entry<Integer, String> e : idToTokenMap.entrySet()) {
            int id = e.getKey();
            String tok = e.getValue();
            if (id >= 0 && id < VOCAB_SIZE) {
                idToTokenArr[id] = tok;
                tokenToIdMap.put(tok, id);
            }
        }

        return new WhisperTokenizer(idToTokenArr, tokenToIdMap, VOCAB_SIZE);
    }

    /** Vocab 获取大小 */
    public int vocabSize() {
        return vocabSize;
    }

    /** TokenToId */
    public int tokenToId(String token) {
        Integer id = tokenToId.get(token);
        return id == null ? -1 : id;
    }

    /** IdToToken */
    public String idToToken(int id) {
        if (id < 0 || id >= vocabSize) return null;
        return idToToken[id];
    }

    /**
     * 解码 token id 序列为字符串：
     * <ol>
     *   <li>拼接所有 token（替换 Ġ → " "）</li>
     *   <li>跳过特殊 token（以 &lt; 开头且以 &gt; 结尾）</li>
     *   <li>转换 byte fallback（部分非 ascii 字符）</li>
     * </ol>
     *
     * @param ids token id 序列
     * @return 解码后的字符串（已过滤特殊 token）
     */
    public String decode(int[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String tok = idToToken(id);
            if (tok == null) continue;
            // 特殊 token 以 &lt; 开头且 &gt; 结尾
            if (isSpecial(tok)) continue;
            // 替换 Ġ → ' '
            if (tok.startsWith("\u0120")) {
                sb.append(' ').append(tok.substring(1));
            } else {
                sb.append(tok);
            }
        }
        return sb.toString().trim();
    }

    /** 是否 Special */
    private static boolean isSpecial(String token) {
        return token.startsWith("<") && token.endsWith(">");
    }

    /**
     * Whisper 特殊 token 常量（与 HuggingFace whisper tokenizer 一致）。
     * 来源: config.json bos_token_id=50257, eos_token_id=50256
     */

    /** Start Of Transcription */
    public static final int SOT = 50257;
    /** End Of Transcription */
    public static final int EOT = 50256;
    /** No Timestamps */
    public static final int NOTIMESTAMPS = 50362;
    /** Transcribe */
    public static final int TRANSCRIBE = 50358;
    /** Translate */
    public static final int TRANSLATE = 50357;
    /** No Speech */
    public static final int NO_SPEECH = 50362;
    /** Language token base id */
    public static final int LANG_BASE = 50260;
    /** 判断字符串是否为整数键 */
    private static boolean isIntKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }
}
