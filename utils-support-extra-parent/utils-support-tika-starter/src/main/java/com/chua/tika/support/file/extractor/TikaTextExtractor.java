package com.chua.tika.support.file.extractor;

import com.chua.common.support.file.txtractor.TextExtractResult;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToTextContentHandler;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Apache Tika 的通用文本提取器 SPI 实现，支持从多种文档格式中提取纯文本内容。
 * <p>
 * Tika 通过 {@link AutoDetectParser} 自动检测文件类型并调用对应的解析器，
 * 支持的格式包括但不限于：PDF、Word（.docx/.doc）、Excel（.xlsx/.xls）、
 * PowerPoint（.pptx）、HTML、XML、CSV、RTF、EPUB、邮件（.msg/.eml）等。
 * </p>
 *
 * <pre>{@code
 * // 使用方式
 * String text = TextExtractor.create("tika").extractText(new File("document.pdf"));
 * }</pre>
 *
 * @author CH
 */
@Slf4j
@Spi("tika")
public class TikaTextExtractor implements TextExtractor {

    private final Parser parser = new AutoDetectParser();

    @Override
    public List<TextExtractResult> extractText(File file) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());

        try (InputStream input = new FileInputStream(file)) {
            ToTextContentHandler handler = new ToTextContentHandler();
            parser.parse(input, handler, metadata, new ParseContext());
            String text = handler.toString();
            if (text == null || text.isEmpty()) {
                return Collections.emptyList();
            }

            // 尝试从 metadata 获取页码信息
            int pageCount = 0;
            try {
                String pages = metadata.get("xmpTPg:NPages");
                if (pages != null) {
                    pageCount = Integer.parseInt(pages);
                }
            } catch (Exception ignored) {
            }

            // 尝试获取标题/章节
            String title = metadata.get("dc:title");
            if (title == null) {
                title = metadata.get("title");
            }

            TextExtractResult result;
            if (pageCount > 0) {
                // 有页码信息，按大致等分分段
                String[] paragraphs = text.split("\n\\s*\n");
                if (paragraphs.length <= 1) {
                    result = new TextExtractResult(text.trim(), title != null ? title : "", 1, file.getName());
                    return Collections.singletonList(result);
                }
                // 简单分块：每 N 段为一页
                int paragraphsPerPage = Math.max(1, paragraphs.length / pageCount);
                java.util.List<TextExtractResult> results = new java.util.ArrayList<>();
                StringBuilder pageBuilder = new StringBuilder();
                for (int i = 0; i < paragraphs.length; i++) {
                    pageBuilder.append(paragraphs[i]).append("\n\n");
                    if ((i + 1) % paragraphsPerPage == 0 || i == paragraphs.length - 1) {
                        int pageNum = (i / paragraphsPerPage) + 1;
                        results.add(new TextExtractResult(
                                pageBuilder.toString().trim(), title != null ? title : "", pageNum, file.getName()));
                        pageBuilder.setLength(0);
                    }
                }
                return results;
            }

            // 无页码信息，返回单一结果
            return Collections.singletonList(new TextExtractResult(text.trim(), title != null ? title : "", 0, file.getName()));
        } catch (IOException | SAXException | TikaException e) {
            log.warn("Tika 提取文本失败: {}", file.getAbsolutePath(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public String type() {
        return "tika";
    }
}
