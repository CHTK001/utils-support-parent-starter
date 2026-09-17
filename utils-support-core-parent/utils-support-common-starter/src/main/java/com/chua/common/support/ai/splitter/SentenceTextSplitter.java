package com.chua.common.support.ai.splitter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
* 基于句子边界的文本分割器。
* <p>
* 按句号（。！？）、换行符等自然句子边界进行分割，
* 每个块的大小受 maxChunkSize 控制，优先在句子边界截断。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class SentenceTextSplitter implements TextSplitter {

    /**
    * 默认最大块大小（字符）
     */
    private static final int DEFAULT_MAX_CHUNK_SIZE = 500;

    /**
    * 句子边界分隔符（按优先级依次匹配）
     */
    private static final List<String> SENTENCE_DELIMITERS = List.of(
            "\n\n",
            "\n",
            "。",
            "！",
            "？",
            ".",
            "!",
            "?",
            "；",
            ";"
    );

    /**
    * 最大块大小
     */
    private final int maxChunkSize;

    /**
     * 分块类型标识：句子边界分块。
     */
    private static final String CHUNK_TYPE_SENTENCE = "sentence";

    /**
     * 块重叠大小
     */
    private final int chunkOverlap;

    /**
     * 使用默认块大小创建实例。
     */
    public SentenceTextSplitter() {
        this(DEFAULT_MAX_CHUNK_SIZE, 0);
    }

    /**
     * 使用指定最大块大小创建实例（无重叠）。
     *
     * @param maxChunkSize 最大块大小（字符），最小为 1，小于 1 时按 1 处理
     */
    public SentenceTextSplitter(int maxChunkSize) {
        this(maxChunkSize, 0);
    }

    /**
     * 使用指定最大块大小与重叠大小创建实例。
     *
     * @param maxChunkSize 最大块大小（字符），最小为 1，小于 1 时按 1 处理
     * @param chunkOverlap 相邻块重叠字符数，最小为 0，负值按 0 处理
     */
    public SentenceTextSplitter(int maxChunkSize, int chunkOverlap) {
        this.maxChunkSize = Math.max(1, maxChunkSize);
        this.chunkOverlap = Math.max(0, chunkOverlap);
    }

    /**
     * 按句子边界切分文本为若干块。
     *
     * @param text 待切分文本，不能为 null；空白文本返回空列表
     * @return 分块列表（不可变），空文本时返回空列表；非空时至少 1 个分块
     */
    @Override
    @Nonnull
    public List<TextChunk> split(@Nonnull String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        if (text.length() <= maxChunkSize) {
            return List.of(new TextChunk(0, 0, text.length(), text.strip(), CHUNK_TYPE_SENTENCE));
        }

        int cursor = 0;
        int len = text.length();
        List<TextChunk> result = new ArrayList<>();
        int index = 0;

        while (cursor < len) {
            int end = Math.min(cursor + maxChunkSize, len);
            int cut = findSentenceBoundary(text, cursor, end);
            // 防死循环：cut 必须严格大于 cursor（句界不得紧贴 cursor，否则 cursor 原地踏步）
            if (cut <= cursor) {
                cut = end;
            }
            String segment = text.substring(cursor, cut).strip();
            if (!segment.isEmpty()) {
                result.add(new TextChunk(index++, cursor, cut, segment, CHUNK_TYPE_SENTENCE));
            }
            int nextCursor = cut - chunkOverlap;
            // 防死循环：overlap 不得使 cursor 回退到不前进的位置
            if (nextCursor <= cursor) {
                nextCursor = cut;
            }
            cursor = nextCursor;
        }

        return Collections.unmodifiableList(result);
    }

    /**
    * 在文本中查找句子边界位置
    *
    * @param text    完整文本
    * @param start   起始位置
    * @param hardEnd 硬上限位置
    * @return 句子边界位置，未找到时返回 hardEnd
     */
    private int findSentenceBoundary(String text, int start, int hardEnd) {
        int best = -1;
        for (String delim : SENTENCE_DELIMITERS) {
            int idx = text.lastIndexOf(delim, hardEnd - 1);
            if (idx >= start) {
                int end = idx + delim.length();
                // 仅当句界严格落在 (start, hardEnd] 区间内才考虑，且需让 cursor 严格前进
                if (end > best && end <= hardEnd && end > start) {
                    best = end;
                }
            }
        }
        return Math.max(best, hardEnd);
    }
}
