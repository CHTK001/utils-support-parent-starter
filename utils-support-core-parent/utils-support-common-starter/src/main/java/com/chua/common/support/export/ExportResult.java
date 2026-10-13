package com.chua.common.support.export;

import java.io.File;

/**
 * 导出结果。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ExportResult {

    /**
     * 实际使用的格式。
     */
    private final String format;

    /**
     * 实际使用的引擎名。
     */
    private final String engine;

    /**
     * 导出行数。
     */
    private final int rows;

    /**
     * 产出字节。
     */
    private final byte[] data;

    /**
     * 输出文件，可能为 {@code null}（输出到流时）。
     */
    private final File file;

    /**
     * 是否使用模板。
     */
    private final boolean templated;

    /**
     * 耗时（毫秒）。
     */
    private final long elapsed;

    /**
     * 构造导出结果。
     *
     * @param format    格式
     * @param engine    引擎名
     * @param rows      行数
     * @param data      产出字节
     * @param file      输出文件
     * @param templated 是否使用模板
     * @param elapsed   耗时
     */
    public ExportResult(String format, String engine, int rows, byte[] data, File file, boolean templated, long elapsed) {
        this.format = format;
        this.engine = engine;
        this.rows = rows;
        this.data = data;
        this.file = file;
        this.templated = templated;
        this.elapsed = elapsed;
    }

    /**
     * 获取格式。
     *
     * @return 格式
     */
    public String format() {
        return format;
    }

    /**
     * 获取引擎名。
     *
     * @return 引擎名
     */
    public String engine() {
        return engine;
    }

    /**
     * 获取行数。
     *
     * @return 行数
     */
    public int rows() {
        return rows;
    }

    /**
     * 获取产出字节。
     *
     * @return 产出字节
     */
    public byte[] data() {
        return data;
    }

    /**
     * 获取输出文件。
     *
     * @return 输出文件，可能为 {@code null}
     */
    public File file() {
        return file;
    }

    /**
     * 是否使用了模板。
     *
     * @return 是否使用模板
     */
    public boolean templated() {
        return templated;
    }

    /**
     * 获取耗时。
     *
     * @return 耗时（毫秒）
     */
    public long elapsed() {
        return elapsed;
    }

    @Override
    /**
     * 转换为字符串
     * @return 字符串 对象
     */
    public String toString() {
        return "ExportResult{format='" + format + "', engine='" + engine + "', rows=" + rows
                + ", bytes=" + (data == null ? 0 : data.length) + ", templated=" + templated
                + ", elapsed=" + elapsed + "ms}";
    }
}