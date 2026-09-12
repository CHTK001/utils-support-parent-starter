package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* TXT 纯文本文件系统 SPI 实现。
*
* <p>通过 SPI 机制注册为 {@code "txt"} 类型的文件系统实现。
* 使用 JDK 内置 IO 流实现纯文本文件的逐行读取与写入。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("txt")
public class TxtFileSystem implements FileSystem {

    /**
    * 文本读取默认分隔符（Tab 字符）
     */
    private static final char DEFAULT_DELIMITER = '\t';

    /**
    * 单行行内字段缺失表头时的列名前缀
     */
    private static final String COLUMN_KEY_PREFIX = "col";

    /**
    * 写入时使用的默认单元格分隔符（Tab 字符）
     */
    private static final String TAB_DELIMITER = "\t";

    /**
    * 多行拼接使用的换行符
     */
    private static final String LINE_SEPARATOR = "\n";

    @Override
    /** 获取Type */
    public String getType() {
        return "txt";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new TxtReadBuilder(file);
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        return new TxtWriteBuilder(file);
    }

    /**
    * TXT 文件读取构建器。
    *
    * @since 4.0.0.42
     */
    @Slf4j
    public static class TxtReadBuilder extends ReadBuilder {

        /**
        * 字段分隔符
         */
        private char delimiter = DEFAULT_DELIMITER;

        TxtReadBuilder(File file) {
            super(file);
        }

        @Override
        /** WithCharset */
        public TxtReadBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        /**
        * 设置分隔符（默认 TAB）。
        *
        * @param delimiter 分隔符字符
        * @return 当前构建器
         */
        public TxtReadBuilder withDelimiter(char delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        /**
        * 读取文件全部行。
        *
        * @return 行文本列表
         */
        public List<String> lines() {
            List<String> result = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.add(line);
                    if (callback != null) {
                        callback.onBody(line);
                    }
                }
            } catch (IOException e) {
                // 读取失败时返回空列表
                log.warn("read txt lines error: {}", e.getMessage());
            }
            if (callback != null) {
                callback.onComplete(result.size());
            }
            return result;
        }

        /**
        * 以表格形式读取（首行为表头，行数据转为 Map）。
        *
        * @return Map 行数据列表
         */
        public List<Map<String, Object>> rows() {
            List<Map<String, Object>> result = new ArrayList<>();
            List<String> headerRow = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), charset))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split(String.valueOf(delimiter));
                    if (first && hasHeader) {
                        for (String h : parts) {
                            headerRow.add(h.trim());
                        }
                        first = false;
                        if (callback != null) {
                            callback.onHeader(headerRow);
                        }
                        continue;
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < parts.length; i++) {
                        String col = (hasHeader && i < headerRow.size()) ? headerRow.get(i) : COLUMN_KEY_PREFIX + i;
                        String key = (columnMapping != null) ? columnMapping.getOrDefault(col, col) : col;
                        map.put(key, parts[i].trim());
                    }
                    result.add(map);
                    if (callback != null) {
                        callback.onBody(map);
                    }
                }
            } catch (IOException e) {
                // 读取失败时返回空列表
                log.warn("read txt rows error: {}", e.getMessage());
            }
            // 应用行过滤 + 行数据转换
            result = applyFilter(result);
            result = applyRowMapping(result);
            if (callback != null) {
                callback.onComplete(result.size());
            }
            return result;
        }

        @Override
        /** 读取 */
        public Object read() {
            return hasHeader ? rows() : lines();
        }

        @Override
        /** AsString */
        public String asString() {
            return String.join(LINE_SEPARATOR, lines());
        }
    }

    /**
    * TXT 文件写入构建器。
    *
    * @since 4.0.0.42
     */
    @Slf4j
    public static class TxtWriteBuilder extends WriteBuilder {

        /**
        * 是否已写入表头行
         */
        private boolean txtHeaderDone;

        /**
        * 表头列列表
         */
        private List<String> txtHeaderCols;

        TxtWriteBuilder(File file) {
            super(file);
        }

        @Override
        /** WithCharset */
        public TxtWriteBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        /** 写入 */
        public TxtWriteBuilder write(Object data) {
            if (data instanceof Map || data instanceof List) {
                pending.add(data);
            } else {
                pending.add(toMapList(data));
            }
            return this;
        }

        /**
        * 追加多行文本写入。
        *
        * @param lines 待写入的行列表
        * @return 当前构建器
         */
        public TxtWriteBuilder write(List<String> lines) {
            pending.addAll(lines);
            return this;
        }

        @Override
        /** Finish */
        public void finish() {
            txtHeaderDone = false;
            txtHeaderCols = new ArrayList<>();
            callback.onStart();
            callback.onBeginWrite();
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), charset))) {
                long written = 0;
                for (String line : resolveLines()) {
                    writer.write(line);
                    writer.newLine();
                    written += line.getBytes(charset).length + 1;
                }
                callback.onProgress((int) written, (int) written);
                callback.onComplete(true);
            } catch (IOException e) {
                // 写入失败时通过回调通知
                log.warn("write txt file error: {}", e.getMessage());
                callback.onComplete(false);
            }
        }

        /**
        * 将待写入数据解析为行字符串列表。
        *
        * @return 行字符串列表
         */
        private List<String> resolveLines() {
            List<String> result = new ArrayList<>();
            for (Object entry : pending) {
                if (entry instanceof Map) {
                    appendMapRow(result, (Map<String, Object>) entry);
                } else if (entry instanceof List) {
                    for (Object item : (List<?>) entry) {
                        if (item instanceof Map) {
                            appendMapRow(result, (Map<String, Object>) item);
                        } else if (item != null) {
                            result.add(item.toString());
                        }
                    }
                } else if (entry != null) {
                    result.add(entry.toString());
                }
            }
            return result;
        }

        /**
        * 追加一行 Map 数据；首行根据 {@link #withHeader} 决定是否写入表头。
        *
        * @param result 收集行字符串的列表
        * @param map    单行数据
         */
        private void appendMapRow(List<String> result, Map<String, Object> map) {
            // 应用行过滤谓词
            if (!testRow(map)) {
                return;
            }
            if (!txtHeaderDone) {
                txtHeaderCols = new ArrayList<>(map.keySet());
                if (withHeader) {
                    result.add(String.join(TAB_DELIMITER, txtHeaderCols));
                }
                txtHeaderDone = true;
            }
            List<String> row = new ArrayList<>();
            for (String col : txtHeaderCols) {
                Object val = map.get(col);
                row.add(val != null ? val.toString() : "");
            }
            result.add(String.join(TAB_DELIMITER, row));
        }
    }
}
