package com.chua.tencent.support.auth;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.datasource.support.engine.FileEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支持 Enum 类型转换的 FileEngine 子类
 * <p>
 * 解决 FileEngine 的 mapToEntity 不支持 String -> Enum 转换的问题。
 * 通过 Jackson 进行反序列化，确保 Enum 类型正确转换。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WechatFileEngine extends FileEngine {

    /** JSON 对象映射器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 已加载文件映射 */
    private final Map<String, File> loadedFiles = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public <T> FileEngine load(String name, File file, String type) {
        loadedFiles.put(name, file);
        return super.load(name, file, type);
    }

    /**
     * 使用 Jackson 重新加载数据，支持 Enum 转换
     * <p>
     * FileEngine 保存的 JSON 格式为二维数组：[["col1","col2",...],[val1,val2,...],...]
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> loadWithJackson(String name, Class<T> entityClass) {
        File file = loadedFiles.get(name);
        if (file == null || !file.exists()) {
            return new ArrayList<>();
        }

        try {
            String content = java.nio.file.Files.readString(file.toPath());
            if (content == null || content.isBlank() || "[]".equals(content.trim())) {
                return new ArrayList<>();
            }

            List<?> raw = OBJECT_MAPPER.readValue(content, List.class);
            if (CollectionUtils.isEmpty(raw)) {
                return new ArrayList<>();
            }

            // 检查是否为二维数组格式（FileEngine 默认格式）
            if (raw.get(0) instanceof List) {
                List<List<?>> rows = (List<List<?>>) raw;
                if (rows.size() < 2) {
                    return new ArrayList<>();
                }

                // 第一行为列名
                List<?> headerRow = rows.get(0);
                List<String> columns = new ArrayList<>();
                for (Object col : headerRow) {
                    columns.add(col != null ? col.toString() : null);
                }

                // 后续行为数据
                List<T> result = new ArrayList<>();
                for (int i = 1; i < rows.size(); i++) {
                    List<?> row = rows.get(i);
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int j = 0; j < columns.size() && j < row.size(); j++) {
                        map.put(columns.get(j), row.get(j));
                    }
                    T entity = OBJECT_MAPPER.convertValue(map, entityClass);
                    result.add(entity);
                }
                return result;
            }

            // 如果是标准 JSON 对象数组
            List<Map<String, Object>> maps = (List<Map<String, Object>>) raw;
            List<T> result = new ArrayList<>(maps.size());
            for (Map<String, Object> map : maps) {
                T entity = OBJECT_MAPPER.convertValue(map, entityClass);
                result.add(entity);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Jackson 反序列化失败: " + file.getName(), e);
        }
    }
}
