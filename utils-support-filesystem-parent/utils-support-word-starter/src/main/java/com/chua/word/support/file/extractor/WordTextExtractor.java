package com.chua.word.support.file.extractor;

import com.chua.common.support.file.txtractor.TextExtractResult;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
* Word 文本提取器 SPI 实现，从 Word 文档中提取纯文本内容。
* <p>
* 基于 Apache POI 实现，支持 .docx 格式的段落文本提取。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"docx", "doc"})
public class WordTextExtractor implements TextExtractor {

    /**
    * 标题样式前缀（Word 内置标题样式）。
    */
    private static final String[] HEADING_STYLES = {"Heading1", "Heading2", "Heading3",
            "Heading4", "Heading5", "Heading6", "heading1", "heading2", "heading3"};

    @Override
    /** extract文本 */
    public List<TextExtractResult> extractText(File file) {
        List<TextExtractResult> results = new ArrayList<>();
        String currentSection = "";
        StringBuilder currentText = new StringBuilder();

        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file))) {
            // 段落处理：检测标题切换章节
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                if (isHeading(p)) {
                    // 保存上一章节内容
                    if (currentText.length() > 0) {
                        results.add(new TextExtractResult(
                                currentText.toString().trim(), currentSection, 0, file.getName()));
                        currentText.setLength(0);
                    }
                    currentSection = text;
                } else {
                    if (currentText.length() > 0) {
                        currentText.append("\n");
                    }
                    currentText.append(text);
                }
            }
            // 保存最后一章节
            if (currentText.length() > 0) {
                results.add(new TextExtractResult(
                        currentText.toString().trim(), currentSection, 0, file.getName()));
            }

            // 提取表格中的文本（归入最后章节）
            StringBuilder tableSb = new StringBuilder();
            doc.getTables().forEach(table -> {
                for (var row : table.getRows()) {
                    for (var cell : row.getTableCells()) {
                        tableSb.append(cell.getText().trim()).append("\t");
                    }
                    tableSb.append("\n");
                }
            });
            if (tableSb.length() > 0) {
                results.add(new TextExtractResult(
                        tableSb.toString().trim(), currentSection, 0, file.getName()));
            }

            // 如果没有任何结果（全是标题或空文档），添加一个空结果
            if (results.isEmpty()) {
                results.add(new TextExtractResult("", "", 0, file.getName()));
            }
        } catch (IOException e) {
            log.warn("提取 Word 文本失败: {}", file.getAbsolutePath(), e);
        }

        return results;
    }

    /**
    * 判断段落是否为标题。
    * @param p p
    * @return 是否heading的结果
    */
    private boolean isHeading(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null || style.isEmpty()) {
            return false;
        }
        for (String headingStyle : HEADING_STYLES) {
            if (style.equalsIgnoreCase(headingStyle)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 类型 */
    public String type() {
        return "docx";
    }
}
