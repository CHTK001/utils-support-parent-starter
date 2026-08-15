package com.chua.common.support.lang.bean;

/**
 * 命名风格枚举，用于 BeanPath 属性匹配时的名称转换。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum NamingStyle {

    /**
     * 原样匹配，不转换
     */
    RAW,
    /**
     * 驼峰：userName
     */
    CAMEL,
    /**
     * 下划线（蛇形）：user_name
     */
    UNDERSCORE,
    /**
     * 连字符（罗马式）：user-name
     */
    ROMAN,
    /**
     * 大写下划线：USER_NAME
     */
    UPPER_UNDERSCORE;

    /**
     * 将属性名按当前风格转换为驼峰格式。
     */
    public String toCamel(String name) {
        return switch (this) {
            case RAW -> name;
            case CAMEL -> name;
            case UNDERSCORE, UPPER_UNDERSCORE -> underscoreToCamel(name);
            case ROMAN -> kebabToCamel(name);
        };
    }

    /**
     * 将驼峰属性名按当前风格转换。
     */
    public String fromCamel(String camel) {
        return switch (this) {
            case RAW -> camel;
            case CAMEL -> camel;
            case UNDERSCORE -> camelToUnderscore(camel);
            case ROMAN -> camelToKebab(camel);
            case UPPER_UNDERSCORE -> camelToUnderscore(camel).toUpperCase();
        };
    }

    private static String underscoreToCamel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String kebabToCamel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String camelToUnderscore(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String camelToKebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}