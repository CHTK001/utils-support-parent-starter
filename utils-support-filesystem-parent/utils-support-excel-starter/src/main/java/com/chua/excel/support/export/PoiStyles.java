package com.chua.excel.support.export;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.ColumnType;
import com.chua.common.support.export.meta.HorizontalAlign;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.Map;

/**
 * POI 样式工厂 —— 按需创建并缓存 {@link CellStyle}。
 *
 * <p>Excel 样式数量有硬上限（64K），因此必须复用而不是每格新建。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class PoiStyles {

    /**
     * 表头底色（深蓝）。
     */
    private static final byte[] HEADER_RGB = {0x1F, 0x4E, (byte) 0x79};

    /**
     * 标题底色（浅蓝）。
     */
    private static final byte[] TITLE_RGB = {(byte) 0xDD, (byte) 0xEB, (byte) 0xF7};

    /**
     * 细边框颜色。
     */
    private static final byte[] BORDER_RGB = {(byte) 0xBF, (byte) 0xBF, (byte) 0xBF};

    /**
     * 百分比格式。
     */
    private static final String PERCENT_FORMAT = "0.00%";

    /**
     * 金额列默认小数位。
     */
    private static final int DEFAULT_SCALE = 2;

    /**
     * 样式缓存：签名 → 样式。
     */
    private final Map<String, CellStyle> cache = new ConcurrentReferenceHashMap<>(64);

    /**
     * 工作簿。
     */
    private final Workbook workbook;

    /**
     * 构造样式工厂。
     *
     * @param workbook 工作簿
     */
    PoiStyles(Workbook workbook) {
        this.workbook = workbook;
    }

    /**
     * 获取标题样式。
     *
     * @param lastCol 最后一列下标
     * @return 单元格样式
     */
    CellStyle title(int lastCol) {
        String key = "title:" + lastCol;
        return cache.computeIfAbsent(key, signature -> {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 14);
            style.setFont(font);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(TITLE_RGB, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        });
    }

    /**
     * 获取表头样式。
     *
     * @param lastCol 最后一列下标
     * @return 单元格样式
     */
    CellStyle header(int lastCol) {
        String key = "header:" + lastCol;
        return cache.computeIfAbsent(key, signature -> {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            style.setFont(font);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(HEADER_RGB, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(style);
            return style;
        });
    }

    /**
     * 获取数据单元格样式。
     *
     * @param column 列元数据
     * @return 单元格样式
     */
    CellStyle body(ColumnMeta column) {
        HorizontalAlign align = column.getAlign();
        boolean wrap = column.isWrap();
        ColumnType type = column.getType();
        String key = "body:" + align + ":" + wrap + ":" + type + ":" + column.getScale();
        return cache.computeIfAbsent(key, signature -> {
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(toPoi(align));
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(wrap);
            border(style);
            if (type == ColumnType.PERCENT) {
                style.setDataFormat(workbook.createDataFormat().getFormat(PERCENT_FORMAT));
            } else if (type == ColumnType.AMOUNT || (type == ColumnType.DECIMAL && column.getScale() >= 0)) {
                int scale = column.getScale() >= 0 ? column.getScale() : DEFAULT_SCALE;
                style.setDataFormat(workbook.createDataFormat().getFormat("0." + "0".repeat(scale)));
            }
            return style;
        });
    }

    /**
     * 合并标题行区域。
     *
     * @param style 单元格样式
     * @param title 标题文本
     * @param totalColumns 总列数
     */
    void applyTitle(org.apache.poi.ss.usermodel.Sheet sheet, String title, int totalColumns) {
        if (title == null || title.isEmpty() || totalColumns <= 0) {
            return;
        }
        org.apache.poi.ss.usermodel.Row row = sheet.createRow(0);
        row.setHeightInPoints(26f);
        org.apache.poi.ss.usermodel.Cell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(title(totalColumns - 1));
        if (totalColumns > 1) {
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, totalColumns - 1));
        }
    }

    /**
     * 设置细边框。
     *
     * @param style 单元格样式
     */
    private void border(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        if (style instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle xssf) {
            var color = new org.apache.poi.xssf.usermodel.XSSFColor(BORDER_RGB, null);
            xssf.setTopBorderColor(color);
            xssf.setBottomBorderColor(color);
            xssf.setLeftBorderColor(color);
            xssf.setRightBorderColor(color);
            return;
        }
        short color = IndexedColors.GREY_40_PERCENT.getIndex();
        style.setTopBorderColor(color);
        style.setBottomBorderColor(color);
        style.setLeftBorderColor(color);
        style.setRightBorderColor(color);
    }

    /**
     * 转换为 POI 对齐枚举。
     *
     * @param align 自定义对齐
     * @return POI 对齐
     */
    private static HorizontalAlignment toPoi(HorizontalAlign align) {
        return switch (align) {
            case LEFT -> HorizontalAlignment.LEFT;
            case CENTER -> HorizontalAlignment.CENTER;
            case RIGHT -> HorizontalAlignment.RIGHT;
            default -> HorizontalAlignment.LEFT;
        };
    }
}