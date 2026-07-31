package com.chua.word.support.document;

import com.chua.common.support.lang.document.*;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;

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
            String title = nullToEmpty(table.getTableName());
            if (table.getRemark() != null && !table.getRemark().isEmpty()) {
                title += "（" + table.getRemark() + "）";
            }
            XWPFParagraph tableTitle = doc.createParagraph();
            XWPFRun tableTitleRun = tableTitle.createRun();
            tableTitleRun.setText(title);
            tableTitleRun.setBold(true);
            tableTitleRun.setFontSize(16);
            tableTitleRun.setFontFamily("微软雅黑");
            tableTitleRun.setColor("1F4E79");

            int rowCount = table.getColumns() == null ? 1 : table.getColumns().size() + 1;
            XWPFTable xwpfTable = doc.createTable(rowCount, 9);
            setCellValue(xwpfTable.getRow(0), 0, "序号");
            setCellValue(xwpfTable.getRow(0), 1, "列名");
            setCellValue(xwpfTable.getRow(0), 2, "类型");
            setCellValue(xwpfTable.getRow(0), 3, "大小");
            setCellValue(xwpfTable.getRow(0), 4, "小数位");
            setCellValue(xwpfTable.getRow(0), 5, "可空");
            setCellValue(xwpfTable.getRow(0), 6, "主键");
            setCellValue(xwpfTable.getRow(0), 7, "默认值");
            setCellValue(xwpfTable.getRow(0), 8, "备注");

            styleHeaderRow(xwpfTable.getRow(0));

            if (table.getColumns() != null) {
                for (int i = 0; i < table.getColumns().size(); i++) {
                    ColumnData col = table.getColumns().get(i);
                    setCellValue(xwpfTable.getRow(i + 1), 0, String.valueOf(col.getOrdinalPosition()));
                    setCellValue(xwpfTable.getRow(i + 1), 1, col.getColumnName());
                    setCellValue(xwpfTable.getRow(i + 1), 2, col.getTypeName());
                    setCellValue(xwpfTable.getRow(i + 1), 3, String.valueOf(col.getColumnSize()));
                    setCellValue(xwpfTable.getRow(i + 1), 4, col.getDecimalDigits() != null ? String.valueOf(col.getDecimalDigits()) : "");
                    setCellValue(xwpfTable.getRow(i + 1), 5, col.isNullable() ? "是" : "否");
                    setCellValue(xwpfTable.getRow(i + 1), 6, col.isPrimaryKey() ? "是" : "否");
                    setCellValue(xwpfTable.getRow(i + 1), 7, col.getDefaultValue() != null ? col.getDefaultValue() : "");
                    setCellValue(xwpfTable.getRow(i + 1), 8, col.getRemark() != null ? col.getRemark() : "");
                }
            }

            setTableBorders(xwpfTable);
            XWPFParagraph space = doc.createParagraph();
            space.setSpacingAfter(200);
        }
    }

    private void addInfoLine(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontSize(10);
        run.setFontFamily("微软雅黑");
    }

    private void styleHeaderRow(XWPFTableRow row) {
        for (int i = 0; i < row.getTableCells().size(); i++) {
            XWPFTableCell cell = row.getCell(i);
            if (cell == null) {
                continue;
            }
            XWPFParagraph para = cell.getParagraphs().get(0);
            para.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = para.getRuns().isEmpty() ? para.createRun() : para.getRuns().get(0);
            run.setBold(true);
            run.setFontSize(10);
            run.setFontFamily("微软雅黑");
            run.setColor("FFFFFF");

            CTTc ctTc = cell.getCTTc();
            CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();
            CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
            shd.setFill("1F4E79");
            shd.setVal(STShd.Enum.forString("clear"));
        }
    }

    private void setTableBorders(XWPFTable table) {
        CTTbl ctTbl = table.getCTTbl();
        CTTblPr tblPr = ctTbl.getTblPr() != null ? ctTbl.getTblPr() : ctTbl.addNewTblPr();
        CTTblBorders borders = tblPr.isSetTblBorders() ? tblPr.getTblBorders() : tblPr.addNewTblBorders();

        setBorder(borders.addNewTop(), "1", STBorder.SINGLE, "auto");
        setBorder(borders.addNewLeft(), "1", STBorder.SINGLE, "auto");
        setBorder(borders.addNewBottom(), "1", STBorder.SINGLE, "auto");
        setBorder(borders.addNewRight(), "1", STBorder.SINGLE, "auto");
        setBorder(borders.addNewInsideH(), "1", STBorder.SINGLE, "auto");
        setBorder(borders.addNewInsideV(), "1", STBorder.SINGLE, "auto");
    }

    private void setBorder(CTBorder border, String size, STBorder.Enum type, String color) {
        border.setSz(BigInteger.valueOf(Long.parseLong(size)));
        border.setVal(type);
        border.setColor(color);
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
