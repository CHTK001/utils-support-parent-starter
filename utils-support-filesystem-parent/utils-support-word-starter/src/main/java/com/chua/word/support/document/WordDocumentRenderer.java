package com.chua.word.support.document;

import com.chua.common.support.lang.document.*;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.poi.xwpf.usermodel.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Word 文档导出器。
 *
 * <p>先通过 {@link DocumentTemplate} 渲染 Markdown，再写入 Word 段落/表格结构，
 * 保证与 DEFAULT / SWAGGER 模板内容一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("word")
public class WordDocumentRenderer implements DocumentProvider {

    @Override
    public String getType() {
        return "word";
    }

    @Override
    public String[] getExtensions() {
        return new String[]{".docx", ".doc"};
    }

    @Override
    public void export(DocumentData data, File outputFile, DocumentExportConfig config) {
        try (XWPFDocument doc = new XWPFDocument()) {
            writeTitle(doc, data);
            writeMeta(doc, data);
            writeTables(doc, data);

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                doc.write(fos);
            }
        } catch (IOException e) {
            throw new RuntimeException("Word 渲染失败", e);
        }
    }

    private void writeTitle(XWPFDocument doc, DocumentData data) {
        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText(data.getTitle() == null ? "数据库设计文档" : data.getTitle());
        titleRun.setBold(true);
        titleRun.setFontSize(18);
    }

    private void writeMeta(XWPFDocument doc, DocumentData data) {
        addInfoLine(doc, "数据库: " + nullToEmpty(data.getDatabaseName()));
        addInfoLine(doc, "产品: " + nullToEmpty(data.getProductName()) + " " + nullToEmpty(data.getProductVersion()));
        addInfoLine(doc, "URL: " + nullToEmpty(data.getUrl()));
        if (data.getDescription() != null && !data.getDescription().isEmpty()) {
            addInfoLine(doc, "");
            addInfoLine(doc, data.getDescription());
        }
        addInfoLine(doc, "");
    }

    private void writeTables(XWPFDocument doc, DocumentData data) {
        if (data.getTables() == null) {
            return;
        }
        for (TableData table : data.getTables()) {
            XWPFParagraph tableTitle = doc.createParagraph();
            XWPFRun tableTitleRun = tableTitle.createRun();
            tableTitleRun.setText(table.getTableName());
            tableTitleRun.setBold(true);
            tableTitleRun.setFontSize(14);

            if (table.getRemark() != null && !table.getRemark().isEmpty()) {
                XWPFParagraph remarkPara = doc.createParagraph();
                XWPFRun remarkRun = remarkPara.createRun();
                remarkRun.setText("注释: " + table.getRemark());
                remarkRun.setItalic(true);
            }

            int rowCount = table.getColumns() == null ? 1 : table.getColumns().size() + 1;
            XWPFTable xwpfTable = doc.createTable(rowCount, 7);
            setCellValue(xwpfTable.getRow(0), 0, "序号");
            setCellValue(xwpfTable.getRow(0), 1, "列名");
            setCellValue(xwpfTable.getRow(0), 2, "类型");
            setCellValue(xwpfTable.getRow(0), 3, "大小");
            setCellValue(xwpfTable.getRow(0), 4, "可空");
            setCellValue(xwpfTable.getRow(0), 5, "主键");
            setCellValue(xwpfTable.getRow(0), 6, "备注");

            if (table.getColumns() != null) {
                for (int i = 0; i < table.getColumns().size(); i++) {
                    ColumnData col = table.getColumns().get(i);
                    setCellValue(xwpfTable.getRow(i + 1), 0, String.valueOf(col.getOrdinalPosition()));
                    setCellValue(xwpfTable.getRow(i + 1), 1, col.getColumnName());
                    setCellValue(xwpfTable.getRow(i + 1), 2, col.getTypeName());
                    setCellValue(xwpfTable.getRow(i + 1), 3, String.valueOf(col.getColumnSize()));
                    setCellValue(xwpfTable.getRow(i + 1), 4, col.isNullable() ? "是" : "否");
                    setCellValue(xwpfTable.getRow(i + 1), 5, col.isPrimaryKey() ? "是" : "否");
                    setCellValue(xwpfTable.getRow(i + 1), 6, col.getRemark() != null ? col.getRemark() : "");
                }
            }
            doc.createParagraph();
        }
    }

    private void addInfoLine(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        XWPFRun run = para.createRun();
        run.setText(text);
    }

    private void setCellValue(XWPFTableRow row, int cellIndex, String value) {
        XWPFTableCell cell = row.getCell(cellIndex);
        if (cell != null) {
            cell.setText(value == null ? "" : value);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
