package com.chua.common.support.file.txtractor;

import com.chua.common.support.spi.ServiceProvider;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 文本提取器接口，用于从各种文件格式中提取纯文本内容。
 * <p>
 * 各模块通过 SPI 机制（{@code @Spi}）注册自己的实现，例如：
 * <ul>
 *   <li>excel-starter — 从 Excel 单元格中提取文本</li>
 *   <li>pdf-starter — 从 PDF 文档中提取文本</li>
 *   <li>word-starter — 从 Word 文档中提取文本</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * String text = TextExtractor.create("pdf").extractText(new File("doc.pdf"));
 * }</pre>
 * </p>
 *
 * @author CH
 */
public interface TextExtractor {

    /**
     * 根据 SPI 类型创建文本提取器实例。
     *
     * @param type 文件类型 SPI 名称（如 "excel"、"pdf"、"docx"）
     * @return TextExtractor 实例
     * @throws IllegalArgumentException 当找不到对应 SPI 实现时抛出
     */
    static TextExtractor create(String type) {
        TextExtractor extractor = ServiceProvider.of(TextExtractor.class)
                .getExtension(type);
        if (extractor == null) {
            throw new IllegalArgumentException("未找到 TextExtractor SPI 实现: " + type);
        }
        return extractor;
    }

    /**
     * 根据文件名自动匹配文本提取器。
     *
     * @param file 文件对象
     * @return TextExtractor 实例
     * @throws IllegalArgumentException 当文件类型不支持时抛出
     */
    static TextExtractor auto(File file) {
        Objects.requireNonNull(file, "file must not be null");
        String name = file.getName().toLowerCase();

        if (name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".xlsm")) {
            return create("excel");
        } else if (name.endsWith(".pdf")) {
            return create("pdf");
        } else if (name.endsWith(".docx") || name.endsWith(".doc")) {
            return create("docx");
        } else if (name.endsWith(".csv") || name.endsWith(".tsv")) {
            return create("csv");
        } else if (name.endsWith(".txt")) {
            return create("txt");
        } else {
            throw new IllegalArgumentException("不支持的文件类型: " + name);
        }
    }

    /**
     * 从文件中提取结构化文本内容。
     * <p>
     * 返回结果列表，每个元素包含文本片段及其结构信息（章节、页码等）。
     * 对于无结构信息的文档（如纯文本），返回包含单一结果的列表。
     * </p>
     *
     * @param file 待提取的文件
     * @return 结构化文本结果列表
     */
    List<TextExtractResult> extractText(File file);

    /**
     * 从文件中提取纯文本内容（无结构信息，合并所有片段）。
     *
     * @param file 待提取的文件
     * @return 合并后的纯文本内容
     */
    default String extractFullText(File file) {
        List<TextExtractResult> results = extractText(file);
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TextExtractResult result : results) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(result.text());
        }
        return sb.toString();
    }

    /**
     * 获取当前提取器支持的 SPI 类型名称（如 "pdf"、"docx"）。
     *
     * @return 类型名称
     */
    String type();
}
