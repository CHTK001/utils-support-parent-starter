package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 横向 ASCII 条形图视图解析器。
 * <p>适合展示比例、对比等数值数据。</p>
 * <pre>{@code
 * 任务A  ████████████████░░░░  80%
 * 任务B  ██████████░░░░░░░░░░  50%
 * 任务C  ████████████████████ 100%
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("barchart")
public class BarChartViewParser implements ViewParser {

    /**
     * 默认条形图宽度（块数）
     */
    private static final int DEFAULT_BAR_WIDTH = 20;

    /**
     * 已填充块字符
     */
    private static final char FILLED_BLOCK = '█';

    /**
     * 未填充块字符
     */
    private static final char EMPTY_BLOCK = '░';

    /**
     * 百分比格式
     */
    private static final String PERCENT_FORMAT = "  %.0f%%";

    /**
     * 全零数据占位文本
     */
    private static final String ALL_ZEROS_PLACEHOLDER = "(all zeros)";

    /**
     * 空数据占位文本
     */
    private static final String EMPTY_PLACEHOLDER = "(empty)";

    /**
     * 判断是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return 非空 {@link Map} 返回 true
     */
    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Map && !((Map<?, ?>) data).isEmpty();
    }

    /**
     * 将 Map 渲染为横向条形图。
     *
     * @param data 待渲染的数据（{@link Map}，值为数值）
     * @return 条形图文本；空数据返回 {@value #EMPTY_PLACEHOLDER}，全零返回 {@value #ALL_ZEROS_PLACEHOLDER}
     */
    @SuppressWarnings("unchecked")
    @Override
    public String render(Object data) {
        Map<Object, Object> map = (Map<Object, Object>) data;
        if (map.isEmpty()) {
            return EMPTY_PLACEHOLDER;
        }

        List<Entry> entries = new ArrayList<>();
        double maxVal = 0;
        int maxNameLen = 0;

        for (var entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            double val = parseDouble(entry.getValue());
            maxVal = Math.max(maxVal, val);
            maxNameLen = Math.max(maxNameLen, name.length());
            entries.add(new Entry(name, val));
        }

        if (maxVal == 0) {
            return ALL_ZEROS_PLACEHOLDER;
        }

        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            // 按最大值归一化计算填充块数
            double pct = e.value / maxVal * 100;
            int blocks = (int) (pct * DEFAULT_BAR_WIDTH / 100);
            sb.append(e.name);
            sb.append(" ".repeat(maxNameLen - e.name.length() + 1));
            sb.append(String.valueOf(FILLED_BLOCK).repeat(Math.max(0, blocks)));
            sb.append(String.valueOf(EMPTY_BLOCK).repeat(Math.max(0, DEFAULT_BAR_WIDTH - blocks)));
            sb.append(String.format(PERCENT_FORMAT, pct));
            sb.append('\n');
        }
        // 移除末尾换行
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 将数值对象解析为 double。
     *
     * @param val 待解析的对象
     * @return 解析后的数值，解析失败返回 0
     */
    private static double parseDouble(Object val) {
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取解析器顺序。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 25;
    }

    /**
     * 条形图条目：名称 + 数值。
     *
     * @param name  条目名称
     * @param value 条目数值
     * @author CH
     * @since 4.0.0.42
     */
    private record Entry(String name, double value) {
    }
}