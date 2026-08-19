package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XML 文件系统 SPI 实现（简易 DOM 解析）。
 *
 * <p>通过 SPI 机制注册为 {@code "xml"} 类型的文件系统实现。
 * 使用 JDK 内置解析方式实现简易 XML 的读取与生成，适用于结构简单的配置文件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("xml")
public class XmlFileSystem implements FileSystem {

    /**
     * XML 文件类型标识
     */
    private static final String TYPE_XML = "xml";

    /**
     * 行标签起始
     */
    private static final String ROW_START_TAG = "<row>";

    /**
     * 行标签结束
     */
    private static final String ROW_END_TAG = "</row>";

    /**
     * 行标签起始长度
     */
    private static final int ROW_START_TAG_LENGTH = ROW_START_TAG.length();

    /**
     * 行标签结束长度
     */
    private static final int ROW_END_TAG_LENGTH = ROW_END_TAG.length();

    /**
     * XML 处理指令正则
     */
    private static final String XML_DECLARATION_PATTERN = "<\\?[^>]+\\?>";

    /**
     * 标签起始字符
     */
    private static final char TAG_START_CHAR = '<';

    /**
     * 标签结束字符
     */
    private static final char TAG_END_CHAR = '>';

    /**
     * 结束标签前缀
     */
    private static final String CLOSE_TAG_PREFIX = "</";

    /**
     * 结束标签后缀
     */
    private static final String CLOSE_TAG_SUFFIX = ">";

    /**
     * 结束标签前缀长度
     */
    private static final int CLOSE_TAG_PREFIX_LENGTH = CLOSE_TAG_PREFIX.length();

    /**
     * 空白字符分隔正则
     */
    private static final String WHITESPACE_PATTERN = "\\s+";

    /**
     * XML 声明头
     */
    private static final String XML_DECLARATION_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n";

    /**
     * 根结束标签
     */
    private static final String ROOT_END_TAG = "</root>\n";

    /**
     * 行开始标签模板
     */
    private static final String ROW_OPEN_TAG_TEMPLATE = "<row>\n";

    /**
     * 行结束标签模板
     */
    private static final String ROW_CLOSE_TAG_TEMPLATE = "</row>\n";

    /**
     * 字段开始标签模板
     */
    private static final String FIELD_OPEN_TAG_TEMPLATE = "  <";

    /**
     * 字段结束标签模板
     */
    private static final String FIELD_CLOSE_TAG_TEMPLATE = ">";

    /**
     * 字段闭合标签模板
     */
    private static final String FIELD_END_TAG_TEMPLATE = "</";

    /**
     * 字段换行模板
     */
    private static final String FIELD_NEWLINE_TEMPLATE = ">\n";

    /**
     * 单层缩进
     */
    private static final String INDENT_UNIT = "  ";

    /**
     * 标签后偏移量（标签结束符位置 + 1）
     */
    private static final int TAG_END_OFFSET = 1;

    /**
     * 关闭标签总偏移量（关闭标签前缀长度 + 标签名长度 + 关闭标签后缀长度）
     */
    private static final int CLOSE_TAG_TOTAL_EXTRA = CLOSE_TAG_PREFIX_LENGTH + CLOSE_TAG_SUFFIX.length();

    @Override
    /** 获取Type */
    public String getType() {
        return TYPE_XML;
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new XmlReadBuilder(file);
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        return new XmlWriteBuilder(file);
    }

    /**
     * XML 文件读取构建器。
     *
     * @since 4.0.0.42
     */
    public static class XmlReadBuilder extends ReadBuilder {

        /**
         * 是否将首行作为表头
         */
        private boolean hasHeader;

        XmlReadBuilder(File file) {
            super(file);
        }

        @Override
        /** WithCharset */
        public XmlReadBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        /**
         * 标记首条记录为表头（属性名作为列名）。
         *
         * @return 当前构建器
         */
        public XmlReadBuilder withHeader() {
            this.hasHeader = true;
            return this;
        }

