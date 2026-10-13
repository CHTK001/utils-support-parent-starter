package com.chua.common.support.export.meta;

/**
 * 水平对齐方式。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum HorizontalAlign {

    /**
     * 未声明，由实现方按 {@link ColumnType} 推断（数值右对齐，其余左对齐）。
     */
    AUTO,
    /**
     * 左对齐。
     */
    LEFT,
    /**
     * 居中。
     */
    CENTER,
    /**
     * 右对齐。
     */
    RIGHT;

    /**
     * 按名称解析，忽略大小写；解析失败返回 {@link #AUTO}。
     *
     * @param name 对齐名，可为 {@code null}
     * @return 解析结果
     */
    public static HorizontalAlign of(String name) {
        if (name == null || name.isBlank()) {
            return AUTO;
        }
        String key = name.trim().replace("-", "").replace("_", "");
        for (HorizontalAlign align : values()) {
            if (align.name().equalsIgnoreCase(key)) {
                return align;
            }
        }
        return AUTO;
    }
}