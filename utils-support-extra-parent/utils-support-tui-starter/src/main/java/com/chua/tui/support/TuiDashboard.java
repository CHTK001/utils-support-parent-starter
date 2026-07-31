package com.chua.tui.support;

import com.github.ajalt.mordant.terminal.Terminal;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端仪表盘引擎。
 * <p>
 * 管理网格布局中的多个 {@link TuiWidget} 组件，通过内部处理器注册表获取实时数据，
 * 使用 Mordant {@link Terminal} 渲染到终端，支持定时刷新和键盘交互。
 * </p>
 * <p>
 * 使用方式：
 * <pre>{@code
 * TuiDashboard dashboard = TuiDashboardBuilder.create()
 *     .layout(TuiLayout.GRID_2x2)
 *     .title("系统监控")
 *     .registerHandler(new SystemMonitorService())
 *     .addWidget(new CpuWidget())
 *     .addWidget(new MemoryWidget())
 *     .refreshInterval(2000)
 *     .build();
 * dashboard.start();
 * dashboard.waitForExit();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TuiDashboard {

    /** 布局规格 */
    private final TuiLayout layout;

    /** 仪表盘标题 */
    @Getter
    private final String title;

    /** 组件列表（按添加顺序排列） */
    private final List<TuiWidget> widgets;

    /** 处理器方法注册表（路径 → 方法） */
    private final Map<String, Method> handlerMethods;

    /** 处理器目标对象注册表（路径 → 目标对象） */
    private final Map<String, Object> handlerTargets;

    /** 刷新间隔（毫秒） */
    private final long refreshInterval;

    /** Mordant 终端实例，自动检测终端能力 */
    private final Terminal terminal;

    /** 定时刷新线程池 */
    private ScheduledExecutorService scheduler;

    /** 键盘输入监听线程 */
    private Thread inputThread;

    /** 运行状态标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造仪表盘。
     *
     * @param layout          布局规格
     * @param title           仪表盘标题
     * @param widgets         组件列表
     * @param handlerMethods  处理器方法注册表
     * @param handlerTargets  处理器目标对象注册表
     * @param refreshInterval 刷新间隔（毫秒）
     */
    TuiDashboard(TuiLayout layout, String title, List<TuiWidget> widgets,
                 Map<String, Method> handlerMethods, Map<String, Object> handlerTargets,
                 long refreshInterval) {
        this.layout = layout;
        this.title = title;
        this.widgets = new ArrayList<>(widgets);
        this.handlerMethods = handlerMethods;
        this.handlerTargets = handlerTargets;
        this.refreshInterval = refreshInterval;
        this.terminal = MordantHelper.createTerminal();
    }

    /**
     * 启动仪表盘。
     * <p>
     * 初次渲染后启动定时刷新循环和键盘监听线程。
     * 按 q 键退出，按 r 键手动刷新。
     * </p>
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("仪表盘已在运行中");
            return;
        }

        log.info("启动终端仪表盘: {} ({}x{})", title, layout.getCols(), layout.getRows());

        // 初次渲染
        renderAll();

        // 启动定时刷新
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tui-dashboard-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::renderAll,
                refreshInterval, refreshInterval, TimeUnit.MILLISECONDS);

        // 启动键盘监听线程
        startInputListener();
    }

    /**
     * 启动键盘输入监听。
     * <p>
     * 在独立线程中读取标准输入，处理按键事件。
     * 按 q 退出，按 r 手动刷新。
     * </p>
     */
    private void startInputListener() {
        this.inputThread = new Thread(() -> {
            try {
                while (running.get()) {
                    int ch = System.in.read();
                    if (ch == 'q' || ch == 'Q') {
                        log.info("检测到 q 键，停止仪表盘");
                        stop();
                        break;
                    } else if (ch == 'r' || ch == 'R') {
                        renderAll();
                    }
                }
            } catch (Exception e) {
                // 输入流关闭或中断，正常退出
            }
        }, "tui-dashboard-input");
        this.inputThread.setDaemon(true);
        this.inputThread.start();
    }

    /**
     * 等待仪表盘退出。
     * <p>
     * 阻塞当前线程直到用户按 q 键退出。
     * </p>
     */
    public void waitForExit() {
        try {
            while (running.get()) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }
    }

    /**
     * 停止仪表盘。
     * <p>
     * 停止定时刷新和键盘监听，释放资源。
     * </p>
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("终端仪表盘已停止: {}", title);
    }

    /**
     * 判断仪表盘是否正在运行。
     *
     * @return true 表示正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 渲染全部组件。
     * <p>
     * 遍历所有组件，从处理器获取数据后设置到组件，然后渲染。
     * 使用 Mordant {@link Terminal#clearScreen()} 清除屏幕实现原地刷新。
     * </p>
     */
    private void renderAll() {
        try {
            // 刷新所有组件数据
            for (TuiWidget widget : widgets) {
                String data = resolveData(widget.getId());
                widget.setRawData(data);
                widget.refresh();
            }

            // 使用 ANSI 清屏
            MordantHelper.clearScreen(terminal);

            // 构建输出
            StringBuilder sb = new StringBuilder();

            // 标题栏
            sb.append(MordantHelper.bold(" " + title)).append("\n");
            sb.append(MordantHelper.dim(
                    " ─" + "─".repeat(Math.max(0, 50 - title.length())))).append("\n\n");

            // 按网格布局排列组件
            int cols = layout.getCols();
            List<List<TuiWidget>> rows = new ArrayList<>();
            List<TuiWidget> currentRow = new ArrayList<>();
            for (int i = 0; i < widgets.size(); i++) {
                currentRow.add(widgets.get(i));
                if (currentRow.size() == cols) {
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
            }
            if (!currentRow.isEmpty()) {
                rows.add(currentRow);
            }

            // 渲染每一行
            for (List<TuiWidget> row : rows) {
                for (TuiWidget widget : row) {
                    sb.append(widget.render(widget.getRawData()));
                }
                sb.append("\n");
            }

            // 底部操作提示
            sb.append("\n").append(MordantHelper.dim(
                    " [q] 退出  [r] 手动刷新  "))
                    .append(MordantHelper.dim(
                            "刷新间隔: " + refreshInterval + "ms  "))
                    .append(MordantHelper.dim(
                            "布局: " + layout.getCols() + "x" + layout.getRows()));

            // 输出到终端
            MordantHelper.println(terminal, sb.toString());
        } catch (Exception e) {
            log.error("渲染仪表盘异常", e);
        }
    }

    /**
     * 根据组件 id 从处理器注册表获取数据。
     * <p>
     * 查找路径 {@code "/" + widgetId} 对应的处理器方法并调用。
     * 如果未找到处理器，返回默认值。
     * </p>
     *
     * @param widgetId 组件标识
     * @return 处理器返回的数据字符串
     */
    private String resolveData(String widgetId) {
        String path = "/" + widgetId;
        Method method = handlerMethods.get(path);
        if (method == null) {
            return null;
        }
        Object target = handlerTargets.get(path);
        if (target == null) {
            return null;
        }
        try {
            if (method.getParameterCount() == 0) {
                Object result = method.invoke(target);
                return result != null ? result.toString() : null;
            } else if (method.getParameterCount() == 1) {
                Object result = method.invoke(target, "");
                return result != null ? result.toString() : null;
            }
        } catch (Exception e) {
            log.warn("处理器调用失败: {}, path={}", e.getMessage(), path);
        }
        return null;
    }
}
