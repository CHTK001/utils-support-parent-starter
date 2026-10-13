package com.chua.common.support.export;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.support.Rows;

import java.util.List;
import java.util.Map;

/**
 * 导出上下文 —— 接口一（List → 文档）的输入载体。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ExportContext {

    /**
     * 文档元数据。
     */
    private final DocumentMeta meta;

    /**
     * 行数据。
     */
    private final List<Object> rows;

    /**
     * 附加标量变量。
     */
    private final Map<String, Object> variables;

    /**
     * 导出配置。
     */
    private final ExportConfig config;

    /**
     * 构造导出上下文。
     *
     * @param meta      文档元数据
     * @param rows      行数据
     * @param variables 附加变量
     * @param config    导出配置
     */
    public ExportContext(DocumentMeta meta, List<Object> rows, Map<String, Object> variables, ExportConfig config) {
        this.meta = meta;
        this.rows = rows == null ? Wraps.list() : rows;
        this.variables = variables == null ? Wraps.map() : variables;
        this.config = config == null ? ExportConfig.EMPTY : config;
    }

    /**
     * 获取文档元数据。
     *
     * @return 文档元数据
     */
    public DocumentMeta meta() {
        return meta;
    }

    /**
     * 获取行数据。
     *
     * @return 行数据
     */
    public List<Object> rows() {
        return rows;
    }

    /**
     * 获取可见列。
     *
     * @return 可见列
     */
    public List<ColumnMeta> columns() {
        return meta.visibleColumns();
    }

    /**
     * 获取表头。
     *
     * @return 表头列表
     */
    public List<String> headers() {
        return meta.headers();
    }

    /**
     * 获取行数。
     *
     * @return 行数
     */
    public int size() {
        return rows.size();
    }

    /**
     * 获取附加变量。
     *
     * @return 变量表
     */
    public Map<String, Object> variables() {
        return variables;
    }

    /**
     * 获取导出配置。
     *
     * @return 导出配置
     */
    public ExportConfig config() {
        return config;
    }

    /**
     * 读取某行某列的原始值。
     *
     * @param row    行对象
     * @param column 列元数据
     * @return 原始值
     */
    public Object raw(Object row, ColumnMeta column) {
        return Rows.raw(row, column);
    }

    /**
     * 读取某行某列的展示值。
     *
     * @param row    行对象
     * @param column 列元数据
     * @return 展示值
     */
    public Object display(Object row, ColumnMeta column) {
        return Rows.display(row, column);
    }
}