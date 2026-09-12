package com.chua.pdf.support.file.extractor;

import com.chua.common.support.file.txtractor.TextExtractResult;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 文本提取器 SPI 实现，从 PDF 文档中提取纯文本内容。
 * <p>
   * 基于 Apache pdfbox 实现，支持提取全部页面的文本。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("pdf")
public class PdfTextExtractor implements TextExtractor {

    @Override
    /** extract文本 */
    public List<TextExtractResult> extractText(File file) {
        List<TextExtractResult> results = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(file)) {
            // 解析书签/大纲结构，获取章节→页码映射
            Map<Integer, String> pageToSection = resolveBookmarks(doc);
            int totalPages = doc.getNumberOfPages();

            // 逐页提取
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.trim().isEmpty()) {
                    continue;
                }
                String section = pageToSection.getOrDefault(page, "");
                results.add(new TextExtractResult(pageText.trim(), section, page, file.getName()));
            }
        } catch (IOException e) {
            log.warn("提取 PDF 文本失败: {}", file.getAbsolutePath(), e);
        }

        return results;
    }

    /**
     * 解析 PDF 书签（大纲），建立 页码 → 章节名称 的映射。
     * @param doc doc
     * @return resolveBookmarks的结果
     */
    private Map<Integer, String> resolveBookmarks(PDDocument doc) throws IOException {
        Map<Integer, String> pageToSection = new java.util.LinkedHashMap<>();
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline != null) {
            collectBookmarks(outline, doc, pageToSection, "");
        }
        return pageToSection;
    }

    /**
     * 递归遍历PDF书签节点，收集章节名称与页码的映射关系。
     * <p>
     * 该方法会遍历当前节点下的所有子书签，提取书签标题并计算其在全书中的完整路径（包含父级路径）。
     * 同时尝试获取书签指向的目标页码，并将其存入映射表中。如果书签没有明确的页码或解析失败，则跳过该书签。
     * </p>
     *
     * @param node           当前正在处理的书签节点 (pdoutline节点)
     * @param doc            加载的PDF文档对象 (pd文档)
     * @param pageToSection  用于存储页码到章节名称映射的有序映射
     * @param parentSection  当前节点的父级章节路径字符串
     */
    private void collectBookmarks(PDOutlineNode node,
                                  PDDocument doc,
                                  Map<Integer, String> pageToSection,
                                  String parentSection) {
        PDOutlineItem current = node.getFirstChild();

        while (current != null) {
            String sectionName = current.getTitle();

            if (sectionName != null && !sectionName.isEmpty()) {
                String fullPath;

                if (parentSection.isEmpty()) {
                    fullPath = sectionName;
                } else {
                    fullPath = parentSection + " > " + sectionName;
                }

                try {
                    var page = current.findDestinationPage(doc);

                    if (page != null) {
                        int pageNum = doc.getPages().indexOf(page) + 1;
                        pageToSection.putIfAbsent(pageNum, fullPath);
                    }
                } catch (Exception e) {
                    log.debug("无法解析书签页码: {}", sectionName);
                }

                collectBookmarks(current, doc, pageToSection, fullPath);
            }

            current = current.getNextSibling();
        }
    }

    @Override
    /** 类型 */
    public String type() {
        return "pdf";
    }
}
