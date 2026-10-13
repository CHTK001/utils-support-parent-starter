package com.chua.excel.support.export;

import com.chua.common.support.export.ExportContext;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.DocumentGenerator;
import com.chua.common.support.export.support.Rows;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * POI 文档生成器 —— 接口一在 Excel 侧的实现。
 *
 * <p>按 {@code DocumentMeta} 从零构建工作簿：可选标题行、表头、冻结窗格、自动筛选、
 * 列宽自适应，以及超出 {@code rowsPerSheet} 时自动拆分为多个工作表。</p>
 *
 * <p>仅产出 OOXML（{@code .xlsx}）；旧版二进制 {@code .xls} 无法处理，
 * 调用时需改用 {@code .xlsx} 以免拿到与扩展名不符的内容。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"poi", "excel"}, order = 0)
public class PoiDocumentGenerator implements DocumentGenerator {

    /**
     * 默认列宽（字符数）。
     */
    private static final int DEFAULT_WIDTH = 16;

    /**
     * 列宽换算为 POI 单位（1/256 字符）。
     */
    private static final int WIDTH_UNIT = 256;

    /**
     * 工作表名最大长度。
     */
    private static final int MAX_SHEET_NAME = 28;

    /**
     * 非法字符正则。
     */
    private static final String ILLEGAL_CHARS = "[\\\\/*?:\\[\\]]";

    @Override
    /**
     * 支持的格式
     * @return 格式 对象
     */
    public Set<String> formats() {
        return Set.of(ExportFormats.XLSX);
    }

    @Override
    /**
     * 引擎名
     * @return 引擎名 对象
     */
    public String engine() {
        return "poi";
    }

    @Override
    /**
     * 生成文档
     * @param context 导出上下文
     * @param out     输出流
     * @throws IOException 写出异常
     */
    public void generate(ExportContext context, OutputStream out) throws IOException {
        List<ColumnMeta> columns = context.columns();
        if (columns.isEmpty()) {
            throw new ExportException("没有可导出的列，请检查 @ExportColumn 或列限定参数");
        }
        DocumentMeta meta = context.meta();
        Workbook workbook = context.config().isStreaming() ? new SXSSFWorkbook(200) : new XSSFWorkbook();
        try {
            PoiStyles styles = new PoiStyles(workbook);
            List<Object> rows = context.rows();
            int chunkSize = meta.getRowsPerSheet() > 0 ? meta.getRowsPerSheet() : Math.max(rows.size(), 1);
            int chunks = Math.max(1, (int) Math.ceil((double) rows.size() / chunkSize));

            for (int chunk = 0; chunk < chunks; chunk++) {
                Sheet sheet = workbook.createSheet(sheetName(meta, chunk, chunks));
                writeSheet(sheet, styles, columns, meta, rows, chunk * chunkSize, chunkSize);
            }
            workbook.setActiveSheet(0);
            workbook.setSelectedTab(0);
            workbook.write(out);
        } finally {
            workbook.close();
            if (workbook instanceof SXSSFWorkbook sxssf) {
                sxssf.dispose();
            }
        }
    }

    /**
     * 写出单个工作表。
     *
     * @param sheet   工作表
     * @param styles  样式工厂
     * @param columns 列
     * @param meta    元数据
     * @param rows    全部行
     * @param offset  起始行下标
     * @param limit   本表最大数据行数
     */
    private void writeSheet(Sheet sheet, PoiStyles styles, List<ColumnMeta> columns, DocumentMeta meta,
                            List<Object> rows, int offset, int limit) {
        int columnCount = columns.size();
        int headerIndex = Math.max(0, meta.getHeaderRow() - 1);
        boolean withTitle = headerIndex > 0 && !meta.getTitle().isEmpty();
        boolean withHeader = meta.isHeader();
        // 无标题且无表头时数据从第 0 行开始，避免留下空行
        int cursor = withTitle || withHeader ? headerIndex : 0;

        if (withTitle) {
            styles.applyTitle(sheet, meta.getTitle(), columnCount);
        }

        if (withHeader) {
            Row header = sheet.createRow(cursor);
            header.setHeightInPoints(22f);
            for (int i = 0; i < columnCount; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i).getName());
                cell.setCellStyle(styles.header(columnCount));
            }
        }

        int dataStart = cursor + (withHeader ? 1 : 0);
        int end = Math.min(rows.size(), offset + limit);
        for (int r = offset; r < end; r++) {
            Row row = sheet.createRow(dataStart + (r - offset));
            Object source = rows.get(r);
            for (int c = 0; c < columnCount; c++) {
                ColumnMeta column = columns.get(c);
                Cell cell = row.createCell(c);
                cell.setCellStyle(styles.body(column));
                writeCell(cell, Rows.display(source, column), column);
            }
        }

        if (meta.isFreezeHeader() && dataStart > 0) {
            sheet.createFreezePane(0, dataStart);
        }
        if (meta.isAutoFilter() && end > offset) {
            int last = dataStart + (end - offset) - 1;
            sheet.setAutoFilter(new CellRangeAddress(cursor, last, 0, columnCount - 1));
        }
        for (int c = 0; c < columnCount; c++) {
            sheet.setColumnWidth(c, width(columns.get(c)) * WIDTH_UNIT);
        }
    }

    /**
     * 组装工作表名。
     *
     * @param meta   元数据
     * @param chunk  分片序号
     * @param chunks 分片总数
     * @return 工作表名
     */
    private String sheetName(DocumentMeta meta, int chunk, int chunks) {
        String base = meta.resolveSheetName().replaceAll(ILLEGAL_CHARS, "_");
        if (base.isEmpty()) {
            base = "Sheet1";
        }
        if (base.length() > MAX_SHEET_NAME) {
            base = base.substring(0, MAX_SHEET_NAME);
        }
        return chunks <= 1 ? base : base + "_" + (chunk + 1);
    }

    /**
     * 计算列宽（字符数）。
     *
     * @param column 列元数据
     * @return 列宽
     */
    private int width(ColumnMeta column) {
        if (column.getWidth() > 0) {
            return column.getWidth();
        }
        return Math.max(DEFAULT_WIDTH, Math.min(40, column.getName().length() * 2 + 6));
    }

    /**
     * 写入单元格。
     *
     * @param cell   单元格
     * @param value  展示值
     * @param column 列元数据
     */
    private void writeCell(Cell cell, Object value, ColumnMeta column) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Boolean flag) {
            cell.setCellValue(flag);
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }
}