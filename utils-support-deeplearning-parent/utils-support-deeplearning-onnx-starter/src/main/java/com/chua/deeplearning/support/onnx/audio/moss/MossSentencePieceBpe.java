package com.chua.deeplearning.support.onnx.audio.moss;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
   * sentencepiece BPE 分词器的纯 Java 最小实现。
 *
 * <p>针对 MOSS-TTS-Nano 的 {@code tokenizer.model}（ModelProto 格式）设计，
 * 算法与 sentencepiece 官方 {@code bpe_model.cc} 一致：
 * <ol>
 *   <li>空白替换为 ▁ 并添加 dummy 前缀</li>
 *   <li>用户自定义 token（如 &lt;|audio_start|&gt;）优先按字面切分</li>
 *   <li>词内 BPE 合并：每轮选取合并后得分最高的相邻符号对</li>
 *   <li>未知字符回退为 UTF-8 字节 token（&lt;0xXX&gt;）</li>
 * </ol>
 *
 * @author chua
 * @since 4.0.0.42
 */
public class MossSentencePieceBpe implements AutoCloseable {

    private static final String SPACE_MARK = "\u2581"; // SPACE_MARK

    /** piece 类型常量（对应 protobuf enum）。 */
    private static final int TYPE_NORMAL = 1;
    private static final int TYPE_UNKNOWN = 2; // 类型unknown
    private static final int TYPE_CONTROL = 3; // 类型control
    private static final int TYPE_USER_DEFINED = 4; // 类型用户defined

    private final List<String> idToPiece = new ArrayList<>(); // 标识转为piece
    private final Map<String, Integer> pieceToId = new HashMap<>(); // piece转为标识
    private final Map<String, Float> pieceScore = new HashMap<>(); // piecescore
    private final List<Integer> userDefinedIds = new ArrayList<>(); // 用户defined标识

    /**
      * 从 .模型 文件加载词表。
     *
     * @param modelPath sentencepiece 模型路径
     * @throws IOException 解析失败
     */
    public void load(Path modelPath) throws IOException {
        try (InputStream in = Files.newInputStream(modelPath)) {
            load(in.readAllBytes());
        }
    }

    /**
     * 从字节数组加载词表。
     *
     * @param proto 模型Proto.io 原始字节
     */
    public void load(byte[] proto) {
        int offset = 0;
        while (offset < proto.length) {
            int[] tag = readVarint(proto, offset);
            long fieldTag = tag[0];
            offset = tag[1];
            int fieldNumber = (int) (fieldTag >>> 3);
            int wireType = (int) (fieldTag & 0x7);

            if (fieldNumber == 1 && wireType == 2) {
                int[] lenInfo = readVarint(proto, offset);
                int len = lenInfo[0];
                offset = lenInfo[1];
                parsePiece(proto, offset, len);
                offset += len;
            } else if (wireType == 2) {
                int[] lenInfo = readVarint(proto, offset);
                int len = lenInfo[0];
                offset = lenInfo[1] + len;
            } else if (wireType == 0) {
                int[] v = readVarint(proto, offset);
                offset = v[1];
            } else if (wireType == 5) {
                offset += 4;
            } else if (wireType == 1) {
                offset += 8;
            } else {
                throw new IllegalStateException("不支持的 protobuf wire type: " + wireType);
            }
        }
    }

    /**
      * 编码文本为 令牌 标识 序列。
     *
     * @param text 输入文本
     * @return token 标识 数组
     */
    public int[] encode(String text) {
        List<String> segments = splitUserDefined(text);
        List<Integer> ids = new ArrayList<>();
        boolean firstNormal = true;
        for (String segment : segments) {
            if (isUserDefined(segment)) {
                ids.add(pieceToId.get(segment));
                continue;
            }
            String normalized = java.text.Normalizer.normalize(
                    segment, java.text.Normalizer.Form.NFKC);
            normalized = normalized.replace(" ", SPACE_MARK)
                    .replace("\n", SPACE_MARK);
            if (firstNormal && !normalized.startsWith(SPACE_MARK)) {
                normalized = SPACE_MARK + normalized;
            }
            firstNormal = false;
            encodeNormalized(normalized, ids);
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ids.get(i);
        }
        return result;
    }

    /**
     * 查询词表大小。
     *
     * @return 词表大小
     * @param data 数据
     /**
      * 大小。
      * @return 大小的结果
      */
     * @param offset 偏移量
     * @param text 文本
     * @param symbol symbol
     * @param out 出
     /**
       * encodenormalized。
      * @param normalized normalized
      * @param out 出
      */
      * @return 大小的结果
     */
    public int size() {
        return idToPiece.size();
    }

    private void encodeNormalized(String normalized, List<Integer> out) {
        int pos = 0;
        int len = normalized.length();
        while (pos < len) {
            int nextSpace = normalized.indexOf(SPACE_MARK, pos + 1);
            String word;
            if (nextSpace < 0) {
                word = normalized.substring(pos);
                pos = len;
            } else {
                word = normalized.substring(pos, nextSpace);
                pos = nextSpace;
            }
            if (!word.isEmpty()) {
                /**
                  * encodeword。
                 * @param word word
                 * @param out 出
                 * @param text 文本
                 * @return 分割用户defined的结果
                 * @param symbol symbol
                 */
                encodeWord(word, out);
            }
        }
    }

