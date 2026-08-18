package com.chua.tui.support;

import com.chua.common.support.network.ipc.parser.IpcAddressParser;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端仪表盘链式构建器。
 * <p>
 * 类似 {@link com.chua.common.support.network.server.ServerBuilder} 的链式风格，
 * 通过一系列方法调用完成配置后构建 {@link TuiDashboard} 实例。
 * </p>
 * <p>
 * 使用方式：
 * <pre>{@code
 * TuiDashboard dashboard = TuiDashboardBuilder.create()
 *     .layout(TuiLayout.GRID_2x2)
 *     .title("系统监控")
 *     .registerHandler(new SystemMonitorService())
 *     .addWidget(new CpuWidget("cpu", "CPU"))
 *     .addWidget(new MemoryWidget("mem", "内存"))
 *     .refreshInterval(2000)
 *     .build();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TuiDashboardBuilder {

    /** 布局规格，默认 2x2 */
    /** Layout */
    private TuiLayout layout = TuiLayout.GRID_2x2;

    /** 仪表盘标题 */
    /** 标题 */
    private String title = "仪表盘";

    /** 组件列表 */
    /** Widgets */
    private final List<TuiWidget> widgets = new ArrayList<>();

    /** 处理器方法注册表（路径 → 方法） */
    private final Map<String, Method> handlerMethods = new LinkedHashMap<>();

    /** 处理器目标对象注册表（路径 → 目标对象） */
    private final Map<String, Object> handlerTargets = new LinkedHashMap<>();

    /** IpcMethod 注解解析器 */
    /** 解析器 */
    private final IpcAddressParser parser = new IpcAddressParser();

    /** 刷新间隔（毫秒），默认 3 秒 */
    /** Refresh间隔 */
    private long refreshInterval = 3000L;

    /** 创建时默认显示的组件 id 列表（为空时全部显示） */
    /** VisiblewidgetIDS */
    private final List<String> visibleWidgetIds = new ArrayList<>();

    /** 创建时默认隐藏的组件 id 列表 */
    /** HiddenwidgetIDS */
    private final List<String> hiddenWidgetIds = new ArrayList<>();

    /** 私有构造方法，通过 {@link #create()} 创建 */
    private TuiDashboardBuilder() {
    }

    /**
     * 创建构建器实例。
     *
     * @return TuiDashboardBuilder
     */
    public static TuiDashboardBuilder create() {
        return new TuiDashboardBuilder();
    }

    /**
     * 设置仪表盘布局。
     *
     * @param layout 网格布局规格
     * @return this
     */
    public TuiDashboardBuilder layout(TuiLayout layout) {
        this.layout = layout;
        return this;
    }

    /**
     * 设置仪表盘标题。
     *
     * @param title 标题文本
     * @return this
     */
    public TuiDashboardBuilder title(String title) {
        this.title = title;
        return this;
    }

    /**
     * 注册包含 {@code @IpcMethod} 注解的数据处理器。
     *
     * @param handler 处理器对象（包含 @IpcMethod 注解方法）
     * @return this
     */
    public TuiDashboardBuilder registerHandler(Object handler) {
        if (handler == null) {
            return this;
        }
        Map<String, Method> parsed = parser.parse(handler);
        for (Map.Entry<String, Method> entry : parsed.entrySet()) {
            String path = entry.getKey();
            handlerMethods.put(path, entry.getValue());
            handlerTargets.put(path, handler);
            log.debug("已注册处理器: {} -> {}.{}()",
                    path, handler.getClass().getSimpleName(), entry.getValue().getName());
        }
        return this;
    }

    /**
     * 添加仪表盘组件。
     *
     * @param widget 仪表盘组件
     * @return this
     */
    public TuiDashboardBuilder addWidget(TuiWidget widget) {
        if (widget != null) {
            this.widgets.add(widget);
        }
        return this;
    }

    /**
     * 设置刷新间隔。
     *
     * @param intervalMillis 刷新间隔（毫秒）
     * @return this
     */
    public TuiDashboardBuilder refreshInterval(long intervalMillis) {
        this.refreshInterval = intervalMillis;
        return this;
    }

    /**
     * 指定创建时默认显示的组件 id。
     *
     * @param ids 组件 id 列表
     * @return this
     */
    public TuiDashboardBuilder showOnStart(String... ids) {
        for (String id : ids) {
            if (id != null && !id.isEmpty()) {
                this.visibleWidgetIds.add(id);
            }
        }
        return this;
    }

    /**
     * 指定创建时默认隐藏的组件 id。
     *
     * @param ids 组件 id 列表
     * @return this
     */
    public TuiDashboardBuilder hideOnStart(String... ids) {
        for (String id : ids) {
            if (id != null && !id.isEmpty()) {
                this.hiddenWidgetIds.add(id);
            }
        }
        return this;
    }

    /**
     * 构建仪表盘实例。
     *
     * @return TuiDashboard 实例
     * @throws IllegalStateException 组件数量超出布局容量时抛出
     */
    public TuiDashboard build() {
        int capacity = layout.getCapacity();
        if (capacity > 0 && widgets.size() > capacity) {
            throw new IllegalStateException(
                    "组件数量(" + widgets.size() + ") 超出布局容量(" + capacity + ")");
        }

        TuiDashboard dashboard = new TuiDashboard(
                layout, title, widgets, handlerMethods, handlerTargets, refreshInterval);

        if (!visibleWidgetIds.isEmpty()) {
            for (TuiWidget widget : widgets) {
                widget.setVisible(visibleWidgetIds.contains(widget.getId()));
            }
        } else if (!hiddenWidgetIds.isEmpty()) {
            for (TuiWidget widget : widgets) {
                widget.setVisible(!hiddenWidgetIds.contains(widget.getId()));
            }
        }

        return dashboard;
    }
}
