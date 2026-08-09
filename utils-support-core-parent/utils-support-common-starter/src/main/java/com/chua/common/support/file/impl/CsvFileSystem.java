package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.ObjectUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
 * @since 4.0.0.42
 */
@Spi("csv")
public class CsvFileSystem implements FileSystem {

    /**
     * CSV 文件类型标识
     */
    private static final String TYPE_CSV = "csv";

    /**
     * 默认字段分隔符（逗号）
     */
    private static final char DEFAULT_DELIMITER = ',';

    /**
     * 无表头时生成的列名前缀
     */
    private static final String COLUMN_KEY_PREFIX = "col";

    /**
     * CSV 双引号字符
     */
    private static final char QUOTE_CHAR = '"';

    /**
     * 空值占位字符串
     */
    private static final String EMPTY_VALUE = "";

    /**
     * 读取每行默认的列数估算（仅用于预分配 Map 容量，避免初始扩容）
     */
    private static final int ESTIMATED_COLUMN_COUNT = 8;

    @Override
    public String getType() {
        return TYPE_CSV;
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
     * @since 4.0.0.42
     */
    @Slf4j
    public static class CsvReadBuilder extends ReadBuilder {

        /**
         * 字段分隔符，默认逗号
         */
        private char delimiter = DEFAULT_DELIMITER;

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
            List<Map<String, Object>> result = CollectionUtils.newArrayList();
            List<String> headerRow = CollectionUtils.newArrayList();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), charset))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (StringUtils.isBlank(line)) {
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
                    Map<String, Object> rowMap = new LinkedHashMap<>(ESTIMATED_COLUMN_COUNT);
                    for (int i = 0; i < parts.length; i++) {
                        String col = (hasHeader && i < headerRow.size()) ? headerRow.get(i) : COLUMN_KEY_PREFIX + i;
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
                log.warn("CSV 文件读取失败: {}", file.getAbsolutePath(), e);
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
            List<String> parts = CollectionUtils.newArrayList();
            StringBuilder sb = new StringBuilder();
            boolean inQuotes = false;
            for (char c : line.toCharArray()) {
                if (c == QUOTE_CHAR) {
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
     * @since 4.0.0.42
     */
    @Slf4j
    public static class CsvWriteBuilder extends WriteBuilder {

        /**
         * 字段分隔符，默认逗号
         */
        private char delimiter = DEFAULT_DELIMITER;

        /**
         * 显式指定的表头列（与 {@link #headerColumns} 二选一）
         */
        private String[] header;

        CsvWriteBuilder(File file) {
            super(file);
        }

        /**
         * 设置显式表头列。
         *
         * @param header 表头列数组
         * @return 当前构建器
         */
        public CsvWriteBuilder withHeader(String... header) {
            this.header = header;
            return this;
        }

        /**
         * 设置字段分隔符。
         *
         * @param delimiter 分隔符字符
         * @return 当前构建器
         */
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

        /**
         * 显式写入一组 Map 行。
         *
         * @param rows 行数据列表
         * @return 当前构建器
         */
        public CsvWriteBuilder write(List<Map<String, Object>> rows) {
            pending.add(rows);
            return this;
        }

        /**
         * 立即将指定行写入磁盘并刷新。
         *
         * @param rows 行数据列表
         */
        public void writeAndFlush(List<Map<String, Object>> rows) {
            callback.onStart();
            callback.onBeginWrite();
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), charset))) {
                List<String> cols = headerColumns;
                if (cols == null && !rows.isEmpty()) {
                    cols = CollectionUtils.newArrayList(rows.get(0).keySet());
                }
                long written = 0L;
                if (withHeader && cols != null && !cols.isEmpty()) {
                    String headerLine = String.join(String.valueOf(delimiter), cols);
                    writer.write(headerLine);
                    writer.newLine();
                    // 写入字节数加上换行符字节数
                    written += headerLine.getBytes(charset).length + 1;
                }
                if (cols != null) {
                    for (Map<String, Object> row : rows) {
                        String line = String.join(
                                String.valueOf(delimiter),
                                cols.stream().map(c -> row.getOrDefault(c, EMPTY_VALUE).toString()).toList());
                        writer.write(line);
                        writer.newLine();
                        // 写入字节数加上换行符字节数
                        written += line.getBytes(charset).length + 1;
                        callback.onProgress((int) written, (int) written);
                    }
                }
                callback.onComplete(true);
            } catch (IOException e) {
                log.warn("CSV 文件写入失败: {}", file.getAbsolutePath(), e);
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
                long written = 0L;
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
                                // 写入字节数加上换行符字节数
                                written += headerLine.getBytes(charset).length + 1;
                                headerWritten = true;
                            }
                            for (Object rowObj : list) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> row = (Map<String, Object>) rowObj;
                                // 行过滤：未通过的行直接跳过
                                if (!testRow(row)) {
                                    continue;
                                }
                                String line = String.join(String.valueOf(delimiter),
                                        cols.stream()
                                                .map(c -> {
                                                    Object v = row.get(c);
                                                    return v == null ? EMPTY_VALUE : v.toString();
                                                })
                                                .toList());
                                writer.write(line);
                                writer.newLine();
                                // 写入字节数加上换行符字节数
                                written += line.getBytes(charset).length + 1;
                            }
                        }
                    }
                }
                callback.onProgress((int) written, (int) written);
                callback.onComplete(true);
            } catch (IOException e) {
                log.warn("CSV 文件写入失败: {}", file.getAbsolutePath(), e);
                callback.onComplete(false);
            }
        }

        /**
         * 解析最终用于写入的列顺序：优先使用 {@link #headerColumns}，否则从首行 Map 的 keySet 推断。
         *
         * @return 列名列表，无法推断时返回 null
         */
        private List<String> resolveColumns() {
            if (headerColumns != null) {
                return headerColumns;
            }
            for (Object entry : pending) {
                if (entry instanceof List) {
                    List<?> list = (List<?>) entry;
                    if (!list.isEmpty() && list.get(0) instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> first = (Map<String, Object>) list.get(0);
                        return CollectionUtils.newArrayList(first.keySet());
                    }
                }
            }
            return null;
        }
    }
}
