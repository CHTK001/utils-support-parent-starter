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

    private static final int DEFAULT_MAX_CHUNK_SIZE = 500;

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
     * 块重叠大小
     */
    private final int chunkOverlap;

    public SentenceTextSplitter() {
        this(DEFAULT_MAX_CHUNK_SIZE, 0);
    }

    public SentenceTextSplitter(int maxChunkSize) {
        this(maxChunkSize, 0);
    }

    public SentenceTextSplitter(int maxChunkSize, int chunkOverlap) {
        this.maxChunkSize = Math.max(1, maxChunkSize);
        this.chunkOverlap = Math.max(0, chunkOverlap);
    }

    @Override
    @Nonnull
    public List<TextChunk> split(@Nonnull String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        if (text.length() <= maxChunkSize) {
            return List.of(new TextChunk(0, 0, text.length(), text.strip(), "sentence"));
        }

        int cursor = 0;
        int len = text.length();
        List<TextChunk> result = new ArrayList<>();
        int index = 0;

        while (cursor < len) {
            int end = Math.min(cursor + maxChunkSize, len);
            int cut = findSentenceBoundary(text, cursor, end);
            if (cut <= cursor) {
                cut = end;
            }
            String segment = text.substring(cursor, cut).strip();
            if (!segment.isEmpty()) {
                result.add(new TextChunk(index++, cursor, cut, segment, "sentence"));
            }
            cursor = cut - chunkOverlap;
            if (cursor < 0) {
                cursor = cut;
            }
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
                if (end > best && end <= hardEnd) {
                    best = end;
                }
            }
        }
        return Math.max(best, hardEnd);
    }
}