    private void encodeWord(String word, List<Integer> out) {
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < word.length(); ) {
            int codePoint = word.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            symbols.add(ch);
            i += Character.charCount(codePoint);
        }

        while (symbols.size() > 1) {
            float bestScore = Float.NEGATIVE_INFINITY;
            int bestIndex = -1;
            String bestMerged = null;
            for (int i = 0; i < symbols.size() - 1; i++) {
                String merged = symbols.get(i) + symbols.get(i + 1);
                if (pieceToId.containsKey(merged)) {
                    float score = pieceScore.getOrDefault(merged, Float.NEGATIVE_INFINITY);
                    if (score > bestScore) {
                        bestScore = score;
                        bestIndex = i;
                        bestMerged = merged;
                    }
                }
            }
            if (bestIndex < 0) {
                break;
            }
            symbols.set(bestIndex, bestMerged);
            symbols.remove(bestIndex + 1);
        }

        for (String symbol : symbols) {
            Integer id = pieceToId.get(symbol);
            if (id != null) {
                out.add(id);
            } else {
                appendByteFallback(symbol, out);
            }
        }
    }

    private void appendByteFallback(String symbol, List<Integer> out) {
        byte[] bytes = symbol.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            String hex = String.format("<0x%02X>", b);
            Integer id = pieceToId.get(hex);
            if (id != null) {
                out.add(id);
            }
        }
    }

    private List<String> splitUserDefined(String text) {
        List<String> segments = new ArrayList<>();
        if (userDefinedIds.isEmpty()) {
            segments.add(text);
            return segments;
        }
        int start = 0;
        while (start < text.length()) {
            int nearest = -1;
            int nearestEnd = -1;
            for (int id : userDefinedIds) {
                String piece = idToPiece.get(id);
                int idx = text.indexOf(piece, start);
                if (idx >= 0 && (nearest < 0 || idx < nearest)) {
                    nearest = idx;
                    nearestEnd = idx + piece.length();
                }
            }
            if (nearest < 0) {
                segments.add(text.substring(start));
                break;
            }
            if (nearest > start) {
                segments.add(text.substring(start, nearest));
            }
            segments.add(text.substring(nearest, nearestEnd));
            start = nearestEnd;
        }
        return segments;
    /**
     * 是否用户defined。
     * @param segment segment
     * @return 是否用户defined的结果
     */
    }

    private boolean isUserDefined(String segment) {
        Integer id = pieceToId.get(segment);
        return id != null && userDefinedIds.contains(id);
    /**
     * 解析piece。
     * @param data 数据
     * @param offset 偏移量
     * @param length 长度
     * @return 读取intle的结果
     */
    }

    private void parsePiece(byte[] data, int offset, int length) {
        int end = offset + length;
        String piece = null;
        float score = 0f;
        int type = TYPE_NORMAL;
        int pos = offset;
        while (pos < end) {
            int[] tag = readVarint(data, pos);
            long fieldTag = tag[0];
            pos = tag[1];
            int fieldNumber = (int) (fieldTag >>> 3);
            int wireType = (int) (fieldTag & 0x7);
            if (fieldNumber == 1 && wireType == 2) {
                int[] lenInfo = readVarint(data, pos);
                int len = lenInfo[0];
                pos = lenInfo[1];
                piece = new String(data, pos, len, StandardCharsets.UTF_8);
                pos += len;
            } else if (fieldNumber == 2 && wireType == 5) {
                score = Float.intBitsToFloat(readIntLE(data, pos));
                pos += 4;
            } else if (fieldNumber == 3 && wireType == 0) {
                int[] v = readVarint(data, pos);
                type = v[0];
                pos = v[1];
            } else if (wireType == 2) {
                int[] lenInfo = readVarint(data, pos);
                pos = lenInfo[1] + lenInfo[0];
            } else if (wireType == 0) {
                int[] v = readVarint(data, pos);
                pos = v[1];
            } else if (wireType == 5) {
                pos += 4;
            } else if (wireType == 1) {
                pos += 8;
            }
        }
        if (piece == null) {
            return;
        }
        int id = idToPiece.size();
        idToPiece.add(piece);
        pieceToId.put(piece, id);
        pieceScore.put(piece, score);
        if (type == TYPE_USER_DEFINED) {
            userDefinedIds.add(id);
        }
    }

    private int[] readVarint(byte[] data, int offset) {
        long value = 0;
        int shift = 0;
        int pos = offset;
        while (pos < data.length) {
            byte b = data[pos++];
            value |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return new int[]{(int) value, pos};
    }

    private int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    @Override
    public void close() {
        idToPiece.clear();
        pieceToId.clear();
        pieceScore.clear();
        userDefinedIds.clear();
    }
}
