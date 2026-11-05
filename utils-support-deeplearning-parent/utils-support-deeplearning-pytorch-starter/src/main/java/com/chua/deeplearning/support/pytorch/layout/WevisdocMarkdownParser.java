// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.layout;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WeVisDoc Markdown 输出解析器。
 * <p>
 * WeVisDoc 模型输出结构化 Markdown（含 HTML 表格、LaTeX 公式），
 * 本类将 Markdown 元素映射到 11 类版面标签。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WevisdocMarkdownParser {

    /**
     * 11 类版面标签。
     */
    public static final String[] LAYOUT_LABELS = {
            "text",
            "paragraph_title",
            "table",
            "figure",
            "figure_caption",
            "table_caption",
            "list",
            "header",
            "footer",
            "reference",
            "equation"
    };

    /**
     * 标题正则：# ## ### #### #####。
     */
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /**
     * 表格行正则。
     */
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("^\\s*\\|.*\\|\\s*$", Pattern.MULTILINE);

    /**
     * 表格分隔行正则。
     */
    private static final Pattern TABLE_SEP_PATTERN = Pattern.compile("^\\s*\\|[\\s:|-]+\\|\\s*$", Pattern.MULTILINE);

    /**
     * 列表项正则：- * + 1. 1)。
     */
    private static final Pattern LIST_PATTERN = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(.+)$", Pattern.MULTILINE);

    /**
     * 图片正则：![alt](url)。
     */
    private static final Pattern FIGURE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]+\\)");

    /**
     * 公式正则：$...$ 或 $$...$$。
     */
    private static final Pattern EQUATION_PATTERN = Pattern.compile("\\$+[^$]+\\$+");

    /**
     * HTML 表格正则。
     */
    private static final Pattern HTML_TABLE_PATTERN = Pattern.compile("<table[^>]*>.*?</table>", Pattern.DOTALL);

    /**
     * HTML 图注正则。
     */
    private static final Pattern HTML_FIGURE_CAPTION_PATTERN = Pattern.compile("<figcaption[^>]*>.*?</figcaption>",
            Pattern.DOTALL);

    /**
     * HTML 表注正则。
     */
    private static final Pattern HTML_TABLE_CAPTION_PATTERN = Pattern.compile("<caption[^>]*>.*?</caption>",
            Pattern.DOTALL);

    /**
     * 解析 Markdown 文本，统计各类版面元素数量。
     *
     * @param markdown Markdown 文本
     * @return 各类版面元素数量（按 LAYOUT_LABELS 顺序）
     */
    public static int[] parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new int[LAYOUT_LABELS.length];
        }

        int[] counts = new int[LAYOUT_LABELS.length];

        // 统计标题（paragraph_title）
        Matcher headerMatcher = HEADER_PATTERN.matcher(markdown);
        while (headerMatcher.find()) {
            counts[1]++; // paragraph_title
        }

        // 统计表格（Markdown 表格 + HTML 表格）
        Matcher tableMatcher = TABLE_ROW_PATTERN.matcher(markdown);
        Matcher htmlTableMatcher = HTML_TABLE_PATTERN.matcher(markdown);
        int tableCount = 0;
        boolean hasHtmlTable = false;
        while (tableMatcher.find()) {
            tableCount++;
        }
        while (htmlTableMatcher.find()) {
            tableCount++;
            hasHtmlTable = true;
        }
        // 表格行数 > 1 且有分隔行才算 Markdown 表格
        if (tableCount > 2 && TABLE_SEP_PATTERN.matcher(markdown).find()) {
            counts[2]++; // table
        } else if (hasHtmlTable) {
            counts[2]++; // table
        }

        // 统计图片（figure）
        Matcher figureMatcher = FIGURE_PATTERN.matcher(markdown);
        while (figureMatcher.find()) {
            counts[3]++; // figure
        }

        // 统计列表（list）
        Matcher listMatcher = LIST_PATTERN.matcher(markdown);
        while (listMatcher.find()) {
            counts[6]++; // list
        }

        // 统计公式（equation）
        Matcher eqMatcher = EQUATION_PATTERN.matcher(markdown);
        while (eqMatcher.find()) {
            counts[10]++; // equation
        }

        // 统计 HTML 图注（figure_caption）
        Matcher figCapMatcher = HTML_FIGURE_CAPTION_PATTERN.matcher(markdown);
        while (figCapMatcher.find()) {
            counts[4]++; // figure_caption
        }

        // 统计 HTML 表注（table_caption）
        Matcher tableCapMatcher = HTML_TABLE_CAPTION_PATTERN.matcher(markdown);
        while (tableCapMatcher.find()) {
            counts[5]++; // table_caption
        }

        // 剩余文本行算作 text
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean isHeader = HEADER_PATTERN.matcher(line).find();
            boolean isTable = TABLE_ROW_PATTERN.matcher(line).find();
            boolean isList = LIST_PATTERN.matcher(line).find();
            if (!isHeader && !isTable && !isList) {
                counts[0]++; // text
            }
        }

        log.debug("WeVisDoc Markdown 解析结果: {}", counts);
        return counts;
    }

    /**
     * 将 Markdown 元素映射到版面标签。
     *
     * @param elementType 元素类型（header/table/list/figure/equation/text）
     * @return 版面标签
     */
    public static String mapToLabel(String elementType) {
        switch (elementType) {
            case "header":
                return "paragraph_title";
            case "table":
                return "table";
            case "list":
                return "list";
            case "figure":
                return "figure";
            case "equation":
                return "equation";
            case "text":
                return "text";
            default:
                return "text";
        }
    }
}