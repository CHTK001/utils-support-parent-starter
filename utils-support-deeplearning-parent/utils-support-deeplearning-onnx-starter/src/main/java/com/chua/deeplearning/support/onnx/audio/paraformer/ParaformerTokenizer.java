package com.chua.deeplearning.support.onnx.audio.paraformer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* Paraformer 词表解析与结果解码（纯 Java）。
* <p>
* 令牌.txt 每行格式：{@code token<空格>id}，共 8359 个 令牌，
* 其中前 4 个为特殊 令牌：0=blank、1=&lt;s&gt;、2=&lt;/s&gt;、3=OOV。
* </p>
* <p>
* 解码逻辑复刻 sherpa-onnx {@code OfflineRecognizerParaformerImpl::Convert}：
* 以 "@@" 结尾的 令牌 为子词（去 "@@" 后与前词合并），
* ascii 与 ascii 之间补空格，ascii 与非 ascii 之间也补空格，
* 连续非 ascii 直接拼接；遇到 EOS（&lt;/s&gt;=2）终止。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class ParaformerTokenizer {

    /** EOS 令牌 名 */
    /** Eos */
    private static final String EOS_TOKEN = "</s>";

    /** BPE 合并标记后缀 */
    /** 合并_后缀 */
    private static final String MERGE_SUFFIX = "@@";

    /** BPE 合并标记后缀长度 */
    /** 合并_后缀_len */
    private static final int MERGE_SUFFIX_LEN = 2;

    /** 令牌 标识 → 令牌 字符串 */
    /** id转为令牌 */
    private final String[] idToToken;

    /** 令牌 字符串 → 令牌 标识 */
    /** 令牌转为id */
    private final Map<String, Integer> tokenToId;

    /** 词表大小 */
    /** Vocab_大小 */
    private final int vocabSize;

    /** EOS 令牌 标识 */
    /** Eos_标识 */
    private final int eosId;

    /**
    * 构造词表。
    *
    * @param idToToken 令牌 标识 → 字符串映射
    */
    private ParaformerTokenizer(String[] idToToken) {
        this.idToToken = idToToken;
        this.vocabSize = idToToken.length;
        this.tokenToId = new HashMap<>(vocabSize);
        for (int i = 0; i < vocabSize; i++) {
            this.tokenToId.put(idToToken[i], i);
        }
        this.eosId = tokenToId.getOrDefault(EOS_TOKEN, 2);
    }

    /**
    * 从 令牌.txt 文件加载词表。
    *
    * @param tokensPath 令牌.txt 路径
    * @return 词表实例
    * @throws IOException 文件读取失败
    */
    public static ParaformerTokenizer load(Path tokensPath) throws IOException {
        try (InputStream in = Files.newInputStream(tokensPath)) {
            return loadFromStream(in);
        }
    }

    /**
    * 从输入流加载词表。
    *
    * @param in 令牌.txt 输入流
    * @return 词表实例
    * @throws IOException 读取失败
    */
    public static ParaformerTokenizer loadFromStream(InputStream in) throws IOException {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        List<String> lines = content.lines().toList();
        Map<Integer, String> idToToken = new HashMap<>();
        int maxId = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int sep = line.lastIndexOf(' ');
            if (sep <= 0) {
                sep = line.lastIndexOf('\t');
            }
            if (sep <= 0) {
                continue;
            }
            String token = line.substring(0, sep);
            String idStr = line.substring(sep + 1).trim();
            try {
                int id = Integer.parseInt(idStr);
                idToToken.put(id, token);
                if (id > maxId) {
                    maxId = id;
                }
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的行
            }
        }
        String[] arr = new String[maxId + 1];
        for (Map.Entry<Integer, String> e : idToToken.entrySet()) {
            arr[e.getKey()] = e.getValue();
        }
        return new ParaformerTokenizer(arr);
    }

    /**
    * 词表大小。
    *
    * @return 词表大小
    */
    public int vocabSize() {
        return vocabSize;
    }

    /**
    * EOS 令牌 标识。
    *
    * @return EOS 标识
    */
    public int eosId() {
        return eosId;
    }

    /**
    * 解码 令牌 标识 序列为文本（sherpa-onnx 转换 逻辑）。
    *
    * @param tokenIds 令牌 标识 序列（不含 EOS）
    * @return 识别文本
    */
    public String decode(List<Integer> tokenIds) {
        StringBuilder text = new StringBuilder();
        boolean mergeable = false;
        for (int i = 0; i < tokenIds.size(); i++) {
            int id = tokenIds.get(i);
            if (id < 0 || id >= vocabSize) {
                continue;
            }
            String sym = idToToken[id];
            if (sym == null || sym.isEmpty()) {
                continue;
            }
            if (sym.endsWith(MERGE_SUFFIX) && sym.length() > MERGE_SUFFIX_LEN) {
                // 子词，去掉 "@@" 后缀
                String base = sym.substring(0, sym.length() - MERGE_SUFFIX_LEN);
                if (mergeable) {
                    text.append(base);
                } else {
                    text.append(' ').append(base);
                    mergeable = true;
                }
            } else {
                // 完整词
                char first = sym.charAt(0);
                if (first < 0x80) {
                    // ascii
                    if (mergeable) {
                        mergeable = false;
                        text.append(sym);
                    } else {
                        text.append(' ').append(sym);
                    }
                } else {
                    // 非 ascii
                    mergeable = false;
                    if (i > 0) {
                        int prevId = tokenIds.get(i - 1);
                        String prev = (prevId >= 0 && prevId < vocabSize) ? idToToken[prevId] : null;
                        if (prev != null && !prev.isEmpty() && prev.charAt(0) < 0x80) {
                            // ascii 与非 ascii 之间补空格
                            text.append(' ');
                        }
                    }
                    text.append(sym);
                }
            }
        }
        return text.toString().trim();
    }
}
