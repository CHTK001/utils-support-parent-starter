package com.chua.common.support.lang.format;

import lombok.Getter;

import java.util.*;
import org.jspecify.annotations.NullUnmarked;

/**
 * SQL高亮配置类，用于定义关键词的颜色和样式。
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public class HighlightConfig {

    /**
     * 高亮类型枚举
     */
    public enum HighlightType {
        /**
         * 控制台ANSI颜色
         */
        ANSI,
        /**
         * HTML标签
         */
        HTML,
        /**
         * 无高亮（纯文本）
         */
        NONE
    }

    /**
     * 关键词类别枚举
     */
    public enum KeywordCategory {
        /**
         * DDL关键词（CREATE, ALTER, DROP等）
         */
        DDL,
        /**
         * DML关键词（SELECT, INSERT, UPDATE, DELETE等）
         */
        DML,
        /**
         * 查询子句（FROM, WHERE, JOIN等）
         */
        CLAUSE,
        /**
         * 函数（SUM, COUNT, MAX等）
         */
        FUNCTION,
        /**
         * 数据类型（INT, VARCHAR, DATE等）
         */
        DATA_TYPE,
        /**
         * 逻辑运算符（AND, OR, NOT等）
         */
        OPERATOR,
        /**
         * 字符串/数字
         */
        LITERAL,
        /**
         * 注释
         */
        COMMENT
    }

    @Getter
    /**
     * 类型
     */
    private HighlightType type = HighlightType.ANSI;

    /**
     * 关键词颜色映射（ANSI）
     */
    private Map<KeywordCategory, String> ansiColors = new HashMap<>();

    /**
     * 关键词颜色映射（HTML）
     */
    private Map<KeywordCategory, String> htmlColors = new HashMap<>();

    /**
     * 是否启用高亮
     */
    @Getter
    /**
     * 是否启用
     */
    private boolean enabled = true;

    // ==================== 默认ANSI颜色配置 ====================

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_WHITE = "\u001B[37m";
    private static final String ANSI_GRAY = "\u001B[90m";

    // ==================== 默认HTML颜色配置 ====================

    private static final String HTML_RED = "<span style=\"color:#c7254e;\">";
    private static final String HTML_GREEN = "<span style=\"color:#42b983;\">";
    private static final String HTML_BLUE = "<span style=\"color:#2c8cff;\">";
    private static final String HTML_ORANGE = "<span style=\"color:#f0a030;\">";
    private static final String HTML_PURPLE = "<span style=\"color:#a855f7;\">";
    private static final String HTML_GRAY = "<span style=\"color:#88909e;\">";
    private static final String HTML_CYAN = "<span style=\"color:#1abc9c;\">";
    private static final String HTML_END = "</span>";

    /**
     * 默认构造函数
     */
    public HighlightConfig() {
        // 初始化ANSI颜色
        ansiColors.put(KeywordCategory.DDL, ANSI_BOLD + ANSI_BLUE);
        ansiColors.put(KeywordCategory.DML, ANSI_BOLD + ANSI_GREEN);
        ansiColors.put(KeywordCategory.CLAUSE, ANSI_BOLD + ANSI_CYAN);
        ansiColors.put(KeywordCategory.FUNCTION, ANSI_MAGENTA);
        ansiColors.put(KeywordCategory.DATA_TYPE, ANSI_YELLOW);
        ansiColors.put(KeywordCategory.OPERATOR, ANSI_RED);
        ansiColors.put(KeywordCategory.LITERAL, ANSI_WHITE);
        ansiColors.put(KeywordCategory.COMMENT, ANSI_GRAY);

        // 初始化HTML颜色
        htmlColors.put(KeywordCategory.DDL, HTML_BLUE);
        htmlColors.put(KeywordCategory.DML, HTML_GREEN);
        htmlColors.put(KeywordCategory.CLAUSE, HTML_CYAN);
        htmlColors.put(KeywordCategory.FUNCTION, HTML_PURPLE);
        htmlColors.put(KeywordCategory.DATA_TYPE, HTML_ORANGE);
        htmlColors.put(KeywordCategory.OPERATOR, HTML_RED);
        htmlColors.put(KeywordCategory.LITERAL, HTML_CYAN);
        htmlColors.put(KeywordCategory.COMMENT, HTML_GRAY);
    }

    // ==================== Getter/Setter ====================

    public HighlightConfig setType(HighlightType type) {
        this.type = type;
        return this;
    }

    public HighlightConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * 获取关键词的ANSI颜色
     */
    public String getAnsiColor(KeywordCategory category) {
        return ansiColors.getOrDefault(category, ANSI_RESET);
    }

    /**
     * 获取关键词的HTML颜色
     */
    public String getHtmlColor(KeywordCategory category) {
        return htmlColors.getOrDefault(category, HTML_END);
    }

    /**
     * 自定义ANSI颜色
     */
    public HighlightConfig setAnsiColor(KeywordCategory category, String color) {
        ansiColors.put(category, color);
        return this;
    }

    /**
     * 自定义HTML颜色
     */
    public HighlightConfig setHtmlColor(KeywordCategory category, String color) {
        htmlColors.put(category, color);
        return this;
    }

    /**
     * 获取ANSI重置码
     */
    public String getAnsiReset() {
        return ANSI_RESET;
    }

    /**
     * 获取HTML结束标签
     */
    public String getHtmlEnd() {
        return HTML_END;
    }
}