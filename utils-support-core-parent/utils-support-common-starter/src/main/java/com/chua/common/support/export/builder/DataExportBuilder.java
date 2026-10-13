package com.chua.common.support.export.builder;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.Documents;
import com.chua.common.support.export.ExportConfig;
import com.chua.common.support.export.ExportContext;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.ExportResult;
import com.chua.common.support.export.Engines;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.resolver.MetaResolvers;
import com.chua.common.support.export.spi.DocumentGenerator;
import com.chua.common.support.export.support.Outputs;
import com.chua.common.support.export.support.Rows;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 非模板构建器 —— 接口一：<b>输入 List 数据 → 直接生成文档</b>。
 *
 * <p>列结构由 {@code MetaResolver} SPI 链解析（反射兜底 → 三方注解 → 自定义注解），
 * 调用 {@link #template(String)} 等方法可切换到模板模式，模板语法见
 * {@link TemplateExportBuilder} 的类注释。</p>
 *
 * <p>流归属遵循"谁打开谁关闭"：{@link #output(OutputStream)} 传入的流默认不被关闭，
 * 需要时用 {@link #autoCloseStream(boolean)} 托管。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DataExportBuilder {

    /**
     * 默认格式。
     */
    private static final String DEFAULT_FORMAT = ExportFormats.XLSX;

    /**
     * 原始行数据。
     */
    private Object rows;

    /**
     * 显式声明的数据行类型。
     */
    private Class<?> type;

    /**
     * 目标格式。
     */
    private String format;

    /**
     * 引擎别名。
     */
    private String engine;

    /**
     * 目标文件。
     */
    private File file;

    /**
     * 目标流。
     */
    private OutputStream stream;

    /**
     * 目标流是否已由本构建器关闭。
     */
    private boolean outputClosed;

    /**
     * 附加变量。
     */
    private final Map<String, Object> variables = Wraps.map();

    /**
     * 显式包含的列（为空表示全部）。
     */
    private final Set<String> includes = new LinkedHashSet<>();

    /**
     * 显式排除的列。
     */
    private final Set<String> excludes = new LinkedHashSet<>();

    /**
     * 元数据覆写回调。
     */
    private final java.util.List<Consumer<DocumentMeta>> metaTweaks = Wraps.list();

    /**
     * 配置覆写。
     */
    private final ExportConfig.Builder configBuilder = ExportConfig.builder();

    /**
     * 构造构建器。
     *
     * @param rows 行数据
     */
    public DataExportBuilder(Object rows) {
        this.rows = rows;
    }

    /**
     * 设置目标格式。
     *
     * @param value 格式标识，如 {@code xlsx} / {@code docx} / {@code pdf}
     * @return 当前构建器
     */
    public DataExportBuilder format(String value) {
        this.format = ExportFormats.normalize(value);
        return this;
    }

    /**
     * 显式指定引擎（SPI 别名）。
     *
     * @param value 引擎别名
     * @return 当前构建器
     */
    public DataExportBuilder engine(String value) {
        this.engine = value;
        return this;
    }

    /**
     * 显式声明数据行类型（数据为空时仍能生成表头）。
     *
     * @param value 数据行类型
     * @return 当前构建器
     */
    public DataExportBuilder type(Class<?> value) {
        this.type = value;
        return this;
    }

    /**
     * 设置附加变量，供实现方在模板/页眉等位置引用。
     *
     * @param key   变量名
     * @param value 变量值
     * @return 当前构建器
     */
    public DataExportBuilder put(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            variables.put(key, value);
        }
        return this;
    }

    /**
     * 批量设置附加变量。
     *
     * @param values 变量表
     * @return 当前构建器
     */
    public DataExportBuilder putAll(Map<String, Object> values) {
        if (values != null) {
            variables.putAll(values);
        }
        return this;
    }

    /**
     * 指定导出到文件。
     *
     * @param value 目标文件
     * @return 当前构建器
     */
    public DataExportBuilder output(File value) {
        this.file = value;
        this.stream = null;
        return this;
    }

    /**
     * 指定导出到路径。
     *
     * @param path 目标路径
     * @return 当前构建器
     */
    public DataExportBuilder output(String path) {
        return output(path == null ? null : new File(path));
    }

    /**
     * 指定导出到流。
     *
     * @param value 目标流
     * @return 当前构建器
     */
    public DataExportBuilder output(OutputStream value) {
        this.stream = value;
        this.file = null;
        this.outputClosed = false;
        return this;
    }

    /**
     * 限定导出的列（并决定顺序）。
     *
     * @param fields 属性名
     * @return 当前构建器
     */
    public DataExportBuilder columns(String... fields) {
        if (fields != null) {
            includes.addAll(List.of(fields));
        }
        return this;
    }

    /**
     * 排除指定的列。
     *
     * @param fields 属性名
     * @return 当前构建器
     */
    public DataExportBuilder exclude(String... fields) {
        if (fields != null) {
            excludes.addAll(List.of(fields));
        }
        return this;
    }

    /**
     * 直接操作元数据（覆盖注解结果）。
     *
     * @param tweak 覆写回调
     * @return 当前构建器
     */
    public DataExportBuilder meta(Consumer<DocumentMeta> tweak) {
        if (tweak != null) {
            metaTweaks.add(tweak);
        }
        return this;
    }

    /**
     * 设置工作表名 / 文档小节名。
     *
     * @param value 工作表名
     * @return 当前构建器
     */
    public DataExportBuilder sheet(String value) {
        return meta(meta -> meta.sheetName(value));
    }

    /**
     * 设置文档标题。
     *
     * @param value 标题
     * @return 当前构建器
     */
    public DataExportBuilder title(String value) {
        return meta(meta -> meta.title(value));
    }

    /**
     * 设置表头行号。
     *
     * @param value 行号，从 1 开始
     * @return 当前构建器
     */
    public DataExportBuilder headerRow(int value) {
        return meta(meta -> meta.headerRow(value));
    }

    /**
     * 开关表头。
     *
     * @param value 是否生成表头
     * @return 当前构建器
     */
    public DataExportBuilder header(boolean value) {
        return meta(meta -> meta.header(value));
    }

    /**
     * 开关冻结表头。
     *
     * @param value 是否冻结
     * @return 当前构建器
     */
    public DataExportBuilder freezeHeader(boolean value) {
        return meta(meta -> meta.freezeHeader(value));
    }

    /**
     * 开关自动筛选。
     *
     * @param value 是否筛选
     * @return 当前构建器
     */
    public DataExportBuilder autoFilter(boolean value) {
        return meta(meta -> meta.autoFilter(value));
    }

    /**
     * 设置单个工作表最大行数，0 表示不拆分。
     *
     * @param value 最大行数
     * @return 当前构建器
     */
    public DataExportBuilder rowsPerSheet(int value) {
        return meta(meta -> meta.rowsPerSheet(value));
    }

    /**
     * 设置模板主数据标签名（切换到模板模式后生效）。
     *
     * @param value 标签名
     * @return 当前构建器
     */
    public DataExportBuilder tableTag(String value) {
        this.configBuilder.tableTag(value);
        return this;
    }

    /**
     * 开关流式写出。
     *
     * @param value 是否流式
     * @return 当前构建器
     */
    public DataExportBuilder streaming(boolean value) {
        this.configBuilder.streaming(value);
        return this;
    }

    /**
     * 开关由框架关闭调用方传入的流。
     *
     * <p>默认 {@code false}，即与常见 IO 约定一致：谁打开谁关闭。置为 {@code true} 后，
     * {@link #export()} 结束（无论成功或失败）时关闭 {@link #output(OutputStream)} 指定的流。</p>
     *
     * @param value 是否自动关闭
     * @return 当前构建器
     */
    public DataExportBuilder autoCloseStream(boolean value) {
        this.configBuilder.autoCloseStream(value);
        return this;
    }

    /**
     * 指定导出到路径。
     *
     * @param path 目标路径
     * @return 当前构建器
     */
    public DataExportBuilder output(Path path) {
        return output(path == null ? null : path.toFile());
    }

    /**
     * <b>切换到模板模式</b> —— 由路径加载模板。
     *
     * <p>格式按扩展名推断（{@code xlsx} / {@code docx} / {@code pdf}）；
     * 无法推断时可用 {@link TemplateExportBuilder#format(String)} 显式指定。
     * 模板语法见 {@link TemplateExportBuilder} 的类注释。</p>
     *
     * @param template 模板路径，支持 {@code classpath:} 前缀
     * @return 模板构建器
     */
    public TemplateExportBuilder template(String template) {
        return new TemplateExportBuilder(this).template(template);
    }

    /**
     * <b>切换到模板模式</b> —— 由文件加载模板。
     *
     * @param template 模板文件
     * @return 模板构建器
     */
    public TemplateExportBuilder template(File template) {
        return new TemplateExportBuilder(this).template(template);
    }

    /**
     * <b>切换到模板模式</b> —— 由字节加载模板。
     *
     * @param template 模板字节
     * @return 模板构建器
     */
    public TemplateExportBuilder template(byte[] template) {
        return new TemplateExportBuilder(this).template(template);
    }

    /**
     * <b>切换到模板模式</b> —— 由输入流加载模板。
     *
     * @param template 模板输入流
     * @return 模板构建器
     */
    public TemplateExportBuilder template(InputStream template) {
        return new TemplateExportBuilder(this).template(template);
    }

    /**
     * <b>切换到模板模式</b> —— 由路径加载模板，并按扩展名推断目标格式。
     *
     * <p>路径可以是文件系统路径，也可以是类路径：{@code "classpath:templates/a.docx"} 显式类路径；
     * {@code "templates/a.docx"} 先试文件系统再试类路径。</p>
     *
     * @param template 模板路径
     * @return 模板构建器
     */
    public TemplateExportBuilder template(Path template) {
        return new TemplateExportBuilder(this).template(template);
    }

    /**
     * 替换行数据。
     *
     * @param value 行数据
     * @return 当前构建器
     */
    public DataExportBuilder rows(Object value) {
        this.rows = value;
        return this;
    }

    /**
     * 获取行数据。
     *
     * @return 归一化后的行列表
     */
    public List<Object> rows() {
        return Rows.normalize(rows);
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
     * 获取输出流。
     *
     * @return 输出流，可能为 {@code null}
     */
    public OutputStream stream() {
        return stream;
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
     * 获取引擎别名。
     *
     * @return 引擎别名，可能为 {@code null}
     */
    public String engine() {
        return engine;
    }

    /**
     * 获取数据行类型。
     *
     * @return 数据行类型
     */
    public Class<?> type() {
        return type;
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
     * 获取包含列。
     *
     * @return 列名集合
     */
    public Set<String> includes() {
        return includes;
    }

    /**
     * 获取排除列。
     *
     * @return 列名集合
     */
    public Set<String> excludes() {
        return excludes;
    }

    /**
     * 获取元数据覆写回调。
     *
     * @return 回调列表
     */
    public List<Consumer<DocumentMeta>> metaTweaks() {
        return metaTweaks;
    }

    /**
     * 获取配置构建器。
     *
     * @return 配置构建器
     */
    public ExportConfig.Builder configBuilder() {
        return configBuilder;
    }

    /**
     * <b>预览而不写出</b> —— 构建元数据供调用方检查。
     *
     * @return 文档元数据
     */
    public DocumentMeta preview() {
        return DocumentExporters.buildMeta(this);
    }

    /**
     * 执行导出（非模板模式）。
     *
     * @return 导出结果
     */
    public ExportResult export() {
        long start = System.currentTimeMillis();
        List<Object> data = rows();
        DocumentMeta meta = DocumentExporters.buildMeta(this);
        ExportConfig config = configBuilder.format(format).engine(engine).build();
        String target = Outputs.resolveFormat(file, stream, format, DEFAULT_FORMAT);
        DocumentGenerator generator = Engines.generator(target, engine);

        ExportContext context = new ExportContext(meta, data, variables, config);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(1024, data.size() * 64));
        try {
            generator.generate(context, buffer);
            byte[] bytes = Outputs.deliver(buffer.toByteArray(), file, stream);
            return new ExportResult(target, generator.engine(), data.size(), bytes, file, false,
                    System.currentTimeMillis() - start);
        } catch (IOException e) {
            throw new ExportException("导出失败: " + target, e);
        } finally {
            release(config);
        }
    }

    /**
     * 导出结束后按需关闭调用方传入的目标流。
     *
     * @param config 导出配置
     */
    private void release(ExportConfig config) {
        if (!config.isAutoCloseStream() || outputClosed || stream == null) {
            return;
        }
        outputClosed = true;
        try {
            stream.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    /**
     * 执行导出，等价于 {@link #export()}。
     *
     * @return 导出结果
     */
    public ExportResult write() {
        return export();
    }
}