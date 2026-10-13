package com.chua.word.support.export;

import com.chua.common.support.export.ExportContext;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.DocumentGenerator;
import com.chua.common.support.export.support.Rows;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/**
 * POI Word 文档生成器 —— 接口一在 Word 侧的实现。
 *
 * <p>按 {@code DocumentMeta} 从零构建 docx：可选标题段落、表头表格（带底色与边框）、
 * 数据行，并按 {@code rowsPerSheet} 自动分页插入分节标题。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"poi-word", "word"}, order = 0)
public class PoiWordDocumentGenerator implements DocumentGenerator {

    /**
     * 表头底色（深蓝）。
     */
    private static final String HEADER_SHADING = "1F4E79";

    /**
     * 表头文字颜色（白色）。
     */
    private static final String HEADER_COLOR = "FFFFFF";

    /**
     * 表格宽度（百分比）。
     */
    private static final int TABLE_WIDTH = 5000;

    @Override
    /**
     * 支持的格式
     * @return 格式 对象
     */
    public Set<String> formats() {
        return Set.of(ExportFormats.DOCX);
    }

    @Override
    /**
     * 引擎名
     * @return 引擎名 对象
     */
    public String engine() {
        return "poi-word";
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
        try (XWPFDocument document = new XWPFDocument()) {
            if (!meta.getTitle().isEmpty()) {
                writeTitle(document, meta.getTitle());
            } else if (!meta.resolveSheetName().isEmpty()) {
                writeTitle(document, meta.resolveSheetName());
            }
            int columnCount = columns.size();
            XWPFTable table = document.createTable(1, columnCount);
            table.setWidth(TABLE_WIDTH);

            int cursor = 0;
            if (meta.isHeader()) {
                XWPFTableRow header = table.getRow(0);
                for (int c = 0; c < columnCount; c++) {
                    writeCell(header.getCell(c), columns.get(c).getName(), true);
                }
                cursor = 1;
            }

            int perSheet = meta.getRowsPerSheet() > 0 ? meta.getRowsPerSheet() : Integer.MAX_VALUE;
            int sheetIndex = 0;
            for (int r = 0; r < context.size(); r++) {
                if (perSheet != Integer.MAX_VALUE && r > 0 && r % perSheet == 0) {
                    document.createParagraph().setPageBreak(true);
                    sheetIndex++;
                    writeTitle(document, meta.resolveSheetName() + " (" + (sheetIndex + 1) + ")");
                    table = document.createTable(1, columnCount);
                    table.setWidth(TABLE_WIDTH);
                    XWPFTableRow header = table.getRow(0);
                    for (int c = 0; c < columnCount; c++) {
                        writeCell(header.getCell(c), columns.get(c).getName(), true);
                    }
                    cursor = 1;
                }
                Object row = context.rows().get(r);
                XWPFTableRow target = table.createRow();
                for (int c = 0; c < columnCount; c++) {
                    ColumnMeta column = columns.get(c);
                    Object value = Rows.display(row, column);
                    writeCell(target.getCell(c), value == null ? "" : String.valueOf(value), false);
                }
            }
            if (cursor == 0 && !meta.isHeader()) {
                // 无表头时首行为空行，删除之
                table.removeRow(0);
            }
            document.write(out);
        }
    }

    /**
     * 写入标题段落。
     *
     * @param document 文档
     * @param text     标题
     */
    private void writeTitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(16);
        run.setText(text);
    }

    /**
     * 写入单元格。
     *
     * @param cell   单元格
     * @param text   文本
     * @param header 是否表头
     */
    private void writeCell(XWPFTableCell cell, String text, boolean header) {
        if (cell == null) {
            return;
        }
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty()
                ? cell.addParagraph()
                : cell.getParagraphs().get(0);
        paragraph.setAlignment(header ? ParagraphAlignment.CENTER : ParagraphAlignment.LEFT);
        XWPFRun run = paragraph.createRun();
        run.setBold(header);
        if (header) {
            run.setColor(HEADER_COLOR);
            cell.setColor(HEADER_SHADING);
        }
        run.setText(text);
    }
}