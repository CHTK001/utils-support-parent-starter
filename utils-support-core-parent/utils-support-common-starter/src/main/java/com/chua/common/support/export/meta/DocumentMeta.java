package com.chua.common.support.export.meta;

import com.chua.common.support.collection.wrap.Wraps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档元数据 —— 由 {@code MetaResolver} 链构建的整表结构描述。
 *
 * <p>列以"属性名 → {@link ColumnMeta}"有序保存；{@link #visibleColumns()} 按
 * {@code order} 升序返回未被忽略的列，未显式声明顺序时退化为反射声明顺序。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DocumentMeta {

    /**
     * 元数据可被覆盖的属性。
     */
    public enum Attribute {
        /**
         * 工作表名。
         */
        SHEET_NAME,
        /**
         * 标题。
         */
        TITLE,
        /**
         * 表头行号。
         */
        HEADER_ROW,
        /**
         * 表头开关。
         */
        HEADER,
        /**
         * 冻结表头。
         */
        FREEZE_HEADER,
        /**
         * 自动筛选。
         */
        AUTO_FILTER,
        /**
         * 单表最大行数。
         */
        ROWS_PER_SHEET
    }

    /**
     * 数据行类型。
     */
    private Class<?> type = Object.class;

    /**
     * 工作表名。
     */
    private String sheetName = "";

    /**
     * 标题。
     */
    private String title = "";

    /**
     * 表头行号，从 1 开始。
     */
    private int headerRow = 1;

    /**
     * 是否生成表头。
     */
    private boolean header = true;

    /**
     * 是否冻结表头。
     */
    private boolean freezeHeader = true;

    /**
     * 是否启用自动筛选。
     */
    private boolean autoFilter = true;

    /**
     * 单个工作表最大行数，0 表示不拆分。
     */
    private int rowsPerSheet;

    /**
     * 已声明属性。
     */
    private final java.util.EnumSet<Attribute> defined = java.util.EnumSet.noneOf(Attribute.class);

    /**
     * 列集合：属性名 → 列元数据。
     */
    private final Map<String, ColumnMeta> columns = new LinkedHashMap<>();

    /**
     * 列声明序号，用于稳定排序。
     */
    private final Map<String, Integer> sequence = new LinkedHashMap<>();

    /**
     * 获取数据行类型。
     *
     * @return 行类型
     */
    public Class<?> getType() {
        return type;
    }

    /**
     * 设置数据行类型。
     *
     * @param value 行类型
     * @return 当前对象
     */
    public DocumentMeta type(Class<?> value) {
        if (value != null) {
            this.type = value;
        }
        return this;
    }

    /**
     * 声明工作表名。
     *
     * <p>入参约定：{@code null} 表示<b>显式清空</b>（覆盖解析结果），
     * 空串表示<b>未声明</b>（保留解析结果）。</p>
     *
     * @param value 工作表名
     * @return 当前对象
     */
    public DocumentMeta sheetName(String value) {
        if (value == null) {
            this.sheetName = null;
            this.defined.add(Attribute.SHEET_NAME);
            return this;
        }
        if (!value.isEmpty()) {
            this.sheetName = value;
            this.defined.add(Attribute.SHEET_NAME);
        }
        return this;
    }

    /**
     * 声明标题。
     *
     * <p>入参约定：{@code null} 表示<b>显式清空</b>（覆盖解析结果），
     * 空串表示<b>未声明</b>（保留解析结果）。</p>
     *
     * @param value 标题
     * @return 当前对象
     */
    public DocumentMeta title(String value) {
        if (value == null) {
            this.title = null;
            this.defined.add(Attribute.TITLE);
            return this;
        }
        if (!value.isEmpty()) {
            this.title = value;
            this.defined.add(Attribute.TITLE);
        }
        return this;
    }

    /**
     * 声明表头行号。
     *
     * @param value 行号，从 1 开始
     * @return 当前对象
     */
    public DocumentMeta headerRow(int value) {
        if (value > 0) {
            this.headerRow = value;
            this.defined.add(Attribute.HEADER_ROW);
        }
        return this;
    }

    /**
     * 声明表头开关。
     *
     * @param value 开关
     * @return 当前对象
     */
    public DocumentMeta header(boolean value) {
        this.header = value;
        this.defined.add(Attribute.HEADER);
        return this;
    }

    /**
     * 声明冻结表头。
     *
     * @param value 开关
     * @return 当前对象
     */
    public DocumentMeta freezeHeader(boolean value) {
        this.freezeHeader = value;
        this.defined.add(Attribute.FREEZE_HEADER);
        return this;
    }

    /**
     * 声明自动筛选。
     *
     * @param value 开关
     * @return 当前对象
     */
    public DocumentMeta autoFilter(boolean value) {
        this.autoFilter = value;
        this.defined.add(Attribute.AUTO_FILTER);
        return this;
    }

    /**
     * 声明单表最大行数。
     *
     * @param value 最大行数，0 表示不拆分
     * @return 当前对象
     */
    public DocumentMeta rowsPerSheet(int value) {
        if (value >= 0) {
            this.rowsPerSheet = value;
            this.defined.add(Attribute.ROWS_PER_SHEET);
        }
        return this;
    }

    /**
     * 判断某属性是否被显式声明。
     *
     * @param attribute 属性
     * @return 是否已声明
     */
    public boolean isDefined(Attribute attribute) {
        return defined.contains(attribute);
    }

    /**
     * 获取标题。
     *
     * @return 标题，可能为空串
     */
    public String getTitle() {
        return title == null ? "" : title;
    }

    /**
     * 获取表头行号。
     *
     * @return 表头行号
     */
    public int getHeaderRow() {
        return headerRow;
    }

    /**
     * 是否生成表头。
     *
     * @return 表头开关
     */
    public boolean isHeader() {
        return header;
    }

    /**
     * 是否冻结表头。
     *
     * @return 冻结开关
     */
    public boolean isFreezeHeader() {
        return freezeHeader;
    }

    /**
     * 是否启用自动筛选。
     *
     * @return 筛选开关
     */
    public boolean isAutoFilter() {
        return autoFilter;
    }

    /**
     * 获取单表最大行数。
     *
     * @return 单表最大行数，0 表示不拆分
     */
    public int getRowsPerSheet() {
        return rowsPerSheet;
    }

    /**
     * 注册列。已存在时返回原列元数据，以支持多个解析器叠加写入同一列。
     *
     * @param property 属性名
     * @return 列元数据
     */
    public ColumnMeta column(String property) {
        ColumnMeta meta = columns.get(property);
        if (meta == null) {
            meta = new ColumnMeta();
            meta.setProperty(property);
            columns.put(property, meta);
            sequence.put(property, sequence.size());
        }
        return meta;
    }

    /**
     * 判断列是否存在。
     *
     * @param property 属性名
     * @return 是否存在
     */
    public boolean hasColumn(String property) {
        return columns.containsKey(property);
    }

    /**
     * 移除列。
     *
     * @param property 属性名
     * @return 当前对象
     */
    public DocumentMeta removeColumn(String property) {
        columns.remove(property);
        sequence.remove(property);
        return this;
    }

    /**
     * 获取全部列（含被忽略的）。
     *
     * @return 列集合
     */
    public List<ColumnMeta> allColumns() {
        List<ColumnMeta> list = Wraps.list(columns.size());
        list.addAll(columns.values());
        return list;
    }

    /**
     * 获取可见列（未被忽略），按 order 升序、未声明 order 时按声明顺序。
     *
     * @return 可见列列表
     */
    public List<ColumnMeta> visibleColumns() {
        List<ColumnMeta> list = new ArrayList<>();
        for (ColumnMeta meta : columns.values()) {
            if (!meta.isIgnore()) {
                list.add(meta);
            }
        }
        list.sort(Comparator
                .comparingInt(ColumnMeta::getOrder)
                .thenComparingInt(meta -> sequence.getOrDefault(meta.getProperty(), Integer.MAX_VALUE)));
        return list;
    }

    /**
     * 获取可见列的标题列表。
     *
     * @return 标题列表
     */
    public List<String> headers() {
        List<String> headers = Wraps.list();
        for (ColumnMeta meta : visibleColumns()) {
            headers.add(meta.getName());
        }
        return headers;
    }

    /**
     * 按属性名查找列。
     *
     * @param property 属性名
     * @return 列元数据
     */
    public Optional<ColumnMeta> find(String property) {
        return Optional.ofNullable(columns.get(property));
    }

    /**
     * 获取最终生效的工作表名：显式声明优先，否则回退为类型简单名。
     *
     * @return 工作表名
     */
    public String resolveSheetName() {
        if (sheetName != null && !sheetName.isEmpty()) {
            return sheetName;
        }
        return type == null || type == Object.class ? "Sheet1" : type.getSimpleName();
    }

    private String propertyOf(ColumnMeta meta) {
        return meta.getProperty();
    }

    @Override
    /**
     * 转换为字符串
     * @return 字符串 对象
     */
    public String toString() {
        return "DocumentMeta{type=" + type.getName() + ", sheet='" + resolveSheetName()
                + "', columns=" + headers() + "}";
    }

    /**
     * 获取不可变的列快照（属性名 → 列元数据）。
     *
     * @return 列快照
     */
    public Map<String, ColumnMeta> columnMap() {
        return Collections.unmodifiableMap(columns);
    }
}