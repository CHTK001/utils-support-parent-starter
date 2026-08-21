package com.chua.deeplearning.support.onnx.audio.tts;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Pocket-TTS 专用 tokenizer（基于 vocab.json 的简单 BPE/字符级分词器）。
 *
 * <p>Pocket-TTS 使用 sentencepiece 训练的分词器，但 sherpa-onnx 导出包中只提供了
 * vocab.json（词表映射），未提供完整的 tokenizer.json。本类实现一个轻量级分词器：
 * <ul>
 *   <li>优先匹配词表中较长的子词（贪心最长匹配）</li>
 *   <li>未登录词按字符切分</li>
 *   <li>支持 <s>、</s>、<unk>、<pad> 等特殊 token</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PocketTtsTokenizer {

    /** 词表：token string → id */
    private final Map<String, Integer> vocab = new LinkedHashMap<>();

    /** 反向词表：id → token string */
    private final Map<Integer, String> idToToken = new LinkedHashMap<>();

    /** 特殊 token */
    private final Set<String> specialTokens = new HashSet<>();

    /** BOS token id */
    private int bosId = 1;
    /** EOS token id */
    private int eosId = 2;
    /** UNK token id */
    private int unkId = 0;
    /** PAD token id */
    private int padId = 3;

    /**
     * 从 vocab.json 路径加载词表。
     *
     * @param vocabPath vocab.json 文件路径
     */
    public void load(Path vocabPath) throws IOException {
        String content = Files.readString(vocabPath, StandardCharsets.UTF_8);
        vocab.clear();
        idToToken.clear();
        specialTokens.clear();

        // 简单 JSON 解析：{"token": id, ...}
        String inner = content.trim();
        if (inner.startsWith("{")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("}")) {
            inner = inner.substring(0, inner.length() - 1);
        }

        // 逐对解析
        Scanner scanner = new Scanner(inner);
        scanner.useDelimiter("\"");
        int idx = 0;
        while (scanner.hasNext()) {
            String key = scanner.hasNext() ? scanner.next() : null;
            if (key == null) break;
            // 跳过冒号和空格
            String colon = scanner.hasNext() ? scanner.next() : "";
            String valStr = scanner.hasNext() ? scanner.next() : "0";
            try {
                int id = Integer.parseInt(valStr.trim());
                vocab.put(key, id);
                idToToken.put(id, key);
                if (key.startsWith("<") && key.endsWith(">")) {
                    specialTokens.add(key);
                    switch (key) {
                        case "<s>": bosId = id; break;
                        case "</s>": eosId = id; break;
                        case "<unk>": unkId = id; break;
                        case "<pad>": padId = id; break;
                    }
                }
            } catch (NumberFormatException e) {
                // 跳过
            }
            idx++;
        }
        scanner.close();

        log.info("[PocketTtsTokenizer] 加载词表: {} tokens, bos={}, eos={}, unk={}, pad={}",
                vocab.size(), bosId, eosId, unkId, padId);
    }

    /**
     * 将文本编码为 token id 序列。
     *
     * <p>策略：贪心最长匹配（从左到右，优先匹配词表中最长的前缀）。
     * 未登录字符以单个字符形式查找，若仍不在词表中则使用 UNK。</p>
     *
     * @param text 输入文本
     * @return token id 数组
     */
    public long[] encode(String text) {
        if (text == null || text.isBlank()) {
            return new long[]{(long) unkId};
        }

        // 预处理：大写首字母，末尾补标点
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

        List<Long> ids = new ArrayList<>();
        ids.add((long) bosId);

        int pos = 0;
        while (pos < processed.length()) {
            // 贪心最长匹配：从当前位置尝试匹配最长 token
            boolean matched = false;
            for (int len = Math.min(6, processed.length() - pos); len >= 1; len--) {
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
                // 未登录：单个字符查找
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

    private static long[] toLongArray(List<Long> list) {
        long[] arr = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
