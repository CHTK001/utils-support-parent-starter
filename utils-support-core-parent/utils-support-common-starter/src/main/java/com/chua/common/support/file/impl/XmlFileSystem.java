package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * XML 文件系统 SPI 实现（简易 DOM 解析）。
 *
 * <p>通过 SPI 机制注册为 {@code "xml"} 类型的文件系统实现。
 * 使用 JDK 内置解析方式实现简易 XML 的读取与生成，适用于结构简单的配置文件。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("xml")
public class XmlFileSystem implements FileSystem {

    @Override
    public String getType() {
        return "xml";
    }

    @Override
    public ReadBuilder read(File file) {
        return new XmlReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new XmlWriteBuilder(file);
    }

    /**
     * XML 文件读取构建器。
     *
 * @author CH
     * @since 1.0.0
     */
    public static class XmlReadBuilder extends ReadBuilder {

        private boolean hasHeader;

        XmlReadBuilder(File file) {
            super(file);
        }

        @Override
        public XmlReadBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        /** 标记首条记录为表头（属性名作为列名） */
        public XmlReadBuilder withHeader() {
            this.hasHeader = true;
            return this;
        }

        /**
         * 以表格形式读取 XML（{@code <root><row>...}），每条 row 为一条记录。
         *
         * @return Map 行数据列表
         */
@SuppressWarnings({"unchecked"})
        public List<Map<String, Object>> rows() {
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                content = content.replaceAll("<\\?[^>]+\\?>", "").trim();

                // 查找 <root><row> 结构
                int rowStart = content.indexOf("<row>");
                int rowEnd;
                while (rowStart >= 0) {
                    rowEnd = content.indexOf("</row>", rowStart);
                    if (rowEnd < 0) break;
                    String inner = content.substring(rowStart + 5, rowEnd).trim();
                    Map<String, Object> row = new LinkedHashMap<>();
                    parseXml(inner, row);
                    result.add(row);
                    rowStart = content.indexOf("<row>", rowEnd + 6);
                }

                if (result.isEmpty()) {
                    // 回退：单层结构
                    Map<String, Object> single = toMap();
                    if (!single.isEmpty()) result.add(single);
                }

                if (!result.isEmpty() && callback != null) {
                    List<String> headers = new ArrayList<>(result.get(0).keySet());
                    callback.onHeader(headers);
                    for (Map<String, Object> row : result) {
                        if (columnMapping != null) {
                            Map<String, Object> mapped = new LinkedHashMap<>();
                            for (Map.Entry<String, Object> e : row.entrySet()) {
                                mapped.put(columnMapping.getOrDefault(e.getKey(), e.getKey()), e.getValue());
                            }
                            callback.onBody(mapped);
                        } else {
                            callback.onBody(row);
                        }
                    }
                    callback.onComplete(result.size());
                }
            } catch (Exception ignored) {}
            // 应用行过滤 + 行数据转换
            result = applyFilter(result);
            result = applyRowMapping(result);
            return result;
        }

        /**
         * 读取 XML 文件并返回 Map 结构。
         *
         * @return Map 格式的数据
         */
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                content = content.replaceAll("<\\?[^>]+\\?>", "").trim();
                parseXml(content, result);
            } catch (IOException e) {
                // 读取失败时返回空 Map
            }
            if (callback != null) {
                callback.onBody(result);
                callback.onComplete(1);
            }
            return result;
        }

        @Override
        public Object read() {
            return rows();
        }

        /**
         * 简易 XML 解析器，将标签结构转为 Map。
         */
        private void parseXml(String xml, Map<String, Object> map) {
            int pos = 0;
            int len = xml.length();
            while (pos < len) {
                int tagStart = xml.indexOf('<', pos);
                if (tagStart < 0) {
                    break;
                }
                int tagEnd = xml.indexOf('>', tagStart);
                if (tagEnd < 0) {
                    break;
                }
                String tag = xml.substring(tagStart + 1, tagEnd).trim().split("\\s+")[0];
                if (tag.startsWith("/")) {
                    break;
                }
                int closeTag = xml.indexOf("</" + tag + ">", tagEnd);
                if (closeTag < 0) {
                    break;
                }
                String inner = xml.substring(tagEnd + 1, closeTag).trim();
                if (!inner.contains("<")) {
                    map.put(tag, inner);
                } else {
                    Map<String, Object> child = new LinkedHashMap<>();
                    parseXml(inner, child);
                    map.put(tag, child);
                }
                pos = closeTag + tag.length() + 3;
            }
        }
    }

    /**
     * XML 文件写入构建器。
     *
 * @author CH
     * @since 1.0.0
     */
    public static class XmlWriteBuilder extends WriteBuilder {

        XmlWriteBuilder(File file) {
            super(file);
        }

        @Override
        public XmlWriteBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        @Override
        public XmlWriteBuilder write(Object data) {
            if (data instanceof Map) {
                pending.add(data);
            } else if (data instanceof List) {
                for (Object item : (List<?>) data) {
                    if (item instanceof Map) {
                        pending.add(item);
                    } else {
                        pending.add(toMap(item));
                    }
                }
            } else {
                pending.add(toMap(data));
            }
            return this;
        }

        public XmlWriteBuilder write(Map<String, Object> data) {
            pending.add(data);
            return this;
        }

        @Override
        public void finish() {
            callback.onStart();
            callback.onBeginWrite();
            try {
                String xml = renderXml();
                byte[] bytes = xml.getBytes(charset);
                java.nio.file.Files.write(file.toPath(), bytes);
                callback.onProgress(bytes.length, bytes.length);
                callback.onComplete(true);
            } catch (IOException e) {
                callback.onComplete(false);
            }
        }

        private String renderXml() {
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n");
            for (Object data : pending) {
                if (data instanceof Map) {
                    Map<String, Object> row = (Map<String, Object>) data;
                    // 写入行过滤
                    if (!testRow(row)) continue;
                    renderRow(sb, row, 1);
                }
            }
            sb.append("</root>\n");
            return sb.toString();
        }

        private void renderRow(StringBuilder sb, Map<String, Object> row, int depth) {
            String indent = "  ".repeat(depth);
            sb.append(indent).append("<row>\n");
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sb.append(indent).append("  <").append(entry.getKey()).append('>')
                        .append(entry.getValue()).append("</").append(entry.getKey()).append(">\n");
            }
            sb.append(indent).append("</row>\n");
        }


    }
}