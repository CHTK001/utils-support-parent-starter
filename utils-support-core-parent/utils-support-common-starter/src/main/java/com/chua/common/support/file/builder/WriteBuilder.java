package com.chua.common.support.file.builder;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import com.chua.common.support.utils.BeanUtils;

/**
* 文件写入构建器抽象基类。
*
* <p>提供文件写入的通用配置，子类通过覆盖各方法实现具体格式的写入逻辑。
* 支持字符集设置、模板文件和数据填充功能。</p>
*
* @author CH
* @since 1.0.0
 */
public abstract class WriteBuilder {

    /** 待写入的文件 */
    /**
    * 文件路径
    */
    protected final File file;

    /** 写入时使用的字符集编码，默认使用系统编码 */
    protected Charset charset = Charset.defaultCharset();

    /** 模板文件路径，用于基于模板的写入操作 */
    protected File templateFile;

    /** 模板填充数据，用于带变量的模板渲染 */
    protected Map<String, Object> templateData;

    /** 通用延迟写入暂存区 */
    protected final List<Object> pending = new ArrayList<>();

    /** 列名映射（字段 → 中文），写入时自动转换 */
    protected Map<String, String> columnMapping;

    /** 写入回调 */
    protected WriteCallback callback = new WriteCallback() {
        @Override
        /** OnComplete */
        public void onComplete(boolean success) {}
    };

    /** 是否写入表头行 */
    protected boolean withHeader = true;

    /** 固定表头列列表（null 则自动从数据推断） */
    protected List<String> headerColumns;

    /** 行过滤谓词（null 表示写入所有行） */
    protected Predicate<Map<String, Object>> rowFilter;

    /**
    * 构造写入构建器。
    *
    * @param file 待写入的文件
    */
    protected WriteBuilder(File file) {
        this.file = file;
    }

    /** 获取File */
    public File getFile() {
        return file;
    }

    /** 获取Charset */
    public Charset getCharset() {
        return charset;
    }

    /** 获取TemplateFile */
    public File getTemplateFile() {
        return templateFile;
    }

    /** 获取TemplateData */
    public Map<String, Object> getTemplateData() {
        return templateData;
    }

    /**
    * 设置字符集编码。
    *
    * @param charset 编码名称
    * @return 当前构建器
    */
    public WriteBuilder withCharset(String charset) {
        this.charset = Charset.forName(charset);
        return this;
    }

    /**
    * 设置字符集编码。
    *
    * @param charset 编码对象
    * @return 当前构建器
    */
    public WriteBuilder withCharset(Charset charset) {
        this.charset = charset;
        return this;
    }

    /**
    * 设置模板文件。
    *
    * @param templateFile 模板文件
    * @return 当前构建器
    */
    public WriteBuilder withTemplate(File templateFile) {
        this.templateFile = templateFile;
        return this;
    }

    /**
    * 设置模板填充数据。
    *
    * @param data 填充数据，key 为模板变量名，value 为替换值
    * @return 当前构建器
    */
    public WriteBuilder withData(Map<String, Object> data) {
        this.templateData = data;
        return this;
    }

    /**
    * 设置写入回调。
    *
    * @param callback 回调实例
    * @return 当前构建器
    */
    public WriteBuilder withCallback(WriteCallback callback) {
        this.callback = callback;
        return this;
    }

    /**
    * 设置列名映射（字段 → 中文），写入时自动转换。
    *
    * @param columnMapping 映射表
    * @return 当前构建器
    */
    public WriteBuilder columnMapping(Map<String, String> columnMapping) {
        this.columnMapping = columnMapping;
        return this;
    }

    /**
    * 设置是否写入表头行。
    *
    * @param withHeader 是否写入
    * @return 当前构建器
    */
    public WriteBuilder withHeader(boolean withHeader) {
        this.withHeader = withHeader;
        return this;
    }

    /**
    * 设置固定表头列列表（控制写入哪些列及其顺序，null 则自动推断）。
    *
    * @param headerColumns 列名列表
    * @return 当前构建器
    */
    public WriteBuilder withHeaders(List<String> headerColumns) {
        this.headerColumns = headerColumns;
        return this;
    }

    /**
    * 设置行过滤条件 — 写入时跳过不满足条件的行。
    * <p>所有表格类写入实现（CSV、JSON、XML、TXT、Excel、DBF 等）
    * 在遍历数据行写入前调用 {@link #testRow(Map)} 判断是否跳过。</p>
    *
    * @param filter 行过滤谓词（接收一行 Map 数据，返回 true 则写入）
    * @return 当前构建器
    */
    public WriteBuilder filter(Predicate<Map<String, Object>> filter) {
        this.rowFilter = filter;
        return this;
    }

    /**
    * 判断指定行是否应被写入（当 rowFilter 为 null 时始终返回 true）。
    * <p>子类在写入每行前调用此方法，返回 {@code false} 时跳过该行。</p>
    *
    * @param row 待写入的行数据
    * @return true 表示应写入，false 表示跳过
    */
    protected boolean testRow(Map<String, Object> row) {
        return rowFilter == null || rowFilter.test(row);
    }

    /**
    * 通用写入接口（支持链式调用）。
    *
    * <p>子类应覆盖此方法以处理具体类型的数据。默认实现将数据存入 {@link #pending} 暂存区。</p>
    *
    * @param data 待写入数据
    * @return 当前构建器
    */
    public WriteBuilder write(Object data) {
        pending.add(data);
        return this;
    }

    /**
    * 将 POJO 对象转为 Map。
    * <p>子类在 {@link #write(Object)} 中处理非 Map 对象时调用此方法进行转换。</p>
    *
    * @param data 待写入的 POJO 对象
    * @return 转换后的 Map
    */
@SuppressWarnings("unchecked")
    protected Map<String, Object> toMap(Object data) {
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return BeanUtils.objectToMap(data);
    }

    /**
    * 将 POJO 对象列表批量转为 Map 列表。
    *
    * @param data 待写入的 POJO 对象
    * @return 转换后的 Map 列表
    */
    protected List<Map<String, Object>> toMapList(Object data) {
        if (data instanceof List) {
            return ((List<?>) data).stream()
                    .map(this::toMap)
                    .toList();
        }
        return List.of(toMap(data));
    }

    /**
    * 完成写入并释放资源。
    *
    * <p>在执行写入操作后调用此方法提交写入结果。</p>
    */
    public void finish() {
    }
}
