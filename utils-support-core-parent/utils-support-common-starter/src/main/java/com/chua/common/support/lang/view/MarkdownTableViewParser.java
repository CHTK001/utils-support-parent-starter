package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Markdown 表格视图解析器，将数据渲染为 Markdown 表格格式。
 *
 * <p>方便复制到文档、Issue、PR 等场景。支持三类数据：</p>
 * <ul>
 *   <li>{@link Map}：渲染为两列表格（Key / Value）</li>
 *   <li>{@link Iterable} / 数组：若元素为简单类型，按行号渲染；否则反射读取字段</li>
 * </ul>
 *
 * <pre>{@code
 * | Name  | Value |
 * |-------|-------|
 * | foo   | 123   |
 * | bar   | 456   |
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("md")
public class MarkdownTableViewParser implements ViewParser {

    /**
     * 单元格左右内边距（空格数）
    */
    private static final int PAD = 1;

    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data instanceof Map || data.getClass().isArray();
    }

    @Override
    public String render(Object data) {
        List<String[]> rows = ViewFormatter.extractRows(data);
        if (rows.isEmpty()) {
            return ViewFormatter.EMPTY_PLACEHOLDER;
        }
        return ViewFormatter.drawMarkdownTable(rows, PAD);
    }

    @Override
    public int getOrder() {
        return 8;
    }
}
