package com.chua.common.support.file.builder;

import java.io.File;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.NullUnmarked;

/**
 * 文件读取构建器抽象基类。
 *
 * <p>提供文件读取的通用配置和默认实现，子类通过覆盖各方法实现具体格式的读取逻辑。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public abstract class ReadBuilder {

    /** 待读取的文件 */
    /**
     * 文件路径
     */
    protected final File file;

    /** 读取时使用的字符集编码，默认使用系统编码 */
    protected Charset charset = Charset.defaultCharset();

    /** 读取回调 */
    protected ReadCallback callback;

    /** 列名映射（字段 → 中文），读取时自动转换 */
    protected Map<String, String> columnMapping;

    /** 是否将首行作为表头 */
    protected boolean hasHeader = true;

    /** 行过滤谓词（null 表示不过滤） */
    protected Predicate<Map<String, Object>> rowFilter;

    /** 行数据转换函数（null 表示不转换） */
    protected Function<Map<String, Object>, Map<String, Object>> rowMapper;

    /**
     * 构造读取构建器。
     *
     * @param file 待读取的文件
     */
    protected ReadBuilder(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public Charset getCharset() {
        return charset;
    }

    /**
     * 设置字符集编码。
     *
     * @param charset 编码名称
     * @return 当前构建器
     */
    public ReadBuilder withCharset(String charset) {
        this.charset = Charset.forName(charset);
        return this;
    }

    /**
     * 设置字符集编码。
     *
     * @param charset 编码对象
     * @return 当前构建器
     */
    public ReadBuilder withCharset(Charset charset) {
        this.charset = charset;
        return this;
    }

    /**
     * 设置读取回调。
     *
     * @param callback 回调实例
     * @return 当前构建器
     */
    public ReadBuilder withCallback(ReadCallback callback) {
        this.callback = callback;
        return this;
    }

    /**
     * 设置列名映射（字段 → 中文），读取时自动转换。
     *
     * @param columnMapping 映射表
     * @return 当前构建器
     */
    public ReadBuilder columnMapping(Map<String, String> columnMapping) {
        this.columnMapping = columnMapping;
        return this;
    }

    /**
     * 设置是否将首行作为表头。
     *
     * @param hasHeader 是否包含表头
     * @return 当前构建器
     */
    public ReadBuilder withHeader(boolean hasHeader) {
        this.hasHeader = hasHeader;
        return this;
    }

    /**
     * 设置行过滤条件 — 仅返回满足条件的行记录。
     * <p>所有返回 {@code List<Map<String, Object>>} 的表格类实现（CSV、JSON、XML、TXT、Excel、DBF 等）
     * 均支持此过滤。各子类在 {@code rows()} 返回值前调用 {@link #applyFilter(List)} 统一应用。</p>
     *
     * @param filter 行过滤谓词（接收一行 Map 数据，返回 true 保留）
     * @return 当前构建器
     */
    public ReadBuilder filter(Predicate<Map<String, Object>> filter) {
        this.rowFilter = filter;
        return this;
    }

    /**
     * 在返回前应用行过滤（子类在 {@code rows()} 中调用）。
     * <p>当 {@link #rowFilter} 为 null 时不做过滤，直接返回原列表。</p>
     *
     * @param rows 子类解析出的全量行数据
     * @return 过滤后的行数据（若 rowFilter 为 null 则原样返回）
     */
    protected List<Map<String, Object>> applyFilter(List<Map<String, Object>> rows) {
        if (rowFilter == null || rows == null || rows.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(rowFilter)
                .collect(Collectors.toList());
    }

    /**
     * 设置行数据转换函数 — 在每个行的 Map 数据返回前进行转换。
     * <p>可用于添加/删除/重命名字段、转换字段值等操作。
     * 所有表格类实现均支持此转换。各子类在 {@code rows()} 返回值前、
     * {@link #applyFilter(List)} 之后调用 {@link #applyRowMapping(List)} 统一应用。</p>
     *
     * <p><b>执行顺序：</b>{@code columnMapping}（列重命名，解析阶段）→ {@code filter}（行过滤）→
     * {@code mapRows}（行转换）。因此 mapper 中看到的数据已完成列映射和过滤。</p>
     *
     * <p><b>注意：</b>mapper 应始终返回非 {@code null} 值。返回 {@code null} 不会删除该行，
     * 而是会作为 {@code null} 元素出现在结果列表中。如需删除行请使用 {@link #filter(Predicate)}。</p>
     *
     * @param mapper 行转换函数（接收一行 Map 数据，返回转换后的 Map，不可返回 null）
     * @return 当前构建器
     */
    public ReadBuilder mapRows(Function<Map<String, Object>, Map<String, Object>> mapper) {
        this.rowMapper = mapper;
        return this;
    }

    /**
     * 在返回前应用行数据转换（子类在 {@code rows()} 中调用）。
     * <p>当 {@link #rowMapper} 为 null 时不做转换，直接返回原列表。</p>
     *
     * @param rows 已过滤的行数据列表
     * @return 转换后的行数据（若 rowMapper 为 null 则原样返回）
     */
    protected List<Map<String, Object>> applyRowMapping(List<Map<String, Object>> rows) {
        if (rowMapper == null || rows == null || rows.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .map(rowMapper)
                .collect(Collectors.toList());
    }

    // ==================== 通用读取方法 ====================

    /**
     * 读取全部行文本。
     *
     * @return 行文本列表
     */
    public List<String> asLines() {
        throw new UnsupportedOperationException("该文件类型不支持 asLines 操作");
    }

    /**
     * 读取全部内容为字符串。
     *
     * @return 文件内容字符串
     */
    public String asString() {
        throw new UnsupportedOperationException("该文件类型不支持 asString 操作");
    }

    /**
     * 读取为 Map 结构。
     *
     * @return Map 格式的数据
     */
    public Map<String, Object> toMap() {
        throw new UnsupportedOperationException("该文件类型不支持 toMap 操作");
    }

    /**
     * 读取并反序列化为指定类型。
     *
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 反序列化后的对象
     */
    public <T> T toObject(Class<T> clazz) {
        throw new UnsupportedOperationException("该文件类型不支持 toObject 操作");
    }

    // ==================== 统一同步/异步读取 ====================

    /**
     * 同步读取全部数据。
     *
     * @return 读取结果
     */
    public Object read() {
        throw new UnsupportedOperationException("该文件类型不支持 read 操作");
    }

    /**
     * 异步读取数据，通过回调处理结果。
     *
     * @param callback 回调
     * @return CompletableFuture
     */
    public CompletableFuture<Void> readAsync(ReadCallback callback) {
        withCallback(callback);
        return CompletableFuture.runAsync(() -> read());
    }

    // ==================== 响应式流读取 ====================

    /**
     * 以流式方式逐行读取。
     *
     * @return 行文本流
     */
    public Stream<String> streamLines() {
        return asLines().stream();
    }

    // ==================== 归档/压缩包操作方法 ====================

    /**
     * 列出压缩包中所有条目名称。
     *
     * @return 条目名称列表
     */
    public List<String> listEntries() {
        throw new UnsupportedOperationException("该文件类型不支持 listEntries 操作");
    }

    /**
     * 将压缩包全部内容提取到目标目录。
     *
     * @param targetDir 目标目录
     */
    public void extractAll(File targetDir) {
        throw new UnsupportedOperationException("该文件类型不支持 extractAll 操作");
    }

    /**
     * 将压缩包中指定条目提取到目标目录。
     *
     * @param entryName 要提取的条目名称
     * @param targetDir 目标目录
     */
    public void extract(String entryName, File targetDir) {
        throw new UnsupportedOperationException("该文件类型不支持 extract 操作");
    }

    /**
     * 将压缩包中指定一个或多个条目提取到目标目录。
     *
     * @param targetDir  目标目录
     * @param entryNames 要提取的条目名称（不限数量）
     */
    public void extract(File targetDir, String... entryNames) {
        throw new UnsupportedOperationException("该文件类型不支持 extract 操作");
    }

    /**
     * 读取压缩包中指定文件的内容为字符串。
     *
     * @param entryName 条目名称
     * @return 文件内容字符串
     */
    public String readEntry(String entryName) {
        throw new UnsupportedOperationException("该文件类型不支持 readEntry 操作");
    }
}