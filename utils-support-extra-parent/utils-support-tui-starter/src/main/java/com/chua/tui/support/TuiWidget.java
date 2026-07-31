package com.chua.tui.support;

import lombok.Getter;

/**
 * 终端仪表盘组件基类。
 * <p>
 * 每个组件绑定一个唯一的 {@code id}，该 id 对应 {@code @IpcMethod} 注解中的路径。
 * 组件的数据由 {@link TuiDashboard} 通过内部处理器注册表获取，
 * 再调用 {@link #render(String)} 将数据渲染为终端文本。
 * </p>
 * <p>
 * 子类只需实现 {@link #render(String)} 方法，将来自处理器的方法返回值渲染为终端文本。
 * 内置的预置组件（CpuWidget、MemoryWidget 等）可直接使用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public abstract class TuiWidget {

    /** 组件唯一标识，对应 {@code @IpcMethod} 中的路径值 */
    private final String id;

    /** 组件标题，显示在仪表盘面板顶部 */
    private final String title;

    /** 上一次渲染的原始数据 */
    private String rawData;

    /** 组件是否可见 */
    private boolean visible = true;

    /** 跨列数，默认为 1 */
    private int colspan = 1;

    /** 跨行数，默认为 1 */
    private int rowspan = 1;

    /**
     * 构造组件。
     *
     * @param id    组件标识，对应 {@code @IpcMethod("id")}
     * @param title 组件显示标题
     */
    protected TuiWidget(String id, String title) {
        this.id = id;
        this.title = title;
    }

    /**
     * 设置原始数据。
     * <p>
     * 由 {@link TuiDashboard} 在每次刷新时调用。
     * </p>
     *
     * @param rawData 来自处理器的最新数据
     */
    public void setRawData(String rawData) {
        this.rawData = rawData;
    }

    /**
     * 组件是否可见。
     *
     * @return true 表示可见
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * 设置组件是否可见。
     *
     * @param visible 是否可见
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * 获取跨列数。
     *
     * @return colspan
     */
    public int getColspan() {
        return colspan;
    }

    /**
     * 设置跨列数，最小值为 1。
     *
     * @param colspan 跨列数
     */
    public void setColspan(int colspan) {
        if (colspan < 1) {
            this.colspan = 1;
        } else {
            this.colspan = colspan;
        }
    }

    /**
     * 获取跨行数。
     *
     * @return rowspan
     */
    public int getRowspan() {
        return rowspan;
    }

    /**
     * 设置跨行数，最小值为 1。
     *
     * @param rowspan 跨行数
     */
    public void setRowspan(int rowspan) {
        if (rowspan < 1) {
            this.rowspan = 1;
        } else {
            this.rowspan = rowspan;
        }
    }

    /**
     * 刷新组件数据。
     * <p>
     * 子类可覆盖此方法进行数据预处理。
     * 默认实现为空操作，数据由 Dashboard 通过 {@link #setRawData(String)} 注入。
     * </p>
     */
    public void refresh() {
        // 默认无操作，数据由 Dashboard 注入
    }

    /**
     * 渲染组件内容。
     * <p>
     * 子类实现此方法，将原始数据渲染为带格式的终端文本。
     * </p>
     *
     * @param data 来自处理器的原始数据
     * @return 渲染后的终端文本（含 ANSI 转义码）
     */
    public abstract String render(String data);

    /**
     * 渲染组件内容（带宽度约束）。
     * <p>
     * 默认实现委托给 {@link #render(String)}，忽略宽度参数。
     * 子类可覆盖此方法，根据可用宽度调整输出格式。
     * </p>
     *
     * @param data  来自处理器的原始数据
     * @param width 可用宽度（字符数）
     * @return 渲染后的终端文本（含 ANSI 转义码）
     */
    public String render(String data, int width) {
        return render(data);
    }

    /**
     * 获取默认数据。
     * <p>
     * 当处理器不可用或请求失败时使用此方法返回降级数据。
     * 子类可覆盖此方法提供有意义的默认值。
     * </p>
     *
     * @return 默认数据字符串
     */
    protected String getDefaultData() {
        return "N/A";
    }
}
