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
* 纯 Java 实现的 BERT wordpiece tokenizer（不依赖 Rust tokenizers / DJL）。
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
    * 起始 令牌（[CLS]）
    */
    public static final String CLS = "[CLS]";

    /**
    * 结束 令牌（[SEP]）
    */
    public static final String SEP = "[SEP]";

    /**
    * 填充 令牌（[PAD]）
    */
    public static final String PAD = "[PAD]";

    /**
    * 未登录词 令牌（[UNK]）
    */
    public static final String UNK = "[UNK]";

    /**
    * 前缀子词标识，wordpiece 切分后所有非起始子词都需带此前缀
    */
    private static final String SUBWORD_PREFIX = "##";

    /** 词表映射 */
    private final Map<String, Integer> vocab;
    /** 未知词标识 */
    /** UNKID */
    private final int unkId;
    /** 类别标识 */
    /** CLSID */
    private final int clsId;
    /** 分隔符标识 */
    /** SEPID */
    private final int sepId;
    /** 填充符标识 */
    /** PADID */
    private final int padId;
    /** 是否转小写 */
    /** dolowercase */
    private final boolean doLowerCase;
    /** 是否分词中文字符 */
    /** Tokenizechinesechars */
    private final boolean tokenizeChineseChars;

    /**
    * 创建 minilmtokenizer 实例
    * @param vocab Integer
    * @param vocab vocab
    * @param doLowerCase 布尔值
    * @param doLowerCase 布尔值
    * @param doLowerCase 执行降低大小写
    * @param tokenizeChineseChars tokenizechinesechars
    */
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
    * @param vocabPath vocab.txt 路径，每行一个 令牌
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
    * @return vocab大小的结果
    */
    public int vocabSize() {
        return vocab.size();
    }

    /**
    * unkid
    *
    * @return unkId的结果
    */
    public int unkId() {
        return unkId;
    }

    /**
    * clsid
    *
    * @return clsId的结果
    */
    public int clsId() {
        return clsId;
    }

    /**
    * sepid
    *
    * @return sepId的结果
    */
    public int sepId() {
        return sepId;
    }

    /**
    * padid
    *
    * @return padId的结果
    */
    public int padId() {
        return padId;
    }

    /**
    * BERT 风格单句编码结果：输入_标识 / attention_mask / 令牌_类型_标识 三个等长数组。
    */
    public static final class EncodeResult {
        /** 输入标识数组 */
        /** 输入标识 */
        public final int[] inputIds;
        /** 注意力掩码 */
        /** Attention掩码 */
        public final int[] attentionMask;
        /** 标记类型标识数组 */
        /** 令牌类型标识 */
        public final int[] tokenTypeIds;

        /**
        * 创建 encode结果 实例
        * @param inputIds 输入标识
        * @param int int
        * @param attentionMask attentionmask
        * @param int int
        * @param tokenTypeIds 令牌类型标识
        * @return encode结果的结果
        */
        public EncodeResult(int[] inputIds, int[] attentionMask, int[] tokenTypeIds) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
        }
    }

    /**
    * 编码单句为 [CLS] + 令牌 + [SEP]，右侧按 [PAD] 补齐到 最大len。
    *
    * @param text   输入文本
    * @param maxLen 最大序列长度（必须 ≥ 2，包含 [CLS]/[SEP]）
    * @return 三个长度均为 最大len 的 int[] 数组
    */
    public EncodeResult encode(String text, int maxLen) {
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
            tokenTypeIds[i] = 0;
        }
        for (int i = tokenIds.size(); i < maxLen; i++) {
            inputIds[i] = padId;
            attentionMask[i] = 0;
            tokenTypeIds[i] = 0;
        }
        return new EncodeResult(inputIds, attentionMask, tokenTypeIds);
    }

    /**
    * 基础切分：清理空白、剥离重音、CJK 字符逐字、小写化。
    * @param text 文本
    * @return basicTokenize的结果
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
    * 按空白 + 标点切分（保留缩写、空格分隔后输出独立 令牌）。
    * @param text 文本
    * @return 分割onwhitespace和punct的结果
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
    * @param c c
    * @return 是否punctuation的结果
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

    /**
    * 是否Cjk
    *
    * @param c c
    * @return 是否cjk的结果
    */
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
    * @param s s
    * @return stripAccents的结果
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
    * wordpiece greedy longest-匹配-第一个。
    * @param token 令牌
    * @return wordpieceTokenize的结果
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
