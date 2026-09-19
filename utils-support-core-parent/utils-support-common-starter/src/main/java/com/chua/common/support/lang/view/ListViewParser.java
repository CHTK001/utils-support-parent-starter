package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 带编号的列表视图解析器。
 * <pre>{@code
 *  1. foo
 *  2. bar
 *  3. baz
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("list")
public class ListViewParser implements ViewParser {

    /**
     * 空数据占位文本
     */
    private static final String EMPTY_PLACEHOLDER = "(empty)";

    /**
     * 编号后缀分隔符
     */
    private static final String NUMBER_SUFFIX = ". ";

    /**
     * 换行符
     */
    private static final char NEWLINE = '\n';

    /**
     * 判断是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return {@link Iterable} 或数组返回 true，其它返回 false
     */
    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data.getClass().isArray();
    }

    /**
     * 将数据渲染为带编号的列表文本。
     *
     * @param data 待渲染的数据
     * @return 带编号的列表字符串；空数据返回 {@value #EMPTY_PLACEHOLDER}
     */
    @Override
    public String render(Object data) {
        List<Object> items = toList(data);
        if (items.isEmpty()) {
            return EMPTY_PLACEHOLDER;
        }
        int digits = String.valueOf(items.size()).length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            // 编号右对齐，位数不足时前置空格
            String num = String.valueOf(i + 1);
            sb.append(" ".repeat(digits - num.length()));
            sb.append(num).append(NUMBER_SUFFIX).append(items.get(i)).append(NEWLINE);
        }
        // 移除末尾换行
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 将数据统一转为元素列表。
     *
     * @param data 待转换的数据
     * @return 元素列表（标量会被包装为单元素列表）
     */
    private static List<Object> toList(Object data) {
        if (data instanceof Iterable) {
            List<Object> result = new ArrayList<>();
            ((Iterable<?>) data).forEach(result::add);
            return result;
        }
        if (data.getClass().isArray()) {
            return new ArrayList<>(Arrays.asList((Object[]) data));
        }
        return List.of(data);
    }

    /**
     * 获取解析器顺序。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 5;
    }
}
