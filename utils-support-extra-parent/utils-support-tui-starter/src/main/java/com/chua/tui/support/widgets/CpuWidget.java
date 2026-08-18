package com.chua.tui.support.widgets;

import com.chua.tui.support.MordantHelper;
import com.chua.tui.support.TuiWidget;

/**
 * CPU 监控组件。
 * <p>
 * 绑定 id 为 {@code "cpu"}，对应 {@code @IpcMethod("/cpu")}。
 * 期望返回格式：{@code "45.2"}（百分比数值字符串）。
 * 使用 Mordant {@link MordantHelper#panel} 渲染边框面板，
 * {@link MordantHelper#progressBar} 渲染颜色进度条。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CpuWidget extends TuiWidget {

    /** 进度条总长度（字符数） */
    /** Bar_width */
    private static final int BAR_WIDTH = 25;

    /**
     * 构造 CPU 监控组件。
     *
     * @param id    组件标识，默认 {@code "cpu"}
     * @param title 组件标题
     */
    public CpuWidget(String id, String title) {
        super(id, title);
    }

    /**
     * 构造 CPU 监控组件（使用默认 id 和标题）。
     */
    public CpuWidget() {
        this("cpu", "CPU 使用率");
    }

    @Override
    public String render(String data) {
        double usage = parseUsage(data);
        String bar = MordantHelper.progressBarWithPercent(usage, BAR_WIDTH);
        String colorName = getColorName(usage);
        String label = MordantHelper.color("使用率: ", colorName);
        String value = MordantHelper.color(String.format("%5.1f%%", usage), colorName);

        // 构建面板内容
        String content = bar + "\n" + label + value;

        // 使用 Mordant Panel 包裹
        return MordantHelper.panel(content, getTitle(), "single");
    }

    @Override
    protected String getDefaultData() {
        return "0.0";
    }

    /**
     * 解析使用率数值。
     *
     * @param data 原始数据字符串
     * @return 使用率（0-100）
     */
    private double parseUsage(String data) {
        if (data == null || data.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(data.trim().replace("%", ""));
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
