package com.chua.tui.support.widgets;

import com.chua.tui.support.MordantHelper;
import com.chua.tui.support.TuiWidget;

/**
 * 内存监控组件。
 * <p>
 * 绑定 id 为 {@code "memory"}，对应 {@code @IpcMethod("/memory")}。
 * 期望返回格式：{@code "45.2|8.2|16"}（使用率|已用GB|总量GB）。
 * 使用 Mordant {@link MordantHelper#panel} 渲染边框面板，
 * {@link MordantHelper#progressBarWithPercent} 渲染颜色进度条。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MemoryWidget extends TuiWidget {

    /** 进度条总长度（字符数） */
    /** Bar_width */
    private static final int BAR_WIDTH = 25;

    /**
     * 构造内存监控组件。
     *
     * @param id    组件标识，默认 {@code "memory"}
     * @param title 组件标题
     */
    public MemoryWidget(String id, String title) {
        super(id, title);
    }

    /**
     * 构造内存监控组件（使用默认 id 和标题）。
     */
    public MemoryWidget() {
        this("memory", "内存使用率");
    }

    @Override
    public String render(String data) {
        String[] parts = parseData(data);
        double usage = Double.parseDouble(parts[0]);
        String used = parts[1];
        String total = parts[2];

        String bar = MordantHelper.progressBarWithPercent(usage, BAR_WIDTH);
        String colorName = getColorName(usage);
        String usedInfo = MordantHelper.color("已用: " + used + " / " + total + " GB", colorName);
        String usageInfo = MordantHelper.color(String.format("使用率: %.1f%%", usage), colorName);

        String content = bar + "\n" + usedInfo + "\n" + usageInfo;
        return MordantHelper.panel(content, getTitle(), "single");
    }

    @Override
    protected String getDefaultData() {
        return "0.0|0|16";
    }

    /**
     * 解析内存数据。
     *
     * @param data 原始数据，格式 "使用率|已用GB|总量GB"
     * @return 解析后的三个部分
     */
    private String[] parseData(String data) {
        if (data == null || data.isEmpty()) {
            return new String[]{"0.0", "0", "16"};
        }
        String[] parts = data.split("\\|");
        if (parts.length < 3) {
            return new String[]{"0.0", "0", "16"};
        }
        return parts;
    }

    /**
     * 根据使用率获取颜色名称。
     *
     * @param usage 使用率
     * @return 颜色名称
     */
    private String getColorName(double usage) {
        if (usage >= 90) {
            return "red";
        } else if (usage >= 70) {
            return "yellow";
        } else {
            return "green";
        }
    }
}
