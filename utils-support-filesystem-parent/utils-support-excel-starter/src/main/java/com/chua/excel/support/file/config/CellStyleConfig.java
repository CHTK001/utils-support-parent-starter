package com.chua.excel.support.file.config;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;

/**
 * Excel 单元格样式配置 — 流式构建器。
 *
 * <p>用于在 {@code ExcelWriteBuilder} 中定义单元格的字体、颜色、边框、对齐等样式。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * CellStyleConfig headerStyle = CellStyleConfig.create()
 *     .bold().fontSize(12).fontColor("FFFFFF")
 *     .backgroundColor("4472C4")
 *     .border(BorderStyle.THIN)
 *     .horizontalCenter().verticalCenter();
 *
 * CellStyleConfig dateStyle = CellStyleConfig.create()
 *     .dataFormat("yyyy-MM-dd")
 *     .horizontalCenter();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CellStyleConfig {

    // ==================== 字体属性 ====================
    /** Font名称 */
    private String fontName;
    /** Font尺寸 */
    private Short fontSize;
    /** Bold */
    private Boolean bold;
    /** Italic */
    private Boolean italic;
    /** Strikeout */
    private Boolean strikeout;
    /** Underline */
    private Byte underline;
    /** 六位 RGB（如 "4472C4"）或 IndexedColors 名称 */
    private String fontColor;

    // ==================== 背景填充 ====================
    /** Background颜色 */
    private String backgroundColor;
    /** Fill模式 */
    private FillPatternType fillPattern = FillPatternType.SOLID_FOREGROUND;

    // ==================== 边框 ====================
    /** Border顶部 */
    private BorderStyle borderTop;
    /** Border底部 */
    private BorderStyle borderBottom;
    /** Border左侧 */
    private BorderStyle borderLeft;
    /** Border右侧 */
    private BorderStyle borderRight;
    /** Border颜色 */
    private String borderColor;

    // ==================== 对齐 ====================
    /** Horizontalalignment */
    private HorizontalAlignment horizontalAlignment;
    /** Verticalalignment */
    private VerticalAlignment verticalAlignment;
    /** Wrap文本 */
    private Boolean wrapText;
    /** Indention */
    private Integer indention;
    /** Rotation */
    private Integer rotation;

    // ==================== 数据格式 ====================
    /** 数据格式 */
    private String dataFormat;

    // ==================== 行高 ====================
    /** 行高度 */
    private Float rowHeight;

    /** 创建 CellStyleConfig 实例 */
    private CellStyleConfig() {
    }

    /** 创建 */
    public static CellStyleConfig create() {
        return new CellStyleConfig();
    }

    // ==================== 字体链式方法 ====================

    /** FontName */
    public CellStyleConfig fontName(String fontName) {
        this.fontName = fontName;
        return this;
    }

    /** Font获取大小 */
    public CellStyleConfig fontSize(int fontSize) {
        this.fontSize = (short) fontSize;
        return this;
    }

    /** Bold */
    public CellStyleConfig bold() {
        this.bold = true;
        return this;
    }

    /** Bold */
    public CellStyleConfig bold(boolean bold) {
        this.bold = bold;
        return this;
    }

    /** Italic */
    public CellStyleConfig italic() {
        this.italic = true;
        return this;
    }

    /** Strikeout */
    public CellStyleConfig strikeout() {
        this.strikeout = true;
        return this;
    }

    /** Underline */
    public CellStyleConfig underline(byte underline) {
        this.underline = underline;
        return this;
    }

    /** FontColor */
    public CellStyleConfig fontColor(String color) {
        this.fontColor = color;
        return this;
    }

    // ==================== 背景链式方法 ====================

    /** BackgroundColor */
    public CellStyleConfig backgroundColor(String color) {
        this.backgroundColor = color;
        return this;
    }

    /** FillPattern */
    public CellStyleConfig fillPattern(FillPatternType fillPattern) {
        this.fillPattern = fillPattern;
        return this;
    }

    // ==================== 边框链式方法 ====================

    /** Border */
    public CellStyleConfig border(BorderStyle border) {
        this.borderTop = border;
        this.borderBottom = border;
        this.borderLeft = border;
        this.borderRight = border;
        return this;
    }

    /** BorderTop */
    public CellStyleConfig borderTop(BorderStyle border) {
        this.borderTop = border;
        return this;
    }

    /** BorderBottom */
    public CellStyleConfig borderBottom(BorderStyle border) {
        this.borderBottom = border;
        return this;
    }

    /** BorderLeft */
    public CellStyleConfig borderLeft(BorderStyle border) {
        this.borderLeft = border;
        return this;
    }

    /** BorderRight */
    public CellStyleConfig borderRight(BorderStyle border) {
        this.borderRight = border;
        return this;
    }

    /** BorderColor */
    public CellStyleConfig borderColor(String color) {
        this.borderColor = color;
        return this;
    }

    // ==================== 对齐链式方法 ====================

    /** HorizontalLeft */
    public CellStyleConfig horizontalLeft() {
        this.horizontalAlignment = HorizontalAlignment.LEFT;
        return this;
    }

    /** HorizontalCenter */
    public CellStyleConfig horizontalCenter() {
        this.horizontalAlignment = HorizontalAlignment.CENTER;
        return this;
    }

    /** HorizontalRight */
    public CellStyleConfig horizontalRight() {
        this.horizontalAlignment = HorizontalAlignment.RIGHT;
        return this;
    }

    /** HorizontalAlignment */
    public CellStyleConfig horizontalAlignment(HorizontalAlignment alignment) {
        this.horizontalAlignment = alignment;
        return this;
    }

    /** VerticalTop */
    public CellStyleConfig verticalTop() {
        this.verticalAlignment = VerticalAlignment.TOP;
        return this;
    }

    /** VerticalCenter */
    public CellStyleConfig verticalCenter() {
        this.verticalAlignment = VerticalAlignment.CENTER;
        return this;
    }

    /** VerticalBottom */
    public CellStyleConfig verticalBottom() {
        this.verticalAlignment = VerticalAlignment.BOTTOM;
        return this;
    }

    /** VerticalAlignment */
    public CellStyleConfig verticalAlignment(VerticalAlignment alignment) {
        this.verticalAlignment = alignment;
        return this;
    }

    /** WrapText */
    public CellStyleConfig wrapText(boolean wrap) {
        this.wrapText = wrap;
        return this;
    }

    /** Indention */
    public CellStyleConfig indention(int indention) {
        this.indention = indention;
        return this;
    }

    /** Rotation */
    public CellStyleConfig rotation(int rotation) {
        this.rotation = rotation;
        return this;
    }

    // ==================== 数据格式 ====================

    /** Data格式化 */
    public CellStyleConfig dataFormat(String format) {
        this.dataFormat = format;
        return this;
    }

    // ==================== 行高 ====================

    /** RowHeight */
    public CellStyleConfig rowHeight(float height) {
        this.rowHeight = height;
        return this;
    }

    // ==================== Getter ====================

    /** 获取RowHeight */
    public Float getRowHeight() {
        return rowHeight;
    }

    // ==================== 应用样式到 POI CellStyle ====================

    /**
     * 将配置应用并创建 {@link CellStyle}。
     * <p>内部方法，由 ExcelWriteBuilder 调用。</p>
     *
     * @param workbook 工作簿
     * @return POI CellStyle 实例
     */
    public CellStyle applyTo(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();

        // === 字体 ===
        if (fontName != null) {
            font.setFontName(fontName);
        }
        if (fontSize != null) {
            font.setFontHeightInPoints(fontSize);
        }
        if (bold != null) {
            font.setBold(bold);
        }
        if (italic != null) {
            font.setItalic(italic);
        }
        if (strikeout != null) {
            font.setStrikeout(strikeout);
        }
        if (underline != null) {
            font.setUnderline(underline);
        }
        if (fontColor != null) {
            applyFontColor(font, workbook);
        }
        style.setFont(font);

        // === 背景填充 ===
        if (backgroundColor != null) {
            applyFillColor(style, workbook);
            style.setFillPattern(fillPattern != null ? fillPattern : FillPatternType.SOLID_FOREGROUND);
        }

        // === 边框 ===
        if (borderTop != null) {
            style.setBorderTop(borderTop);
            if (borderColor != null) {
                applyTopBorderColor(style, workbook);
            }
        }
        if (borderBottom != null) {
            style.setBorderBottom(borderBottom);
            if (borderColor != null) {
                applyBottomBorderColor(style, workbook);
            }
        }
        if (borderLeft != null) {
            style.setBorderLeft(borderLeft);
            if (borderColor != null) {
                applyLeftBorderColor(style, workbook);
            }
        }
        if (borderRight != null) {
            style.setBorderRight(borderRight);
            if (borderColor != null) {
                applyRightBorderColor(style, workbook);
            }
        }

        // === 对齐 ===
        if (horizontalAlignment != null) {
            style.setAlignment(horizontalAlignment);
        }
        if (verticalAlignment != null) {
            style.setVerticalAlignment(verticalAlignment);
        }
        if (wrapText != null) {
            style.setWrapText(wrapText);
        }
        if (indention != null) {
            style.setIndention((short) (int) indention);
        }
        if (rotation != null) {
            style.setRotation((short) (int) rotation);
        }

        // === 数据格式 ===
        if (dataFormat != null) {
            short fmtIdx = workbook.createDataFormat().getFormat(dataFormat);
            style.setDataFormat(fmtIdx);
        }

        return style;
    }

    // ==================== 颜色应用（POI 类型感知） ====================

    /**
     * 判断颜色字符串是否为六位 RGB 格式。
     */
    private static boolean isHexRgb(String color) {
        return color != null && color.matches("[0-9A-Fa-f]{6}");
    }

    /**
     * 将六位 RGB 字符串转为 XSSFColor。
     */
    private static XSSFColor hexToXssfColor(String hex) {
        Color awt = Color.decode("#" + hex);
        return new XSSFColor(new byte[]{
                (byte) awt.getRed(),
                (byte) awt.getGreen(),
                (byte) awt.getBlue()
        }, null);
    }

    /**
     * 获取 IndexedColors 索引，支持名称或 RGB 十六进制回退。
     */
    private static short indexedColor(String color) {
        if (color == null || color.isEmpty()) {
            return IndexedColors.BLACK.getIndex();
        }
        // 先按名称查找
        try {
            return IndexedColors.valueOf(color.toUpperCase()).getIndex();
        } catch (IllegalArgumentException ignored) {
            // fallthrough
        }
        return IndexedColors.BLACK.getIndex();
    }

    /**
     * 对字体应用颜色，XSSFWorkbook 下支持 RGB，否则回退 IndexedColors。
     */
    private void applyFontColor(Font font, Workbook workbook) {
        if (workbook instanceof XSSFWorkbook && isHexRgb(fontColor)) {
            XSSFFont xssfFont = (XSSFFont) font;
            xssfFont.setColor(hexToXssfColor(fontColor));
        } else {
            font.setColor(indexedColor(fontColor));
        }
    }

    /**
     * 对单元格背景应用颜色。
     */
    private void applyFillColor(CellStyle style, Workbook workbook) {
        if (style instanceof XSSFCellStyle xssfStyle && isHexRgb(backgroundColor)) {
            xssfStyle.setFillForegroundColor(hexToXssfColor(backgroundColor));
        } else {
            style.setFillForegroundColor(indexedColor(backgroundColor));
        }
    }

    /** 应用TopBorderColor */
    private void applyTopBorderColor(CellStyle style, Workbook workbook) {
        if (style instanceof XSSFCellStyle xssfStyle && isHexRgb(borderColor)) {
            xssfStyle.setTopBorderColor(hexToXssfColor(borderColor));
        } else {
            style.setTopBorderColor(indexedColor(borderColor));
        }
    }

    /** 应用BottomBorderColor */
    private void applyBottomBorderColor(CellStyle style, Workbook workbook) {
        if (style instanceof XSSFCellStyle xssfStyle && isHexRgb(borderColor)) {
            xssfStyle.setBottomBorderColor(hexToXssfColor(borderColor));
        } else {
            style.setBottomBorderColor(indexedColor(borderColor));
        }
    }

    /** 应用LeftBorderColor */
    private void applyLeftBorderColor(CellStyle style, Workbook workbook) {
        if (style instanceof XSSFCellStyle xssfStyle && isHexRgb(borderColor)) {
            xssfStyle.setLeftBorderColor(hexToXssfColor(borderColor));
        } else {
            style.setLeftBorderColor(indexedColor(borderColor));
        }
    }

    /** 应用RightBorderColor */
    private void applyRightBorderColor(CellStyle style, Workbook workbook) {
        if (style instanceof XSSFCellStyle xssfStyle && isHexRgb(borderColor)) {
            xssfStyle.setRightBorderColor(hexToXssfColor(borderColor));
        } else {
            style.setRightBorderColor(indexedColor(borderColor));
        }
    }
}
