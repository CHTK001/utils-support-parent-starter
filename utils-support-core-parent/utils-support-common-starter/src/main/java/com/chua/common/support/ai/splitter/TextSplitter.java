package com.chua.common.support.ai.splitter;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * 文本分割器接口。
 * <p>
 * 将长文本按不同策略分割成多个文本块（TextChunk），
 * 用于 RAG、LLM 上下文窗口等场景。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TextSplitter {

    /**
     * 分割文本
     *
     * @param text 待分割的文本
     * @return 分割后的文本块列表
     */
    @Nonnull
    List<TextChunk> split(@Nonnull String text);
}
