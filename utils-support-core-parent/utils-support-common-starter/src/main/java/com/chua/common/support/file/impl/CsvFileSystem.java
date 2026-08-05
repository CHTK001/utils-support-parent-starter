package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 文件系统 SPI 实现。
 *
 * <p>通过 SPI 机制注册为 {@code "csv"} 类型的文件系统实现。
 * 使用 JDK 内置 IO 流实现 CSV 格式的解析与生成，不依赖第三方库。
 * 支持分隔符配置、表头识别、引号转义等标准 CSV 特性。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("csv")
public class CsvFileSystem implements FileSystem {

    @Override
    public String getType() {
        return "csv";
    }

    @Override
    public ReadBuilder read(File file) {
        return new CsvReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new CsvWriteBuilder(file);
    }

    /**
     * CSV 文件读取构建器。
     *
 * @author CH
     * @since 1.0.0
     */
    public static class CsvReadBuilder extends ReadBuilder {

        /** 字段分隔符，默认逗号 */
        private char delimiter = ',';

        CsvReadBuilder(File file) {
            super(file);
        }

        /**
         * 设置字段分隔符。
         *
         * @param delimiter 分隔符字符
         * @return 当前构建器
         */
        public CsvReadBuilder withDelimiter(char delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        @Override
        public CsvReadBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        /**
         * 读取全部行并返回 Map 列表。
         *
         * <p>当 {@link #withHeader()} 启用时，Map 的 key 为列名。</p>
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
                    String[] parts = parseLine(line, delimiter);
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
                    Map<String, Object> rowMap = new LinkedHashMap<>();
                    for (int i = 0; i < parts.length; i++) {
                        String col = (hasHeader && i < headerRow.size()) ? headerRow.get(i) : "col" + i;
                        String key = (columnMapping != null) ? columnMapping.getOrDefault(col, col) : col;
                        rowMap.put(key, parts[i].trim());
                    }
                    result.add(rowMap);
                    if (callback != null) {
                        callback.onBody(rowMap);
                    }
                }
            } catch (IOException e) {
                // 读取失败时返回空列表
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
        public Object read() {
            return rows();
        }

        /**
         * 解析 CSV 一行数据，支持双引号包裹字段内含分隔符的场景。
         *
         * @param line      CSV 行文本
         * @param delimiter 字段分隔符
         * @return 解析后的字段数组
         */
        private String[] parseLine(String line, char delimiter) {
            List<String> parts = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean inQuotes = false;
            for (char c : line.toCharArray()) {
                if (c == '"') {
                    inQuotes = !inQuotes;
                    continue;
                }
                if (c == delimiter && !inQuotes) {
                    parts.add(sb.toString());
                    sb.setLength(0);
                    continue;
                }
                sb.append(c);
            }
            parts.add(sb.toString());
            return parts.toArray(new String[0]);
        }
    }

    /**
     * CSV 文件写入构建器。
     *
 * @author CH
     * @since 1.0.0
     */
    public static class CsvWriteBuilder extends WriteBuilder {

        private char delimiter = ',';

        private String[] header;

        CsvWriteBuilder(File file) {
            super(file);
        }

        public CsvWriteBuilder withHeader(String... header) {
            this.header = header;
            return this;
        }

        public CsvWriteBuilder withDelimiter(char delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        @Override
        public CsvWriteBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        @Override
        public CsvWriteBuilder write(Object data) {
            if (data instanceof Map || data instanceof List) {
                pending.add(data);
            } else {
                pending.add(toMapList(data));
            }
            return this;
        }

        public CsvWriteBuilder write(List<Map<String, Object>> rows) {
            pending.add(rows);
            return this;
        }

        public void writeAndFlush(List<Map<String, Object>> rows) {
            callback.onStart();
            callback.onBeginWrite();
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), charset))) {
                List<String> cols = headerColumns;
                if (cols == null && !rows.isEmpty()) {
                    cols = new ArrayList<>(rows.get(0).keySet());
                }
                long written = 0;
                if (withHeader && cols != null && !cols.isEmpty()) {
                    String headerLine = String.join(String.valueOf(delimiter), cols);
                    writer.write(headerLine);
                    writer.newLine();
                    written += headerLine.getBytes(charset).length + 1;
                }
                if (cols != null) {
                    for (Map<String, Object> row : rows) {
                        String line = String.join(
                                String.valueOf(delimiter),
                                cols.stream().map(c -> row.getOrDefault(c, "").toString()).toList());
                        writer.write(line);
                        writer.newLine();
                        written += line.getBytes(charset).length + 1;
                        callback.onProgress((int) written, (int) written);
                    }
                }
                callback.onComplete(true);
            } catch (IOException e) {
                callback.onComplete(false);
            }
        }

        @Override
        public void finish() {
            callback.onStart();
            callback.onBeginWrite();
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), charset))) {
                List<String> cols = resolveColumns();
                boolean headerWritten = false;
                long written = 0;
                for (Object entry : pending) {
                    if (entry instanceof List) {
                        List<?> list = (List<?>) entry;
                        if (list.isEmpty()) {
                            continue;
                        }
                        if (list.get(0) instanceof Map) {
                            if (withHeader && !headerWritten && cols != null) {
                                String headerLine = String.join(String.valueOf(delimiter), cols);
                                writer.write(headerLine);
                                writer.newLine();
                                written += headerLine.getBytes(charset).length + 1;
                                headerWritten = true;
                            }
                            for (Object rowObj : list) {
@SuppressWarnings("unchecked")
                                Map<String, Object> row = (Map<String, Object>) rowObj;
                                // 写入行过滤
                                if (!testRow(row)) continue;
                                String line = String.join(String.valueOf(delimiter),
                                        cols.stream()
                                                .map(c -> {
                                                    Object v = row.get(c);
                                                    return v == null ? "" : v.toString();
                                                })
                                                .toList());
                                writer.write(line);
                                writer.newLine();
                                written += line.getBytes(charset).length + 1;
                            }
                        }
                    }
                }
                callback.onProgress((int) written, (int) written);
                callback.onComplete(true);
            } catch (IOException e) {
                callback.onComplete(false);
            }
        }

        private List<String> resolveColumns() {
            if (headerColumns != null) {
                return headerColumns;
            }
            for (Object entry : pending) {
                if (entry instanceof List) {
                    List<?> list = (List<?>) entry;
                    if (!list.isEmpty() && list.get(0) instanceof Map) {
                        Map<String, Object> first = (Map<String, Object>) list.get(0);
                        return new ArrayList<>(first.keySet());
                    }
                }
            }
            return null;
        }
    }
}