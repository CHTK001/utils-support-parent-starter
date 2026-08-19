package com.chua.word.support.file.impl;

import com.chua.common.support.file.builder.ReadBuilder;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Word 文件读取构建器。
 *
 * <p>基于 Apache POI 实现 .docx 文档的文本内容提取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WordReadBuilder extends ReadBuilder {

    /**
     * 创建 WordReadBuilder 实例
     * @param file file
     */
    public WordReadBuilder(File file) {
        super(file);
    }

    @Override
    /** WithCharset */
    public WordReadBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    /**
     * 提取 Word 文档的全部文本内容
     */
    public String text() {
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            String result = sb.toString();
            if (callback != null) {
                callback.onBody(result);
                callback.onComplete(1);
            }
            return result;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 按段落读取
     */
    public List<String> paragraphs() {
        List<String> result = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file))) {
            for (var p : doc.getParagraphs()) {
                String text = p.getText();
                result.add(text);
                if (callback != null) {
                    callback.onBody(text);
                }
            }
        } catch (IOException ignored) {}
        if (callback != null) {
            callback.onComplete(result.size());
        }
        return result;
    }

    /**
     * 读取表格数据（Word 表格），每条为 Map
     */
    public List<Map<String, String>> tableRows() {
        List<Map<String, String>> result = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file))) {
            for (var table : doc.getTables()) {
                List<String> headerCols = new ArrayList<>();
                boolean first = true;
                for (var row : table.getRows()) {
                    List<String> cells = row.getTableCells().stream()
                            .map(c -> c.getText().trim()).toList();
                    if (first) {
                        headerCols.addAll(cells);
                        first = false;
                        if (callback != null) {
                            callback.onHeader(headerCols);
                        }
                        continue;
                    }
                    Map<String, String> map = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < cells.size() && i < headerCols.size(); i++) {
                        String key = headerCols.get(i);
                        if (columnMapping != null) {
                            key = columnMapping.getOrDefault(key, key);
                        }
                        map.put(key, cells.get(i));
                    }
                    result.add(map);
                    if (callback != null) {
                        callback.onBody(map);
                    }
                }
            }
        } catch (IOException ignored) {}
        if (callback != null) {
            callback.onComplete(result.size());
        }
        return result;
    }

    @Override
    /** 读取 */
    public Object read() {
        return text();
    }

    /**
     * 获取文档标题
     */
    public String title() {
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file))) {
            var props = doc.getProperties();
            return props != null && props.getCoreProperties() != null
                    ? props.getCoreProperties().getTitle() : null;
        } catch (IOException e) { return null; }
    }

    @Override
    /** AsLines */
    public List<String> asLines() {
         return paragraphs(); 
    }

    @Override
    /** AsString */
    public String asString() {
         return text(); 
    }
}

