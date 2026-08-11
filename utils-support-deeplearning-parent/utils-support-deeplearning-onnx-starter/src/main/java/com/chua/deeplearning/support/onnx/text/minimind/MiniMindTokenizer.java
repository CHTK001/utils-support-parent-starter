package com.chua.deeplearning.support.onnx.text.minimind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MiniMind BPE Tokenizer（纯 Java 实现，零外部依赖）。
 * <p>
 * 支持 BPE 合并 + ByteLevel pre-tokenizer（GPT-2 风格字节到 Unicode 映射）。
 * 直接解析 HuggingFace {@code tokenizer.json}，绕过 DJL 自带的 Rust tokenizers
 * （后者对 minimind 的较新格式兼容性差，会抛 "untagged enum ModelWrapper"）。
 * </p>
 * <p>
 * 用法：
 * <pre>
 *   MiniMindTokenizer tk = MiniMindTokenizer.load(Path.of("tokenizer.json"));
 *   int[] ids = tk.encode("你好，世界");
 *   String text = tk.decode(ids);
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MiniMindTokenizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Integer> vocab;
    private final Map<Integer, String> reverseVocab;
    private final Map<String, Integer> bpeRanks;
    private final Map<Integer, Character> byteToUnicode;
    private final Map<Character, Integer> unicodeToByte;
    private final int vocabSize;
    private final Map<String, Integer> addedTokens;

    private MiniMindTokenizer(Map<String, Integer> vocab,
                              Map<String, Integer> addedTokens,
                              List<String> merges) {
        this.vocab = vocab;
        this.reverseVocab = new HashMap<>();
        for (Map.Entry<String, Integer> e : vocab.entrySet()) {
            reverseVocab.put(e.getValue(), e.getKey());
        }
        this.addedTokens = addedTokens;
        this.bpeRanks = new HashMap<>();
        for (int i = 0; i < merges.size(); i++) {
            String[] parts = merges.get(i).split(" ", 2);
            if (parts.length == 2) {
                bpeRanks.put(parts[0] + "" + parts[1], i);
            }
        }
        Map<Integer, Character> b2u = new LinkedHashMap<>();
        Map<Character, Integer> u2b = new LinkedHashMap<>();
        int n = 0;
        for (int b = 0; b < 256; b++) {
            boolean printable = (b >= 33 && b <= 126)
                    || (b >= 161 && b <= 172)
                    || (b >= 174 && b <= 255);
            if (printable) {
                b2u.put(b, (char) b);
                u2b.put((char) b, b);
            } else {
                int cp = 256 + n;
                b2u.put(b, (char) cp);
                u2b.put((char) cp, b);
                n++;
            }
        }
        this.byteToUnicode = b2u;
        this.unicodeToByte = u2b;
        this.vocabSize = vocab.size();
    }

    /**
     * 从 tokenizer.json 路径加载
     */
    public static MiniMindTokenizer load(Path tokenizerJson) throws IOException {
        try (InputStream in = Files.newInputStream(tokenizerJson)) {
            return loadFromJson(in);
        }
    }

    /**
     * 从 InputStream 加载（用于 classpath 资源）
     */
    public static MiniMindTokenizer loadFromJson(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        JsonNode model = root.get("model");
        Map<String, Integer> vocab = new LinkedHashMap<>();
        JsonNode vocabNode = model.get("vocab");
        if (vocabNode != null && vocabNode.isObject()) {
            vocabNode.fields().forEachRemaining(e -> vocab.put(e.getKey(), e.getValue().asInt()));
        } else if (vocabNode != null && vocabNode.isArray()) {
            for (int i = 0; i < vocabNode.size(); i++) {
                vocab.put(vocabNode.get(i).asText(), i);
            }
        }
        Map<String, Integer> addedTokens = new HashMap<>();
        JsonNode added = root.get("added_tokens");
        if (added != null && added.isArray()) {
            for (int i = 0; i < added.size(); i++) {
                JsonNode t = added.get(i);
                addedTokens.put(t.get("content").asText(), t.get("id").asInt());
            }
        }
        List<String> merges = new ArrayList<>();
        JsonNode mergesNode = model.get("merges");
        if (mergesNode != null && mergesNode.isArray()) {
            for (int i = 0; i < mergesNode.size(); i++) {
                JsonNode m = mergesNode.get(i);
                if (m.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < m.size(); j++) {
                        if (j > 0) sb.append(' ');
                        sb.append(m.get(j).asText());
                    }
                    merges.add(sb.toString());
                } else {
                    merges.add(m.asText());
                }
            }
        }
        return new MiniMindTokenizer(vocab, addedTokens, merges);
    }

    public int vocabSize() {
        return vocabSize;
    }

    public int addedTokenId(String token) {
        Integer id = addedTokens.get(token);
        return id == null ? -1 : id;
    }

    /**
     * 编码：字符串 → token ids。
     * <p>
     * 1. 按 ByteLevel pre-tokenizer 切分（GPT-2 风格）：
     *    - 拆分空白、标点
     *    - 把每个 byte 映射到 printable unicode
     * 2. 对每个词做 BPE 合并
     * 3. 查 vocab 取 id
     * </p>
     */
    public int[] encode(String text) {
        if (text == null || text.isEmpty()) {
            return new int[0];
        }
        List<String> preTokens = byteLevelPreTokenize(text);
        List<Integer> ids = new ArrayList<>();
        for (String pre : preTokens) {
            if (addedTokens.containsKey(pre)) {
                ids.add(addedTokens.get(pre));
                continue;
            }
            List<String> bpeTokens = bpe(pre);
            for (String bt : bpeTokens) {
                Integer id = vocab.get(bt);
                if (id == null) {
                    if (addedTokens.containsKey(bt)) {
                        ids.add(addedTokens.get(bt));
                    } else {
                        // 未知 token：尝试按 byte 编码（fallback）
                        for (char c : bt.toCharArray()) {
                            Integer cid = vocab.get(String.valueOf(c));
                            if (cid != null) {
                                ids.add(cid);
                            }
                        }
                    }
                } else {
                    ids.add(id);
                }
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 解码：token ids → 字符串。
     * <p>
     * 1. 查 reverseVocab 取 token
     * 2. 拼接 token 字符串
     * 3. 把 printable unicode 反向映射回 bytes，再按 UTF-8 解码
     * </p>
     */
    public String decode(int[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String tok = reverseVocab.get(id);
            if (tok != null) {
                sb.append(tok);
            }
        }
        return byteLevelDecode(sb.toString());
    }

    /**
     * ByteLevel pre-tokenize：按 GPT-2 风格切分。
     * 使用一个简单但可用的正则：
     * - 's|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+
     */
    private static final Pattern BYTE_LEVEL_PATTERN = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d"
                    + "| ?[\\p{L}]+"
                    + "| ?[\\p{N}]+"
                    + "| ?[^\\s\\p{L}\\p{N}]+"
                    + "|\\s+(?!\\S)"
                    + "|\\s+"
    );

    private List<String> byteLevelPreTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher m = BYTE_LEVEL_PATTERN.matcher(text);
        while (m.find()) {
            String token = m.group();
            byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
            StringBuilder mapped = new StringBuilder();
            for (byte b : bytes) {
                int unsigned = b & 0xFF;
                Character unicodeChar = byteToUnicode.get(unsigned);
                if (unicodeChar != null) {
                    mapped.append(unicodeChar);
                }
            }
            tokens.add(mapped.toString());
        }
        return tokens;
    }

    /**
     * 对单个 word 做 BPE 合并
     */
    private List<String> bpe(String word) {
        if (word.isEmpty()) {
            return Collections.emptyList();
        }
        // 初始：每个 byte 一个 token
        List<String> wordPieces = new ArrayList<>();
        for (char c : word.toCharArray()) {
            wordPieces.add(String.valueOf(c));
        }
        // 贪心 BPE 合并
        while (wordPieces.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < wordPieces.size() - 1; i++) {
                String pair = wordPieces.get(i) + "" + wordPieces.get(i + 1);
                Integer rank = bpeRanks.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            String merged = wordPieces.get(bestIdx) + wordPieces.get(bestIdx + 1);
            List<String> next = new ArrayList<>();
            for (int i = 0; i < wordPieces.size(); i++) {
                if (i == bestIdx) {
                    next.add(merged);
                    i++;
                } else {
                    next.add(wordPieces.get(i));
                }
            }
            wordPieces = next;
        }
        return wordPieces;
    }

    /**
     * 把 ByteLevel token 字符串（printable unicode 序列）反向映射回 bytes，再按 UTF-8 解码。
     */
    private String byteLevelDecode(String text) {
        byte[] bytes = new byte[text.length()];
        int len = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Integer b = unicodeToByte.get(c);
            if (b != null) {
                bytes[len++] = b.byteValue();
            } else if (c < 256) {
                bytes[len++] = (byte) c;
            } else {
                // 不在映射表中（理论上不应出现）：保留 unicode（UTF-8 编码）
                // 这种情况意味着原 token 包含非 byte-level 字符，极少
                byte[] enc = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (len + enc.length > bytes.length) {
                    byte[] nb = new byte[bytes.length + enc.length];
                    System.arraycopy(bytes, 0, nb, 0, len);
                    bytes = nb;
                }
                System.arraycopy(enc, 0, bytes, len, enc.length);
                len += enc.length;
            }
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
