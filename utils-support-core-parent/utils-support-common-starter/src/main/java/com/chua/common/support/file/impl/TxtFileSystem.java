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
import org.jspecify.annotations.NullUnmarked;

/**
 * TXT 纯文本文件系统 SPI 实现。
 *
 * <p>通过 SPI 机制注册为 {@code "txt"} 类型的文件系统实现。
 * 使用 JDK 内置 IO 流实现纯文本文件的逐行读取与写入。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@Spi("txt")
public class TxtFileSystem implements FileSystem {

    @Override
    public String getType() {
        return "txt";
    }

    @Override
    public ReadBuilder read(File file) {
        return new TxtReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new TxtWriteBuilder(file);
    }

    /**
     * TXT 文件读取构建器。
     *
 * @author CH
     * @since 1.0.0
     */
    public static class TxtReadBuilder extends ReadBuilder {

        private char delimiter = '\t';

        TxtReadBuilder(File file) {
            super(file);
        }

        @Override
        public TxtReadBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        /** 设置分隔符（默认 TAB） */
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
                        String col = (hasHeader && i < headerRow.size()) ? headerRow.get(i) : "col" + i;
                        String key = (columnMapping != null) ? columnMapping.getOrDefault(col, col) : col;
                        map.put(key, parts[i].trim());
                    }
                    result.add(map);
                    if (callback != null) {
                        callback.onBody(map);
                    }
                }
            } catch (IOException ignored) {}
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
            return hasHeader ? rows() : lines();
        }

        @Override
        public String asString() {
            return String.join("\n", lines());
        }
    }

    /**
     * TXT 文件写入构建器。
     *
 * @author CH
     * @since 1.0.0
     */
    public static class TxtWriteBuilder extends WriteBuilder {

        TxtWriteBuilder(File file) {
            super(file);
        }

        @Override
        public TxtWriteBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        @Override
@SuppressWarnings({"unchecked"})
        public TxtWriteBuilder write(Object data) {
            if (data instanceof Map || data instanceof List) {
                pending.add(data);
            } else {
                pending.add(toMapList(data));
            }
            return this;
        }

        public TxtWriteBuilder write(List<String> lines) {
            pending.addAll(lines);
            return this;
        }

        @Override
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
                callback.onComplete(false);
            }
        }

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

        private boolean txtHeaderDone;
        private List<String> txtHeaderCols;

        private void appendMapRow(List<String> result, Map<String, Object> map) {
            // 写入行过滤
            if (!testRow(map)) return;
            if (!txtHeaderDone) {
                txtHeaderCols = new ArrayList<>(map.keySet());
                if (withHeader) {
                    result.add(String.join("\t", txtHeaderCols));
                }
                txtHeaderDone = true;
            }
            List<String> row = new ArrayList<>();
            for (String col : txtHeaderCols) {
                Object val = map.get(col);
                row.add(val != null ? val.toString() : "");
            }
            result.add(String.join("\t", row));
        }
    }
}