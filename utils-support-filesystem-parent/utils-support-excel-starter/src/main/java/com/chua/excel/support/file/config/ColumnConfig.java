package com.chua.excel.support.file.config;

/**
* Excel 列配置 — 流式构建器。
*
* <p>用于在 {@code ExcelWriteBuilder} 中定义每列的显示名、宽度、样式、数据类型等。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* ColumnConfig.create()
*     .displayName("用户ID")
*     .width(15)
*     .style(headerStyle);
* }</pre>yle);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public class ColumnConfig {

    /** 字段名（数据 映射 中的 键） */
    private final String fieldName;

    /** 列显示名（默认为字段名） */
    private String displayName;

    /** 列宽（字符数，默认自动） */
    private Integer width;

    /** 列样式 */
    private CellStyleConfig style;

    /** 自定义表头样式（覆盖列样式中的表头设置） */
    private CellStyleConfig headerStyle;

    /** 是否隐藏该列 */
    private boolean hidden;

    /**
    * 使用字段名创建列配置。
    *
    * @param fieldName 数据 映射 中的 键
    */
    private ColumnConfig(String fieldName) {
        this.fieldName = fieldName;
    }

    /**
    * 创建列配置。
    *
    * @param fieldName 字段名
    * @return ColumnConfig 实例
    */
    public static ColumnConfig of(String fieldName) {
        return new ColumnConfig(fieldName);
    }

    // ==================== 链式配置 ====================

    /**
    * 设置列显示名（表头显示的文本）。
    *
    * @param displayName 显示名
    * @return 当前构建器
    */
    public ColumnConfig displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    /**
    * 设置列宽（字符数）。
    *
    * @param width 列宽
    * @return 当前构建器
    */
    public ColumnConfig width(int width) {
        this.width = width;
        return this;
    }

    /**
    * 设置列样式。
    *
    * @param style 单元格样式
    * @return 当前构建器
    */
    public ColumnConfig style(CellStyleConfig style) {
        this.style = style;
        return this;
    }

    /**
    * 设置列的表头样式（覆盖默认表头样式）。
    *
    * @param headerStyle 表头样式
    * @return 当前构建器
    */
    public ColumnConfig headerStyle(CellStyleConfig headerStyle) {
        this.headerStyle = headerStyle;
        return this;
    }

    /**
    * 隐藏该列。
    *
    * @return 当前构建器
    */
    public ColumnConfig hidden() {
        this.hidden = true;
        return this;
    }

    // ==================== Getter ====================

    /**
    * 获取字段名称
    *
    * @return 获取字段名称的结果
    */
    public String getFieldName() {
        return fieldName;
    }

    /**
    * 获取display名称
    *
    * @return 获取display名称的结果
    */
    public String getDisplayName() {
        return displayName != null ? displayName : fieldName;
    }

    /**
    * 获取Width
    *
    * @return 获取width的结果
    */
    public Integer getWidth() {
        return width;
    }

    /**
    * 获取Style
    *
    * @return 获取style的结果
    */
    public CellStyleConfig getStyle() {
        return style;
    }

    /**
    * 获取头部style
    *
    * @return 获取头部style的结果
    */
    public CellStyleConfig getHeaderStyle() {
        return headerStyle;
    }

    /**
    * 是否Hidden
    *
    * @return 是否hidden的结果
    */
    public boolean isHidden() {
        return hidden;
    }
}
