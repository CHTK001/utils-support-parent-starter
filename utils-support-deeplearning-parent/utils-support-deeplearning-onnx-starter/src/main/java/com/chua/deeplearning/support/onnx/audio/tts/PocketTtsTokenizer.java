package com.chua.deeplearning.support.onnx.audio.tts;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pocket-TTS 专用 tokenizer（基于 vocab.json 的 BPE 分词器）。
 *
 * <p>Pocket-TTS 使用 sentencepiece 训练的分词器。本类从 vocab.json 加载词表，
 * 实现贪心最长匹配（Greedily Longest 匹配）进行文本分词。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PocketTtsTokenizer {

    /** vocab.json 中的 令牌 标识 正则模式 */
    private static final Pattern VOCAB_PATTERN =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)");

    /** 单次最大匹配长度（字符） */
    private static final int MAX_MATCH_LEN = 20;

    /** 词表：令牌 字符串 → 标识 */
    private final Map<String, Integer> vocab = new LinkedHashMap<>(4096);

    /** BOS 令牌 标识 */
    private int bosId = 1;
    /** EOS 令牌 标识 */
    private int eosId = 2;
    /** UNK 令牌 标识 */
    private int unkId = 0;
    /** PAD 令牌 标识 */
    private int padId = 3;

    /**
    * 从 vocab.json 路径加载词表。
    *
    * @param vocabPath vocab.json 文件路径
    * @throws IOException IO 异常
    */
    public void load(Path vocabPath) throws IOException {
        String content = Files.readString(vocabPath, StandardCharsets.UTF_8);
        parseVocab(content);
        log.info("[PocketTtsTokenizer] 加载词表: {} tokens, bos={}, eos={}, unk={}, pad={}",
                vocab.size(), bosId, eosId, unkId, padId);
    }

    /**
     * 解析 vocab.json 内容（简单 JSON 对象格式）。
     *
     * @param json JSON 字符串
     */
    private void parseVocab(String json) {
        String inner = json.trim();
        if (inner.startsWith("{")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("}")) {
            inner = inner.substring(0, inner.length() - 1);
        }

        java.util.regex.Matcher matcher = VOCAB_PATTERN.matcher(inner);
        while (matcher.find()) {
            String key = matcher.group(1);
            int id = Integer.parseInt(matcher.group(2));
            vocab.put(key, id);
            if ("<s>".equals(key)) {
                bosId = id;
            } else if ("</s>".equals(key)) {
                eosId = id;
            } else if ("<unk>".equals(key)) {
                unkId = id;
            } else if ("<pad>".equals(key)) {
                padId = id;
            }
        }
    }

    /**
     * 将文本编码为 令牌 标识 序列。
     * <p>策略：贪心最长匹配（从左到右，优先匹配词表中最长的前缀）。
     * 未登录字符以单个字符查找，若仍不在词表中则使用 UNK。</p>
     *
     * @param text 输入文本
     * @return token 标识 数组
     */
    public long[] encode(String text) {
        if (text == null || text.isBlank()) {
            return new long[]{(long) unkId};
        }

        String processed = text.strip();
        if (!processed.isEmpty() && Character.isLowerCase(processed.charAt(0))) {
            processed = Character.toUpperCase(processed.charAt(0)) + processed.substring(1);
        }
        if (!processed.isEmpty()) {
            char last = processed.charAt(processed.length() - 1);
            if (last != '.' && last != '!' && last != '?') {
                processed = processed + ".";
            }
        }

 // 预估 令牌 数量：每 2 个字符约 1 个 令牌，加上 bos/eos
        List<Long> ids = new ArrayList<>((processed.length() + 1) / 2 + 2);
        ids.add((long) bosId);

        int pos = 0;
        while (pos < processed.length()) {
            boolean matched = false;
            for (int len = Math.min(MAX_MATCH_LEN, processed.length() - pos); len >= 1; len--) {
                String sub = processed.substring(pos, pos + len);
                Integer id = vocab.get(sub);
                if (id != null) {
                    ids.add((long) id);
                    pos += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                String ch = String.valueOf(processed.charAt(pos));
                Integer id = vocab.get(ch);
                if (id != null) {
                    ids.add((long) id);
                } else {
                    ids.add((long) unkId);
                }
                pos++;
            }
        }

        ids.add((long) eosId);
        return toLongArray(ids);
    }

    /**
     * 获取词表大小。
     *
     * @return 词表大小
     */
    public int vocabSize() {
        return vocab.size();
    }

    /**
     * 列表 转 long 数组。
     *
     * @param list 元素列表
     * @return long 数组
     */
    private static long[] toLongArray(List<Long> list) {
        long[] arr = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
