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
