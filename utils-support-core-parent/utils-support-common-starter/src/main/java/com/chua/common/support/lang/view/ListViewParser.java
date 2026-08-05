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

    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data.getClass().isArray();
    }

    @Override
    public String render(Object data) {
        List<Object> items = toList(data);
        if (items.isEmpty()) {
            return "(empty)";
        }
        int digits = String.valueOf(items.size()).length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String num = String.valueOf(i + 1);
            sb.append(" ".repeat(digits - num.length()));
            sb.append(num).append(". ").append(items.get(i)).append('\n');
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static List<Object> toList(Object data) {
        if (data instanceof Iterable) {
            List<Object> r = new ArrayList<>();
            ((Iterable<?>) data).forEach(r::add);
            return r;
        }
        if (data.getClass().isArray()) {
            return new ArrayList<>(Arrays.asList((Object[]) data));
        }
        return List.of(data);
    }

    @Override
    public int getOrder() {
        return 5;
    }
}
