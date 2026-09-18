package com.chua.oshi.support.cli.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
* 终端面板/表格渲染工具，输出风格与 ocgc 一致。
*
* @author CH
* @since 4.0.0.42
 */
public final class Formatter {

    /**
    * Formatter。
    */
    private Formatter() {
    }

    /**
    * 格式化字节数（B/KB/MB/GB）。
    *
    * @param n 字节数
    * @return 格式化字符串
    */
    public static String formatBytes(long n) {
        if (n >= 1_073_741_824L) {
            return String.format("%.1f GB", n / 1_073_741_824.0);
        }
        if (n >= 1_048_576L) {
            return String.format("%.1f MB", n / 1_048_576.0);
        }
        if (n >= 1024L) {
            return String.format("%.1f KB", n / 1024.0);
        }
        return n + " B";
    }

    /**
    * 渲染键值面板（顶部带标题）。
    *
    * @param title 面板标题
    * @param rows  交替的 键,值
    * @return 面板字符串
    */
    public static String panel(String title, String... rows) {
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i + 1 < rows.length; i += 2) {
            keys.add(rows[i]);
            values.add(rows[i + 1]);
        }
        int keyW = keys.stream().mapToInt(Formatter::width).max().orElse(0);
        int valW = values.stream().mapToInt(Formatter::width).max().orElse(0);
        int inner = keyW + 3 + valW;
        int topLen = Math.max(inner, width(title) + 2);

        StringBuilder sb = new StringBuilder();
        sb.append("╭").append(repeat("─", topLen)).append(' ').append(title).append(' ')
          .append(repeat("─", Math.max(0, topLen - width(title) - 1))).append("╮").append('\n');
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String v = values.get(i);
            sb.append("│ ").append(k).append(repeat(" ", keyW - width(k))).append("   ")
              .append(v).append(repeat(" ", topLen - (width(k) + 3 + width(v)))).append(" │").append('\n');
        }
        sb.append("╰").append(repeat("─", topLen + width(title) + 1)).append("╯");
        return sb.toString();
    }

    /**
    * 渲染表格。
    *
    * @param title   表格标题（空串则不显示标题栏）
    * @param headers 表头
    * @param rows    数据行（每行长度与表头一致）
    * @return 表格字符串
    */
    public static String table(String title, String[] headers, List<String[]> rows) {
        return table(title, headers, rows, null);
    }

    /**
    * 渲染表格（可带汇总行）。
    *
    * @param title   表格标题（空串则不显示标题栏）
    * @param headers 表头
    * @param rows    数据行
    * @param summary 汇总行（可空）
    * @return 表格字符串
    */
    public static String table(String title, String[] headers, List<String[]> rows, String[] summary) {
        int cols = headers.length;
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = Math.max(widths[c], width(headers[c]));
        }
        List<String[]> all = new ArrayList<>(rows);
        if (summary != null) {
            all.add(summary);
        }
        for (String[] r : all) {
            for (int c = 0; c < cols; c++) {
                widths[c] = Math.max(widths[c], width(r[c] == null ? "" : r[c]));
            }
        }

        StringBuilder sb = new StringBuilder();
        int total = Arrays.stream(widths).sum() + cols * 3 + 1;
        if (title != null && !title.isEmpty()) {
            int left = Math.max(1, (total - width(title) - 2) / 2);
            int right = Math.max(1, total - width(title) - 2 - left);
            sb.append("┏").append(repeat("━", left)).append(' ').append(title).append(' ')
              .append(repeat("━", right)).append("┓").append('\n');
        } else {
            sb.append("┏").append(repeat("━", total)).append("┓").append('\n');
        }

        header(sb, headers, widths);
        sep(sb, widths, "┡", "┇", "┩");

        for (int i = 0; i < rows.size(); i++) {
            row(sb, rows.get(i), widths);
            if (summary != null && i == rows.size() - 1) {
                sep(sb, widths, "├", "┼", "┤");
            }
        }
        if (summary != null) {
            row(sb, summary, widths);
        }
        sep(sb, widths, "└", "┴", "┘");
        return sb.toString();
    }

    /**
    * 构建百分比进度条。
    * 实心块填充已用比例，空心块填充剩余部分，结果总长度固定为 length。
    *
    * @param pct    百分比，取值 0-100
    * @param length 进度条总长度
    * @return 进度条字符串
    */
    public static String bar(double pct, int length) {
        int filled = (int) Math.round(pct / 100.0 * length);
        filled = Math.max(0, Math.min(length, filled));
        return repeat("█", filled) + repeat("░", length - filled);
    }

    private static void header(StringBuilder sb, String[] headers, int[] widths) {
        sb.append("┃");
        for (int c = 0; c < headers.length; c++) {
            String h = headers[c];
            sb.append(' ').append(h).append(repeat(" ", widths[c] - width(h))).append("  ┃");
        }
        sb.append('\n');
    }

    private static void row(StringBuilder sb, String[] cells, int[] widths) {
        sb.append("│");
        for (int c = 0; c < cells.length; c++) {
            /**
            * sep。
            * @param sb sb
            * @param widths widths
            * @param l l
            * @param m m
            * @param r r
            */
            String cell = cells[c] == null ? "" : cells[c];
            sb.append(' ').append(cell).append(repeat(" ", widths[c] - width(cell))).append("  │");
        }
        sb.append('\n');
    }

    private static void sep(StringBuilder sb, int[] widths, String l, String m, String r) {
        sb.append(l);
        for (int c = 0; c < widths.length; c++) {
            sb.append(repeat("━", widths[c] + 3));
            if (c < widths.length - 1) {
                /**
                * width。
                * @param s s
                * @return width的结果
                * @param n n
                */
                sb.append(m);
            }
        }
        sb.append(r).append('\n');
    }

    private static int width(String s) {
        if (s == null) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += cp > 0x2E80 ? 2 : 1;
            i += Character.charCount(cp);
        }
        return w;
    }

    private static String repeat(String s, int n) {
        if (n <= 0) {
            return "";
        }
        return s.repeat(n);
    }
}
