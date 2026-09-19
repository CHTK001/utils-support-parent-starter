package com.chua.dbf.support.datasource;

import com.chua.datasource.support.datasource.MutableDataTable;
import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFReader;
import com.linuxense.javadbf.DBFWriter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DBF 数据表实现，支持 dbase 格式文件的读写和 CRUD 操作。
 * <p>
 * 基于 javadbf 库实现。数据在内存中维护，
 * 通过 {@link #save()} 写出到文件。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DbfDataTable extends MutableDataTable {

    /**
     * DBF 文件路径
     */
    private final String filePath;

    /**
     * 列类型列表
     */
    private final List<Class<?>> columnTypes;

    /**
     * DBF 字段元数据（写回时需要）
     */
    private final List<DBFField> dbfFields;

    // ---------------------------------------------------------------
    // 构造
    // ---------------------------------------------------------------

    /**
     * 创建 DBF 数据table。
     *
     * @param name 表名
     * @param file DBF 文件
     */
    public DbfDataTable(String name, File file) {
        super(name);
        this.filePath = file.getAbsolutePath();
        this.columnTypes = new ArrayList<>();
        this.dbfFields = new ArrayList<>();

        if (file.exists()) {
            var parsed = parseDbf(file);
            for (String col : parsed.columnNames) {
                addColumn(col);
            }
            for (Map<String, Object> row : parsed.rows) {
                addRow(row);
            }
            this.columnTypes.addAll(parsed.columnTypes);
            this.dbfFields.addAll(parsed.dbfFields);
        }
    }

    // ---------------------------------------------------------------
    // DBF 特有方法
    // ---------------------------------------------------------------

    /**
     * 获取列类型列表。
     *
     * @return 列类型列表
     */
    public List<Class<?>> columnTypes() {
        return Collections.unmodifiableList(columnTypes);
    }

    /**
     * 保存变更到 DBF 文件。
     */
    public void save() {
        writeDbf();
    }

    // ---------------------------------------------------------------
    // DBF 解析
    // ---------------------------------------------------------------

    /**
     * 解析结果。
     * @author CH
     * @since 4.0.0
     */
    private static class DbfParseResult {
        final List<String> columnNames; // column名称
        final List<Class<?>> columnTypes; // column类型
        final List<DBFField> dbfFields; // dbf字段
        final List<Map<String, Object>> rows; // rows

        DbfParseResult(List<String> columnNames, List<Class<?>> columnTypes,
                       List<DBFField> dbfFields, List<Map<String, Object>> rows) {
            this.columnNames = columnNames;
            this.columnTypes = columnTypes;
            this.dbfFields = dbfFields;
            this.rows = rows;
        }
    }

    /**
     * 解析 DBF 文件。
     * @param file 文件
     * @return 解析dbf的结果
     */
    private DbfParseResult parseDbf(File file) {
        List<String> names = new ArrayList<>();
        List<Class<?>> types = new ArrayList<>();
        List<DBFField> fields = new ArrayList<>();
        List<Map<String, Object>> dataRows = new ArrayList<>();

        try (DBFReader reader = new DBFReader(new FileInputStream(file))) {
            // 读取字段定义
            for (int i = 0; i < reader.getFieldCount(); i++) {
                DBFField field = reader.getField(i);
                fields.add(field);
                names.add(field.getName());
                types.add(dbfTypeToJavaClass(field.getType()));
            }

            // 读取数据行
            Object[] row;
            while ((row = reader.nextRecord()) != null) {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < row.length && i < names.size(); i++) {
                    rowMap.put(names.get(i), row[i]);
                }
                dataRows.add(rowMap);
            }
        } catch (IOException e) {
            log.warn("读取 DBF 失败: {}", filePath, e);
        }

        return new DbfParseResult(names, types, fields, dataRows);
    }

    /**
     * DBF 字段类型 → Java 类型映射。
     * @param type 类型
     * @return dbf类型转为java类的结果
     */
    private Class<?> dbfTypeToJavaClass(DBFDataType type) {
        return switch (type) {
            case CHARACTER -> String.class;
            case NUMERIC, FLOATING_POINT, DOUBLE -> Double.class;
            case LONG -> Long.class;
            case LOGICAL -> Boolean.class;
            case DATE -> java.util.Date.class;
            case TIMESTAMP -> java.sql.Timestamp.class;
            case MEMO -> String.class;
            default -> String.class;
        };
    }

    // ---------------------------------------------------------------
    // DBF 写出
    // ---------------------------------------------------------------

    /**
     * 将内存数据写出到 DBF 文件。
     */
    private void writeDbf() {
        Path path = Paths.get(filePath);
        if (path.getParent() != null) {
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("创建 DBF 目录失败: " + filePath, e);
            }
        }

        // 构建字段定义
        List<DBFField> outFields = new ArrayList<>();
        if (!dbfFields.isEmpty()) {
            outFields.addAll(dbfFields);
        } else {
            // 无已有字段定义时从列名推断
            for (String col : getColumnNames()) {
                DBFField field = new DBFField();
                field.setName(col.length() > 10 ? col.substring(0, 10) : col);
                field.setType(DBFDataType.CHARACTER);
                field.setLength(254);
                outFields.add(field);
            }
        }

        try (DBFWriter writer = new DBFWriter(new FileOutputStream(filePath))) {
            // 一次性设置所有字段定义
            writer.setFields(outFields.toArray(new DBFField[0]));

            // 写入数据行
            for (Map<String, Object> row : getData()) {
                Object[] record = new Object[outFields.size()];
                for (int i = 0; i < outFields.size(); i++) {
                    String colName = i < getColumnNames().size() ? getColumnNames().get(i) : outFields.get(i).getName();
                    record[i] = row.get(colName);
                }
                writer.addRecord(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入 DBF 失败: " + filePath, e);
        }
    }

    @Override
    /** 转为字符串 */
    public String toString() {
        return "DbfDataTable{" +
                "name='" + getName() + '\'' +
                ", filePath='" + filePath + '\'' +
                ", rows=" + getRowCount() +
                '}';
    }
}
