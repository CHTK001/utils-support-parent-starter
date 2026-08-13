package com.chua.deeplearning.support.onnx.embedding.minilm;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯 Java 实现的 BERT WordPiece tokenizer（不依赖 Rust tokenizers / DJL）。
 *
 * <p>用于 {@link MiniLMEmbeddingTranslator} 加载 Xenova/all-MiniLM-L6-v2
 * 配套的 {@code vocab.txt} + {@code tokenizer_config.json}：
 * <ul>
 *   <li>{@code do_lower_case=true}：文本统一转小写</li>
 *   <li>{@code tokenize_chinese_chars=true}：CJK 字符逐字切分（前后加空格）</li>
 *   <li>{@code strip_accents=true}（默认）：剥离重音符号</li>
 *   <li>WordPiece greedy longest-match-first，{@code [UNK]}=兜底</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniLMTokenizer {

    /**
     * 起始 token（[CLS]）
     */
    public static final String CLS = "[CLS]";

    /**
     * 结束 token（[SEP]）
     */
    public static final String SEP = "[SEP]";

    /**
     * 填充 token（[PAD]）
     */
    public static final String PAD = "[PAD]";

    /**
     * 未登录词 token（[UNK]）
     */
    public static final String UNK = "[UNK]";

    /**
     * 前缀子词标识，WordPiece 切分后所有非起始子词都需带此前缀
     */
    private static final String SUBWORD_PREFIX = "##";

    private final Map<String, Integer> vocab;
    private final int unkId;
    private final int clsId;
    private final int sepId;
    private final int padId;
    private final boolean doLowerCase;
    private final boolean tokenizeChineseChars;

    public MiniLMTokenizer(Map<String, Integer> vocab, boolean doLowerCase, boolean tokenizeChineseChars) {
        this.vocab = vocab;
        this.doLowerCase = doLowerCase;
        this.tokenizeChineseChars = tokenizeChineseChars;
        this.unkId = vocab.getOrDefault(UNK, 100);
        this.clsId = vocab.getOrDefault(CLS, 101);
        this.sepId = vocab.getOrDefault(SEP, 102);
        this.padId = vocab.getOrDefault(PAD, 0);
    }

    /**
     * 从 vocab.txt 加载词表。
     *
     * @param vocabPath vocab.txt 路径，每行一个 token
     * @return 加载好的 tokenizer
     */
    public static MiniLMTokenizer load(Path vocabPath) throws IOException {
        Map<String, Integer> vocab = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(vocabPath)) {
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                vocab.put(line, idx++);
            }
        }
        log.info("[MiniLM] vocab loaded: tokens={}", vocab.size());
        return new MiniLMTokenizer(vocab, true, true);
    }

    /**
     * 词表大小
     */
    public int vocabSize() {
        return vocab.size();
    }

    public int unkId() {
        return unkId;
    }

    public int clsId() {
        return clsId;
    }

    public int sepId() {
        return sepId;
    }

    public int padId() {
        return padId;
    }

    /**
     * 编码单句为 token ids 序列（[CLS] + tokens + [SEP]），并按 maxLen 右侧填充 [PAD]。
     *
     * @param text   输入文本
     * @param maxLen 最大序列长度（包含 [CLS]/[SEP]）
     * @return 长度为 maxLen 的 inputIds、attentionMask、tokenTypeIds 三个数组
     */
    public int[] encodeOne(String text, int maxLen) {
        List<Integer> tokenIds = new ArrayList<>();
        tokenIds.add(clsId);
        for (String token : basicTokenize(text)) {
            for (int subId : wordpieceTokenize(token)) {
                tokenIds.add(subId);
                if (tokenIds.size() >= maxLen - 1) {
                    break;
                }
            }
            if (tokenIds.size() >= maxLen - 1) {
                break;
            }
        }
        tokenIds.add(sepId);

        int[] inputIds = new int[maxLen];
        int[] attentionMask = new int[maxLen];
        int[] tokenTypeIds = new int[maxLen];
        for (int i = 0; i < tokenIds.size() && i < maxLen; i++) {
            inputIds[i] = tokenIds.get(i);
            attentionMask[i] = 1;
        }
        for (int i = tokenIds.size(); i < maxLen; i++) {
            inputIds[i] = padId;
            attentionMask[i] = 0;
        }
        return inputIds;
    }

    /**
     * 基础切分：清理空白、剥离重音、CJK 字符逐字、小写化。
     */
    private List<String> basicTokenize(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        String s = text;
        if (doLowerCase) {
            s = s.toLowerCase(Locale.ROOT);
        }
        s = stripAccents(s);

        List<String> tokens = new ArrayList<>();
        if (tokenizeChineseChars) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (isCjk(c)) {
                    if (sb.length() > 0) {
                        tokens.addAll(splitOnWhitespaceAndPunct(sb.toString()));
                        sb.setLength(0);
                    }
                    tokens.add(Character.toString(c));
                } else {
                    sb.append(c);
                }
            }
            if (sb.length() > 0) {
                tokens.addAll(splitOnWhitespaceAndPunct(sb.toString()));
            }
        } else {
            tokens.addAll(splitOnWhitespaceAndPunct(s));
        }
        return tokens;
    }

    /**
     * 按空白 + 标点切分（保留缩写、空格分隔后输出独立 token）。
     */
    private List<String> splitOnWhitespaceAndPunct(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
            } else if (isPunctuation(c)) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                out.add(Character.toString(c));
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString());
        }
        return out;
    }

    /**
     * Unicode 标点判断（C 0-未分类、U 0-未分类、ASCII 标点、ASCII 控制）。
     */
    private static boolean isPunctuation(char c) {
        int cp = c;
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64)
                || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        return Character.getType(c) == Character.OTHER_PUNCTUATION
                || Character.getType(c) == Character.CONNECTOR_PUNCTUATION
                || Character.getType(c) == Character.DASH_PUNCTUATION
                || Character.getType(c) == Character.END_PUNCTUATION
                || Character.getType(c) == Character.FINAL_QUOTE_PUNCTUATION
                || Character.getType(c) == Character.INITIAL_QUOTE_PUNCTUATION
                || Character.getType(c) == Character.START_PUNCTUATION
                || Character.getType(c) == Character.MATH_SYMBOL;
    }

    private static boolean isCjk(char c) {
        int cp = c;
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F)
                || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    /**
     * 剥离拉丁重音符号（normalize NFD + 移除组合标记）。
     */
    private static String stripAccents(String s) {
        try {
            String nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
            StringBuilder sb = new StringBuilder(nfd.length());
            for (int i = 0; i < nfd.length(); i++) {
                char c = nfd.charAt(i);
                if (Character.getType(c) != Character.NON_SPACING_MARK) {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * WordPiece greedy longest-match-first。
     */
    private List<Integer> wordpieceTokenize(String token) {
        if (token.isEmpty()) {
            return new ArrayList<>();
        }
        if (vocab.containsKey(token)) {
            List<Integer> single = new ArrayList<>();
            single.add(vocab.get(token));
            return single;
        }
        List<Integer> out = new ArrayList<>();
        LinkedList<String> subTokens = new LinkedList<>();
        for (int start = 0; start < token.length(); ) {
            int end = token.length();
            String cur = null;
            while (start < end) {
                String sub = token.substring(start, end);
                if (start > 0) {
                    sub = SUBWORD_PREFIX + sub;
                }
                if (vocab.containsKey(sub)) {
                    cur = sub;
                    break;
                }
                end--;
            }
            if (cur == null) {
                out.add(unkId);
                return out;
            }
            subTokens.add(cur);
            start = end;
        }
        for (String s : subTokens) {
            out.add(vocab.getOrDefault(s, unkId));
        }
        return out;
    }
}
