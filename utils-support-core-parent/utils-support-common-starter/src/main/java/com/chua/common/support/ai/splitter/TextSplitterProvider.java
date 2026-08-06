package com.chua.common.support.ai.splitter;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 文本分割器提供者 SPI。
 *
 * <p>通过 SPI 注册多个切片策略（sentence 等），可按名称创建带参数的
 * {@link TextSplitter} 实例，用于 RAG 文档切分。</p>
 *
 * <pre>{@code
 * // 按名称创建切片器（默认 sentence）：
 * TextSplitter splitter = TextSplitterProvider.create("sentence", 1000, 200);
 *
 * // 列出所有已注册的切片策略：
 * List<String> names = TextSplitterProvider.providers();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TextSplitterProvider {

    /**
     * SPI 名称（如 "sentence"）。
     *
     * @return 提供者名称
     */
    String name();

    /**
     * 创建文本分割器实例。
     *
     * @param chunkSize    切片大小
     * @param chunkOverlap 切片重叠
     * @return TextSplitter 实例
     */
    TextSplitter create(int chunkSize, int chunkOverlap);

    /**
     * 创建指定 SPI 名称的文本分割器。
     *
     * @param providerName SPI 名称（如 "sentence"）
     * @param chunkSize    切片大小
     * @param chunkOverlap 切片重叠
     * @return TextSplitter 实例
     */
    static TextSplitter create(String providerName, int chunkSize, int chunkOverlap) {
        try {
            return ServiceProvider.of(TextSplitterProvider.class)
                    .getExtension(providerName)
                    .create(chunkSize, chunkOverlap);
        } catch (Exception e) {
            throw new RuntimeException(
                    "无法创建文本分割器 [" + providerName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 返回所有已注册的 SPI 名称。
     *
     * @return 提供者名称列表
     */
    static List<String> providers() {
        return ServiceProvider.of(TextSplitterProvider.class)
                .getExtensions()
                .stream()
                .toList();
    }

    /**
     * 默认的句子边界分割器提供者（最低优先级，作为兜底）。
     */
    @Spi(value = "sentence", order = -100)
    class SentenceProvider implements TextSplitterProvider {
        @Override
        public String name() {
            return "sentence";
        }

        @Override
        public TextSplitter create(int chunkSize, int chunkOverlap) {
            return new SentenceTextSplitter(chunkSize, chunkOverlap);
        }
    }
}
