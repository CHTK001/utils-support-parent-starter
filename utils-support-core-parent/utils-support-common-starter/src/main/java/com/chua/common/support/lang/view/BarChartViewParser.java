package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** Default_bar_width */
    private static final int DEFAULT_BAR_WIDTH = 20;

    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        if (data instanceof Map) {
            return !((Map<?, ?>) data).isEmpty();
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String render(Object data) {
        Map<Object, Object> map = (Map<Object, Object>) data;
        if (map.isEmpty()) {
            return "(empty)";
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
            return "(all zeros)";
        }

        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            double pct = e.value / maxVal * 100;
            int blocks = (int) (pct * DEFAULT_BAR_WIDTH / 100);
            sb.append(e.name);
            sb.append(" ".repeat(maxNameLen - e.name.length() + 1));
            sb.append("█".repeat(Math.max(0, blocks)));
            sb.append("░".repeat(Math.max(0, DEFAULT_BAR_WIDTH - blocks)));
            sb.append(String.format("  %.0f%%", pct));
            sb.append('\n');
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

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

    static class Entry {
        final String name;
        final double value;

        Entry(String name, double value) {
            this.name = name;
            this.value = value;
        }
    }

    @Override
    public int getOrder() {
        return 25;
    }
}
