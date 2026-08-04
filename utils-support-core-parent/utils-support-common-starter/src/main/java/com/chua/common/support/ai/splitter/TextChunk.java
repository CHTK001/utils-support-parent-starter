package com.chua.common.support.ai.splitter;

import org.jspecify.annotations.NullUnmarked;

/**
 * 文本分块结果。
 *
 * @param index       分块序号（从 0 开始）
 * @param startOffset 分块在原文中的起始偏移（包含）
 * @param endOffset   分块在原文中的结束偏移（不包含）
 * @param text        分块文本
 * @param type        分块类型
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public record TextChunk(int index, int startOffset, int endOffset, String text, String type) {
}
