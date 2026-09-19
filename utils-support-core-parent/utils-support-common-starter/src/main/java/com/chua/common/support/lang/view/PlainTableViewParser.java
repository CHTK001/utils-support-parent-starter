package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * 纯文本表格渲染器，无边框、无 Unicode 字符，适合窄屏终端或纯文本输出场景。
 *
 * <p>输出格式示例：</p>
 * <pre>
 * Name    Value
 * foo     123
 * bar     456
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("plain")
public class PlainTableViewParser implements ViewParser {

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
        return ViewFormatter.drawBorderlessTable(rows, PAD);
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
