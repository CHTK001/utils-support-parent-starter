package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * 表格视图解析器，将结构化数据渲染为终端 ASCII 表格。
 * <p>
 * 支持的数据类型：
 * <ul>
 *   <li>{@link List} / 数组 / {@link Iterable} — 每元素一行，字段映射为列</li>
 *   <li>{@link Map} — 键值对画为两列表</li>
 *   <li>POJO — 单行表格展示所有字段</li>
 * </ul>
 * </p>
 *
 * <pre>{@code
 * ┌───────┬───────────┐
 * │ Name  │ Value     │
 * ├───────┼───────────┤
 * │ foo   │ 123       │
 * │ bar   │ 456       │
 * └───────┴───────────┘
 * }</pre>
 *
 * <p>通过 {@code setBorderless(true)} 可切换为无边框模式，仅按列宽对齐，
 * 不绘制任何框线字符：</p>
 *
 * <pre>{@code
 * Name   Value
 * foo    123
 * bar    456
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("table")
public class TableViewParser implements ViewParser {

    /** 单元格左右内边距（空格数） */
    private static final int PAD = 1;

    /**
    * 是否以无边框模式渲染：true 时不绘制框线字符，仅按列宽以空格对齐
    */
    private final boolean borderless;

    /**
     * 默认构造器：框线模式。
     */
    public TableViewParser() {
        this(false);
    }

    /**
     * 构造器。
     *
     * @param borderless true 表示无边框模式，false 表示默认框线模式
     */
    private TableViewParser(boolean borderless) {
        this.borderless = borderless;
    }

    /**
     * 返回指定无边框模式的新实例（不修改当前单例）。
     *
     * <p>由于 SPI 实例是全局单例，此方法返回一个新实例以避免污染共享状态。</p>
     *
     * @param borderless true 表示无边框模式，false 表示默认框线模式
     * @return 新的 TableViewParser 实例
     */
    public TableViewParser setBorderless(boolean borderless) {
        return new TableViewParser(borderless);
    }

    /**
     * 判断是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return {@link Iterable} / {@link Map} / 数组 返回 true
     */
    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data instanceof Map || data.getClass().isArray();
    }

    /**
     * 将数据渲染为终端 ASCII 表格。
     *
     * @param data 待渲染的数据
     * @return 表格字符串；空数据返回 {@link ViewFormatter#EMPTY_PLACEHOLDER}
     */
    @Override
    public String render(Object data) {
        List<String[]> rows = ViewFormatter.extractRows(data);
        if (rows.isEmpty()) {
            return ViewFormatter.EMPTY_PLACEHOLDER;
        }
        return borderless ? ViewFormatter.drawBorderlessTable(rows, PAD) : ViewFormatter.drawBoxedTable(rows, PAD);
    }

    /**
     * 获取解析器顺序。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
