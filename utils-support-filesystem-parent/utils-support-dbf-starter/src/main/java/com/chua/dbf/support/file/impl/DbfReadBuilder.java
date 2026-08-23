package com.chua.dbf.support.file.impl;

import com.chua.common.support.file.builder.ReadBuilder;
import com.linuxense.javadbf.DBFReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class DbfReadBuilder extends ReadBuilder {

    /**
     * 创建 DbfReadBuilder 实例
     * @param file file
     */
    public DbfReadBuilder(File file) {
        super(file);
    }

    /** Rows */
    public List<Map<String, Object>> rows() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (DBFReader reader = new DBFReader(new FileInputStream(file))) {
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < reader.getFieldCount(); i++) {
                headers.add(reader.getField(i).getName());
            }
            if (callback != null) {
                callback.onHeader(headers);
            }
            Object[] row;
            while ((row = reader.nextRecord()) != null) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < row.length && i < headers.size(); i++) {
                    String key = (columnMapping != null)
                            ? columnMapping.getOrDefault(headers.get(i), headers.get(i))
                            : headers.get(i);
                    map.put(key, row[i]);
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
    /** 读取 */
    public Object read() {
        return rows();
    }
}