        /**
         * 以表格形式读取 XML（{@code <root><row>...}），每条 row 为一条记录。
         *
         * @return Map 行数据列表
         */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> rows() {
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                content = content.replaceAll(XML_DECLARATION_PATTERN, "").trim();

                // 查找 <root><row> 结构
                int rowStart = content.indexOf(ROW_START_TAG);
                int rowEnd;
                while (rowStart >= 0) {
                    rowEnd = content.indexOf(ROW_END_TAG, rowStart);
                    if (rowEnd < 0) {
                        break;
                    }
                    String inner = content.substring(rowStart + ROW_START_TAG_LENGTH, rowEnd).trim();
                    Map<String, Object> row = new LinkedHashMap<>();
                    parseXml(inner, row);
                    result.add(row);
                    rowStart = content.indexOf(ROW_START_TAG, rowEnd + ROW_END_TAG_LENGTH);
                }

                if (result.isEmpty()) {
                    // 回退：单层结构
                    Map<String, Object> single = toMap();
                    if (!single.isEmpty()) {
                        result.add(single);
                    }
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
            } catch (Exception ignored) {
            }
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
                String content = new String(Files.readAllBytes(file.toPath()));
                content = content.replaceAll(XML_DECLARATION_PATTERN, "").trim();
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
        /** 读取 */
        public Object read() {
            return rows();
        }

        /**
         * 简易 XML 解析器，将标签结构转为 Map。
         *
         * @param xml 待解析的 XML 片段
         * @param map 输出 Map 容器
         */
        private void parseXml(String xml, Map<String, Object> map) {
            int pos = 0;
            int len = xml.length();
            while (pos < len) {
                int tagStart = xml.indexOf(TAG_START_CHAR, pos);
                if (tagStart < 0) {
                    break;
                }
                int tagEnd = xml.indexOf(TAG_END_CHAR, tagStart);
                if (tagEnd < 0) {
                    break;
                }
                String tag = xml.substring(tagStart + TAG_END_OFFSET, tagEnd).trim().split(WHITESPACE_PATTERN)[0];
                if (tag.startsWith(CLOSE_TAG_PREFIX)) {
                    break;
                }
                int closeTag = xml.indexOf(CLOSE_TAG_PREFIX + tag + CLOSE_TAG_SUFFIX, tagEnd);
                if (closeTag < 0) {
                    break;
                }
                String inner = xml.substring(tagEnd + TAG_END_OFFSET, closeTag).trim();
                if (!inner.contains("<")) {
                    map.put(tag, inner);
                } else {
                    Map<String, Object> child = new LinkedHashMap<>();
                    parseXml(inner, child);
                    map.put(tag, child);
                }
                pos = closeTag + tag.length() + CLOSE_TAG_TOTAL_EXTRA;
            }
        }
    }

    /**
     * XML 文件写入构建器。
     *
     * @since 4.0.0.42
     */
    public static class XmlWriteBuilder extends WriteBuilder {

        XmlWriteBuilder(File file) {
            super(file);
        }

        @Override
        /** WithCharset */
        public XmlWriteBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        @Override
        /** 写入 */
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

        /**
         * 写入 Map 数据。
         *
         * @param data 待写入的 Map 数据
         * @return 当前构建器
         */
        public XmlWriteBuilder write(Map<String, Object> data) {
            pending.add(data);
            return this;
        }

        @Override
        /** Finish */
        public void finish() {
            callback.onStart();
            callback.onBeginWrite();
            try {
                String xml = renderXml();
                byte[] bytes = xml.getBytes(charset);
                Files.write(file.toPath(), bytes);
                callback.onProgress(bytes.length, bytes.length);
                callback.onComplete(true);
            } catch (IOException e) {
                callback.onComplete(false);
            }
        }

        /**
         * 渲染 XML 字符串。
         *
         * @return 渲染后的 XML 字符串
         */
        private String renderXml() {
            StringBuilder sb = new StringBuilder(XML_DECLARATION_HEADER);
            for (Object data : pending) {
                if (data instanceof Map) {
                    Map<String, Object> row = (Map<String, Object>) data;
                    // 写入行过滤
                    if (!testRow(row)) {
                        continue;
                    }
                    renderRow(sb, row, 1);
                }
            }
            sb.append(ROOT_END_TAG);
            return sb.toString();
        }

        /**
         * 渲染单行 Map 为 XML。
         *
         * @param sb    StringBuilder 输出
         * @param row   行数据
         * @param depth 缩进深度
         */
        private void renderRow(StringBuilder sb, Map<String, Object> row, int depth) {
            String indent = INDENT_UNIT.repeat(depth);
            sb.append(indent).append(ROW_OPEN_TAG_TEMPLATE);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sb.append(indent)
                        .append(FIELD_OPEN_TAG_TEMPLATE)
                        .append(entry.getKey())
                        .append(FIELD_CLOSE_TAG_TEMPLATE)
                        .append(entry.getValue())
                        .append(FIELD_END_TAG_TEMPLATE)
                        .append(entry.getKey())
                        .append(FIELD_NEWLINE_TEMPLATE);
            }
            sb.append(indent).append(ROW_CLOSE_TAG_TEMPLATE);
        }


    }
}
