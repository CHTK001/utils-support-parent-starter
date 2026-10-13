package com.chua.common.support.export.builder;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.ExportConfig;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.ExportResult;
import com.chua.common.support.export.Engines;
import com.chua.common.support.export.TemplateContext;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.TemplateFiller;
import com.chua.common.support.export.support.Outputs;
import com.chua.common.support.export.support.Rows;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板构建器 —— 接口二：<b>模板替换</b>。
 *
 * <p>由 {@code DataExportBuilder#template(...)} 切换而来，也可通过
 * {@code Documents.ofTemplate(...)} 直接创建。</p>
 *
 * <pre>{@code
 * Documents.of(rows)
 *           .template(Path.of("templates/report.docx"))
 *           .put("title", "季度报表")
 *           .bind("users", rows)          // 循环块
 *           .output(Path.of("/tmp/report.docx"))
 *           .export();
 * }</pre>
 *
 * <p><b>模板语法</b>（前后缀由 {@link ExportConfig#getTagPrefix()} /
 * {@code getTagSuffix()} 决定，默认 {@code {{ }} }）：</p>
 * <ul>
 *     <li>{@code {{key}}} —— 标量，取自 {@link #put(String, Object)} / {@link #putAll(Map)}</li>
 *     <li>{@code {{a.b.c}}} —— 嵌套路径，任意深度；集合按下标：{@code {{items.0}}}</li>
 *     <li>{@code {{_row.field}}} —— 循环行内取未导出为列的字段</li>
 *     <li>{@code {{@index}}} / {@code {{@first}}} / {@code {{@last}}} —— 循环内元信息</li>
 *     <li>{@code {{#tag}}} … {@code {{/tag}}} —— 循环块，按 {@link #bind(String, Object)}
 *         绑定的数据行数复制；标记行填充后移除，块内无数据时整块消失</li>
 * </ul>
 *
 * <p>取值优先级：循环元信息 → 原始行对象的嵌套路径 → 当前行展开值 → 循环表 → 标量变量。</p>
 *
 * <p>流归属遵循"谁打开谁关闭"：{@code template(InputStream)} 与 {@code output(OutputStream)}
 * 传入的流默认不被关闭，需要时用 {@link #autoCloseStream(boolean)} 托管。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TemplateExportBuilder {

    /**
     * 默认格式。
     */
    private static final String DEFAULT_FORMAT = ExportFormats.XLSX;

    /**
     * 默认时间格式。
     */
    private static final String TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * 类路径前缀。
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * 模板来源：路径。
     */
    private String templatePath;

    /**
     * 模板来源：文件。
     */
    private File templateFile;

    /**
     * 模板来源：字节。
     */
    private byte[] templateBytes;

    /**
     * 模板来源：流。
     */
    private InputStream templateStream;

    /**
     * 模板来源：文件名（用于推断格式）。
     */
    private String templateName;

    /**
     * 源构建器，可为 {@code null}。
     */
    private final DataExportBuilder source;

    /**
     * 用于解析元数据的构建器视图，承载来源构建器的列限定与元数据覆写。
     */
    private final DataExportBuilder metaSource;

    /**
     * 主数据行。
     */
    private Object rows = List.of();

    /**
     * 显式数据行类型。
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
     * 标量变量。
     */
    private final Map<String, Object> variables = new LinkedHashMap<>();

    /**
     * 循环表格：标签 → 行数据。
     */
    private final Map<String, Object> tables = new LinkedHashMap<>();

    /**
     * 配置覆写。
     */
    private final ExportConfig.Builder configBuilder = ExportConfig.builder();

    /**
     * 构造空的模板构建器。
     */
    public TemplateExportBuilder() {
        this.source = null;
        this.metaSource = new DataExportBuilder(List.of());
    }

    /**
     * 从数据构建器切换而来。
     *
     * @param source 源构建器
     */
    public TemplateExportBuilder(DataExportBuilder source) {
        this.source = source;
        if (source == null) {
            this.metaSource = new DataExportBuilder(List.of());
            return;
        }
        this.rows = source.rows();
        this.type = source.type();
        this.format = source.format();
        this.engine = source.engine();
        this.file = source.file();
        this.stream = source.stream();
        this.configBuilder.mergeFrom(source.configBuilder());
        this.variables.putAll(source.variables());

        this.metaSource = new DataExportBuilder(source.rows());
        this.metaSource.type(source.type());
        this.metaSource.metaTweaks().addAll(source.metaTweaks());
        this.metaSource.includes().addAll(source.includes());
        this.metaSource.excludes().addAll(source.excludes());
    }

    /**
     * 设置模板路径。
     *
     * @param value 模板路径，支持 {@code classpath:} 前缀
     * @return 当前构建器
     */
    public TemplateExportBuilder template(String value) {
        this.templatePath = value;
        this.templateName = value;
        return this;
    }

    /**
     * 设置模板路径。
     *
     * @param value 模板路径
     * @return 当前构建器
     */
    public TemplateExportBuilder template(Path value) {
        return template(value == null ? null : value.toString());
    }

    /**
     * 设置模板类路径资源（等价于 {@code template("classpath:" + resource)}）。
     *
     * @param resource 资源路径，如 {@code templates/report.docx}
     * @return 当前构建器
     */
    public TemplateExportBuilder classpath(String resource) {
        return template(resource == null ? null : CLASSPATH_PREFIX + resource);
    }

    /**
     * 设置模板文件。
     *
     * @param value 模板文件
     * @return 当前构建器
     */
    public TemplateExportBuilder template(File value) {
        this.templateFile = value;
        this.templateName = value == null ? null : value.getName();
        return this;
    }

    /**
     * 设置模板字节。
     *
     * @param value 模板字节
     * @return 当前构建器
     */
    public TemplateExportBuilder template(byte[] value) {
        this.templateBytes = value;
        return this;
    }

    /**
     * 设置模板输入流（由调用方负责关闭）。
     *
     * @param value 模板输入流
     * @return 当前构建器
     */
    public TemplateExportBuilder template(InputStream value) {
        this.templateStream = value;
        return this;
    }

    /**
     * 设置模板格式（当无法从模板名推断时）。
     *
     * @param value 格式标识
     * @return 当前构建器
     */
    public TemplateExportBuilder format(String value) {
        this.format = ExportFormats.normalize(value);
        return this;
    }

    /**
     * 显式指定模板引擎（SPI 别名）。
     *
     * @param value 引擎别名
     * @return 当前构建器
     */
    public TemplateExportBuilder engine(String value) {
        this.engine = value;
        return this;
    }

    /**
     * 设置主数据行。
     *
     * @param value 行数据
     * @return 当前构建器
     */
    public TemplateExportBuilder data(Object value) {
        this.rows = value;
        this.metaSource.rows(value);
        return this;
    }

    /**
     * 显式声明数据行类型。
     *
     * @param value 数据行类型
     * @return 当前构建器
     */
    public TemplateExportBuilder type(Class<?> value) {
        this.type = value;
        this.metaSource.type(value);
        return this;
    }

    /**
     * 设置标量变量，对应模板中的 {@code {{key}}}。
     *
     * @param key   变量名
     * @param value 变量值
     * @return 当前构建器
     */
    public TemplateExportBuilder put(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            variables.put(key, value);
        }
        return this;
    }

    /**
     * 批量设置标量变量。
     *
     * @param values 变量表
     * @return 当前构建器
     */
    public TemplateExportBuilder putAll(Map<String, Object> values) {
        if (values != null) {
            variables.putAll(values);
        }
        return this;
    }

    /**
     * 绑定循环表格，对应模板中的循环区标签。
     *
     * @param tag  标签名，如 {@code users}
     * @param data 行数据
     * @return 当前构建器
     */
    public TemplateExportBuilder bind(String tag, Object data) {
        if (tag != null && !tag.isEmpty()) {
            tables.put(tag, data);
        }
        return this;
    }

    /**
     * 把主数据行绑定到指定标签。
     *
     * @param tag 标签名，为空时使用配置中的 {@code tableTag}
     * @return 当前构建器
     */
    public TemplateExportBuilder bindTo(String tag) {
        String key = tag == null || tag.isBlank() ? configBuilder.build().getTableTag() : tag;
        tables.put(key, rows);
        return this;
    }

    /**
     * 批量绑定循环表格。
     *
     * @param values 标签 → 行数据
     * @return 当前构建器
     */
    public TemplateExportBuilder bindAll(Map<String, ?> values) {
        if (values != null) {
            values.forEach(this::bind);
        }
        return this;
    }

    /**
     * 设置模板主数据标签名。
     *
     * @param value 标签名
     * @return 当前构建器
     */
    public TemplateExportBuilder tableTag(String value) {
        this.configBuilder.tableTag(value);
        return this;
    }

    /**
     * 设置标签前缀。
     *
     * @param value 前缀
     * @return 当前构建器
     */
    public TemplateExportBuilder tagPrefix(String value) {
        this.configBuilder.tagPrefix(value);
        return this;
    }

    /**
     * 设置标签后缀。
     *
     * @param value 后缀
     * @return 当前构建器
     */
    public TemplateExportBuilder tagSuffix(String value) {
        this.configBuilder.tagSuffix(value);
        return this;
    }

    /**
     * 指定导出到文件。
     *
     * @param value 目标文件
     * @return 当前构建器
     */
    public TemplateExportBuilder output(File value) {
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
    public TemplateExportBuilder output(String path) {
        return output(path == null ? null : new File(path));
    }

    /**
     * 指定导出到流。
     *
     * @param value 目标流
     * @return 当前构建器
     */
    public TemplateExportBuilder output(OutputStream value) {
        this.stream = value;
        this.file = null;
        this.outputClosed = false;
        return this;
    }

    /**
     * 指定导出到路径。
     *
     * @param path 目标路径
     * @return 当前构建器
     */
    public TemplateExportBuilder output(Path path) {
        return output(path == null ? null : path.toFile());
    }

    /**
     * 开关由框架关闭调用方传入的流。
     *
     * <p>默认 {@code false}，即与常见 IO 约定一致：谁打开谁关闭。置为 {@code true} 后，
     * {@link #export()} 结束（无论成功或失败）时关闭 {@code output(...)} 的目标流，
     * 并且连同 {@link #template(InputStream)} 的模板流一起关闭。</p>
     *
     * @param value 是否自动关闭
     * @return 当前构建器
     */
    public TemplateExportBuilder autoCloseStream(boolean value) {
        this.configBuilder.autoCloseStream(value);
        return this;
    }

    /**
     * 构建模板上下文（供预览与调试）。
     *
     * @return 模板上下文
     */
    public TemplateContext context() {
        List<Object> data = Rows.normalize(rows);
        DocumentMeta meta = DocumentExporters.buildMeta(metaSource);
        ExportConfig config = configBuilder.format(format).engine(engine).build();

        Map<String, List<Map<String, Object>>> resolved = new LinkedHashMap<>();
        TemplateContext ctx = new TemplateContext(meta, new LinkedHashMap<>(), resolved, data, config);
        for (Map.Entry<String, Object> entry : tables.entrySet()) {
            resolved.put(entry.getKey(), ctx.flattenAll(Rows.normalize(entry.getValue())));
        }
        if (!tables.containsKey(config.getTableTag())) {
            resolved.put(config.getTableTag(), ctx.flattenAll(data));
        }

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", meta.getTitle().isEmpty() ? meta.resolveSheetName() : meta.getTitle());
        vars.put("sheetName", meta.resolveSheetName());
        vars.put("headers", meta.headers());
        vars.put("count", data.size());
        vars.put("exportTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern(TIME_FORMAT)));
        vars.putAll(source == null ? Wraps.<String, Object>map() : source.variables());
        vars.putAll(variables);
        return new TemplateContext(meta, vars, resolved, data, config);
    }

    /**
     * 执行模板填充。
     *
     * @return 导出结果
     */
    public ExportResult export() {
        long start = System.currentTimeMillis();
        TemplateContext context = context();
        ExportConfig config = context.config();
        String target = resolveFormat();
        TemplateFiller filler = Engines.filler(target, engine);
        int rowCount = context.tables().values().stream().mapToInt(List::size).max().orElse(0);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        InputStream in = null;
        try {
            in = openTemplate();
            filler.fill(context, in, buffer);
            byte[] bytes = Outputs.deliver(buffer.toByteArray(), file, stream);
            return new ExportResult(target, filler.engine(), rowCount, bytes, file, true,
                    System.currentTimeMillis() - start);
        } catch (IOException e) {
            throw new ExportException("模板填充失败: " + target, e);
        } finally {
            release(in, config);
        }
    }

    /**
     * 执行模板填充，等价于 {@link #export()}。
     *
     * @return 导出结果
     */
    public ExportResult write() {
        return export();
    }

    /**
     * 推断输出格式：显式格式 → 模板名 → 输出文件名 → 默认 {@code xlsx}。
     *
     * @return 格式标识
     */
    private String resolveFormat() {
        if (format != null && !format.isBlank()) {
            return format;
        }
        String fromTemplate = ExportFormats.of(templateName);
        if (fromTemplate != null) {
            return fromTemplate;
        }
        String fromOutput = file == null ? null : ExportFormats.of(file.getName());
        if (fromOutput != null) {
            return fromOutput;
        }
        // 模板与输出都没有扩展名（如模板来自字节/流）：跟随已注册填充器的格式，
        // 否则会误用 DEFAULT_FORMAT，在只装了 pdf 引擎的模块里直接找不到生成器
        String fromFiller = Engines.anyFillerFormat();
        return fromFiller == null ? DEFAULT_FORMAT : fromFiller;
    }

    /**
     * 打开模板输入流。
     *
     * @return 输入流
     * @throws IOException 读取失败
     */
    private InputStream openTemplate() throws IOException {
        if (templateStream != null) {
            return templateStream;
        }
        if (templateBytes != null) {
            return Outputs.openTemplate(templateBytes);
        }
        if (templateFile != null) {
            return Outputs.openTemplate(templateFile);
        }
        if (templatePath != null) {
            return Outputs.openTemplate(templatePath);
        }
        throw new ExportException("未指定模板：请调用 template(...)");
    }

    /**
     * 关闭模板流与目标流。
     *
     * <p>框架自己打开的模板流总是关闭；调用方传入的模板流仅在
     * {@link ExportConfig#isAutoCloseStream()} 为 {@code true} 时关闭。
     * 目标流同样只在开启该开关时关闭。</p>
     *
     * @param in     本次使用的模板流
     * @param config 导出配置
     */
    private void release(InputStream in, ExportConfig config) {
        if (in != null && (in != templateStream || config.isAutoCloseStream())) {
            closeQuietly(in);
        }
        if (!config.isAutoCloseStream() || outputClosed || stream == null) {
            return;
        }
        outputClosed = true;
        closeQuietly(stream);
    }

    /**
     * 静默关闭流。
     *
     * @param closeable 流
     */
    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    /**
     * 获取模板构建器对应的数据构建器。
     *
     * @return 数据构建器，可能为 {@code null}
     */
    public DataExportBuilder source() {
        return source;
    }

    /**
     * 获取循环表格绑定。
     *
     * @return 标签 → 行数据
     */
    public Map<String, Object> tables() {
        return new LinkedHashMap<>(tables);
    }

    /**
     * 获取标量变量。
     *
     * @return 变量表
     */
    public Map<String, Object> variables() {
        return new LinkedHashMap<>(variables);
    }
}