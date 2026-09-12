package com.chua.dbf.support.file.impl;

import com.chua.common.support.file.builder.WriteBuilder;
import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/**
* @author CH
* @since 4.0.0
 */

public class DbfWriteBuilder extends WriteBuilder {

    /**
    * 创建 dbf写入构建器 实例
    * @param file 文件
     */
    public DbfWriteBuilder(File file) {
        super(file);
    }

    @Override
    /** 写入 */
    public DbfWriteBuilder write(Object data) {
        if (data instanceof Map || data instanceof List) {
            pending.add(data);
        } else {
            pending.add(toMapList(data));
        }
        return this;
    }

    /**
    * 写入
    *
    * @param rows rows
     */
    public void write(List<Map<String, Object>> rows) {
        pending.add(rows);
        finish();
    }

    @Override
    /** 饰面 */
    public void finish() {
        callback.onStart();
        callback.onBeginWrite();
        try {
            for (Object entry : pending) {
                if (entry instanceof List) {
                    List<?> list = (List<?>) entry;
                    if (list.isEmpty() || !(list.get(0) instanceof Map)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
                    List<String> cols = resolveColumns(rows);

                    try (DBFWriter writer = new DBFWriter(new FileOutputStream(file))) {
                        DBFField[] fields = new DBFField[cols.size()];
                        for (int i = 0; i < cols.size(); i++) {
                            DBFField field = new DBFField();
                            field.setName(cols.get(i).length() > 10 ? cols.get(i).substring(0, 10) : cols.get(i));
                            field.setType(DBFDataType.CHARACTER);
                            field.setLength(254);
                            fields[i] = field;
                        }
                        writer.setFields(fields);

                        int total = rows.size(), processed = 0;
                        for (Map<String, Object> row : rows) {
                            // 写入行过滤
                            if (testRow(row)) {
                                Object[] values = new Object[cols.size()];
                                for (int i = 0; i < cols.size(); i++) {
                                    values[i] = String.valueOf(row.getOrDefault(cols.get(i), ""));
                                }
                                writer.addRecord(values);
                                callback.onProgress(++processed, total);
                            }
                        }
                    }
                }
            }
            callback.onComplete(true);
        } catch (IOException e) {
            callback.onComplete(false);
            throw new RuntimeException("DBF 写入失败", e);
        }
    }

    /**
    * 解析Columns
    *
    * @param rows rows
    * @return resolveColumns的结果
     */
    private List<String> resolveColumns(List<Map<String, Object>> rows) {
        if (headerColumns != null) {
            return headerColumns;
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.get(0).keySet());
    }
}
