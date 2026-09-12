package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* JSON 文件系统 SPI 实现。
*
* <p>通过 SPI 机制注册为 {@code "json"} 类型的文件系统实现。
* 支持表格格式 {@code [[header,...],[val,...],...]}（首行为表头）及传统对象格式。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("json")
public class JsonFileSystem implements FileSystem {

    /**
    * JSON 文件类型标识
     */
    private static final String TYPE_JSON = "json";

    /**
    * 表头为空时的占位符
     */
    private static final String EMPTY_HEADER_PLACEHOLDER = "";

    /**
    * 写入完成行数（单对象写入）
     */
    private static final int SINGLE_OBJECT_ROW_COUNT = 1;

    /**
    * 表格行数（无数据）
     */
    private static final int EMPTY_RESULT_ROW_COUNT = 0;

    @Override
    /** 获取Type */
    public String getType() {
        return TYPE_JSON;
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new JsonReadBuilder(file);
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        return new JsonWriteBuilder(file);
    }

    /**
    * JSON 文件读取构建器。
    *
    * @since 4.0.0.42
     */
    public static class JsonReadBuilder extends ReadBuilder {

        JsonReadBuilder(File file) {
            super(file);
        }

        @Override
        /** WithCharset */
        public JsonReadBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        /**
        * 以表格形式读取 JSON（{@code [[header,...],[val,...],...]}），首行为表头。
        *
        * @return Map 行数据列表
         */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> rows() {
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                List<?> raw = Json.toList(content);
                if (raw == null || raw.isEmpty()) {
                    if (callback != null) {
                        callback.onComplete(EMPTY_RESULT_ROW_COUNT);
                    }
                    return result;
                }
                if (raw.get(0) instanceof Map) {
                    /* 对象数组形态：[{"k":v,...},...]，每个元素即一行 */
                    for (Object item : raw) {
                        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) item);
                        result.add(map);
                        if (callback != null) {
                            callback.onBody(map);
                        }
                    }
                    if (callback != null) {
                        callback.onComplete(result.size());
                    }
                    result = applyFilter(result);
                    result = applyRowMapping(result);
                    return result;
                }
                // 二维数组形态：[[header,...],[val,...],...]，首行为表头
                List<Object> headerRow = (List<Object>) raw.get(0);
                List<String> headers = new ArrayList<>();
                for (Object h : headerRow) {
                    headers.add(h == null ? EMPTY_HEADER_PLACEHOLDER : h.toString());
                }
                if (callback != null) {
                    callback.onHeader(headers);
                }
                // 后续行为数据
                for (int i = 1; i < raw.size(); i++) {
                    List<Object> row = (List<Object>) raw.get(i);
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int c = 0; c < headers.size() && c < row.size(); c++) {
                        String key = (columnMapping != null)
                                ? columnMapping.getOrDefault(headers.get(c), headers.get(c))
                                : headers.get(c);
                        map.put(key, row.get(c));
                    }
                    result.add(map);
                    if (callback != null) {
                        callback.onBody(map);
                    }
                }
                if (callback != null) {
                    callback.onComplete(result.size());
                }
            } catch (Exception e) {
                /* 解析失败必须可观测，禁止静默返回空集 */
                org.slf4j.LoggerFactory.getLogger(JsonFileSystem.class)
                        .warn("JSON 文件解析失败: {}", file.getName(), e);
                if (callback != null) {
                    callback.onComplete(EMPTY_RESULT_ROW_COUNT);
                }
            }
            // 应用过滤 + 行映射转换
            result = applyFilter(result);
            result = applyRowMapping(result);
            return result;
        }

        /**
        * 读取 JSON 文件并返回 Map 格式。
        *
        * @return Map 格式的数据
         */
        public Map<String, Object> toMap() {
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                Map<String, Object> result = Json.fromJson(content);
                if (callback != null) {
                    callback.onBody(result);
                    callback.onComplete(SINGLE_OBJECT_ROW_COUNT);
                }
                return result;
            } catch (IOException e) {
                return Map.of();
            }
        }

        /**
        * 反序列化为指定类型。
        *
        * @param clazz 目标类型
        * @param <T>   泛型
        * @return 对象实例
         */
        public <T> T toObject(Class<T> clazz) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                T result = Json.fromJson(content, clazz);
                if (callback != null) {
                    callback.onBody(result);
                    callback.onComplete(SINGLE_OBJECT_ROW_COUNT);
                }
                return result;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        /** 读取 */
        public Object read() {
            return rows();
        }
    }

    /**
    * JSON 文件写入构建器。
    *
    * @since 4.0.0.42
     */
    public static class JsonWriteBuilder extends WriteBuilder {

        /**
        * 是否使用美化格式输出
         */
        private boolean pretty;

        JsonWriteBuilder(File file) {
            super(file);
        }

        /**
        * 启用美化格式输出。
        *
        * @return 当前构建器
         */
        public JsonWriteBuilder withPretty() {
            this.pretty = true;
            return this;
        }

        @Override
        /** WithCharset */
        public JsonWriteBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        @Override
        /** 写入 */
        public JsonWriteBuilder write(Object data) {
            if (data instanceof Map || data instanceof List) {
                pending.add(data);
            } else {
                pending.add(toMapList(data));
            }
            return this;
        }

        /**
        * 立即写入数据并刷新。
        *
        * @param object 待写入对象
         */
        public void writeAndFlush(Object object) {
            callback.onStart();
            callback.onBeginWrite();
            try {
                Object data = (object instanceof List) ? toTableData((List<Object>) object) : object;
                String json = toJsonString(data);
                byte[] bytes = json.getBytes(charset);
                Files.write(file.toPath(), bytes);
                callback.onProgress(bytes.length, bytes.length);
                callback.onComplete(true);
            } catch (IOException e) {
                callback.onComplete(false);
            }
        }

        @Override
        /** Finish */
        public void finish() {
            callback.onStart();
            callback.onBeginWrite();
            try {
                Object data;
                if (pending.size() == 1) {
                    data = pending.get(0);
                } else {
                    data = pending;
                }
                // List<Map> → [[header,...],[val,...],...] 表格格式
                if (data instanceof List) {
                    data = toTableData((List<Object>) data);
                }
                String json = toJsonString(data);
                byte[] bytes = json.getBytes(charset);
                Files.write(file.toPath(), bytes);
                callback.onProgress(bytes.length, bytes.length);
                callback.onComplete(true);
            } catch (IOException e) {
                callback.onComplete(false);
            }
        }

        /**
        * 将列表转为表格格式。
        *
        * @param list 待转换的列表
        * @return 表格数据（二维列表）
         */
        private Object toTableData(List<Object> list) {
            if (list.isEmpty()) {
                return list;
            }
            // 检测是否为 List<Map> 格式
            List<List<Object>> table = new ArrayList<>();
            List<String> headers = null;
            for (Object item : list) {
                if (item instanceof List) {
                    // 已经是二维数组，直接返回
                    return list;
                } else if (item instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) item;
                    // 写入行过滤
                    if (!testRow(map)) {
                        continue;
                    }
                    if (headers == null) {
                        headers = new ArrayList<>(map.keySet());
                        List<Object> headerRow = new ArrayList<>(headers);
                        table.add(headerRow);
                    }
                    List<Object> dataRow = new ArrayList<>();
                    for (String h : headers) {
                        String key = (columnMapping != null) ? columnMapping.getOrDefault(h, h) : h;
                        dataRow.add(map.get(h));
                    }
                    table.add(dataRow);
                } else {
                    return list;
                }
            }
            return table;
        }

        /**
        * 将对象转为 JSON 字符串。
        *
        * @param data 待序列化对象
        * @return JSON 字符串
         */
        private String toJsonString(Object data) {
            if (pretty) {
                return Json.prettyFormat(data);
            }
            return Json.toJson(data);
        }
    }
}
