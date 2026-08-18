package com.chua.tui.support.widgets;

import com.chua.tui.support.MordantHelper;
import com.chua.tui.support.TuiWidget;

/**
 * Htop 风格综合监控组件。
 * <p>
 * 绑定 id 为 {@code "htop"}，对应 {@code @IpcMethod("/htop")}。
 * 期望返回格式（多行，竖线分隔）：
 * <pre>
 * cpuUsage|memUsage|memUsed|memTotal
 * processName|pid|cpu|mem
 * ...
 * </pre>
 * 使用 Mordant {@link MordantHelper#panel} 渲染边框面板，
 * {@link MordantHelper#progressBar} 渲染 CPU/内存进度条。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HtopWidget extends TuiWidget {

    /** CPU 进度条长度 */
    /** Cpu_bar_width */
    private static final int CPU_BAR_WIDTH = 10;

    /** 内存进度条长度 */
    /** Mem_bar_width */
    private static final int MEM_BAR_WIDTH = 10;

    /** 最大显示进程数 */
    /** Max_processes */
    private static final int MAX_PROCESSES = 8;

    /**
     * 构造 htop 综合监控组件。
     *
     * @param id    组件标识，默认 {@code "htop"}
     * @param title 组件标题
     */
    public HtopWidget(String id, String title) {
        super(id, title);
    }

    /**
     * 构造 htop 综合监控组件（使用默认 id 和标题）。
     */
    public HtopWidget() {
        this("htop", "系统监控 (htop)");
    }

    @Override
    public String render(String data) {
        if (data == null || data.isEmpty() || "N/A".equals(data)) {
            return MordantHelper.panel("等待数据...", getTitle(), "single");
        }

        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();

        // 第一行：系统概览
        if (lines.length > 0) {
            String[] overview = lines[0].split("\\|");
            if (overview.length >= 4) {
                double cpuUsage = parseDoubleSafe(overview[0]);
                double memUsage = parseDoubleSafe(overview[1]);
                String memUsed = overview[2].trim();
                String memTotal = overview[3].trim();

                // CPU 行
                sb.append(MordantHelper.bold(" CPU"))
                        .append(" [").append(MordantHelper.progressBar(cpuUsage, CPU_BAR_WIDTH))
                        .append("]").append(MordantHelper.color(
                                String.format(" %5.1f%%", cpuUsage), getColorName(cpuUsage)))
                        .append("\n");

                // 内存行
                sb.append(MordantHelper.bold(" MEM"))
                        .append(" [").append(MordantHelper.progressBar(memUsage, MEM_BAR_WIDTH))
                        .append("]").append(MordantHelper.color(
                                String.format(" %s/%s GB %5.1f%%", memUsed, memTotal, memUsage),
                                getColorName(memUsage)))
                        .append("\n");
            }
        }

        // 分隔线 + 表头
        sb.append(MordantHelper.dim(" PID    USER        CPU  MEM  NAME\n"));

        // 进程列表
        int processCount = 0;
        for (int i = 1; i < lines.length && processCount < MAX_PROCESSES; i++) {
            String[] parts = lines[i].split("\\|");
            if (parts.length < 4) {
                continue;
            }
            String name = parts[0].trim();
            String pid = parts[1].trim();
            String cpu = parts[2].trim();
            String mem = parts[3].trim();

            // 截断过长的进程名
            if (name.length() > 12) {
                name = name.substring(0, 10) + "..";
            }

            sb.append(String.format(" %6s %-12s %4s%% %4s%% %s\n",
                    pid, "root", cpu, mem, name));
            processCount++;
        }

        if (processCount == 0) {
            sb.append(" ").append(MordantHelper.dim("无进程数据")).append("\n");
        }

        return MordantHelper.panel(sb.toString().trim(), getTitle(), "single");
    }

    @Override
    protected String getDefaultData() {
        return "0.0|0.0|0|16\njava|1|0.0|0.0";
    }

    /**
     * 安全解析 double。
     *
     * @param value 字符串值
     * @return double 值，解析失败返回 0
     */
    private double parseDoubleSafe(String value) {
        try {
            return Double.parseDouble(value.trim().replace("%", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 根据使用率获取颜色名称。
     *
     * @param usage 使用率
     * @return 颜色名称
     */
    private String getColorName(double usage) {
        if (usage >= 80) {
            return "red";
        } else if (usage >= 50) {
            return "yellow";
        } else {
            return "green";
        }
    }
}
