package com.chua.tui.support.widgets;

import com.chua.tui.support.MordantHelper;
import com.chua.tui.support.TuiWidget;

/**
 * 磁盘监控组件。
 * <p>
 * 绑定 id 为 {@code "disk"}，对应 {@code @IpcMethod("/disk")}。
 * 期望返回格式：多行，每行 {@code "分区名|已用GB|总量GB|使用率"}。
 * 使用 Mordant {@link MordantHelper#panel} 渲染边框面板，
 * {@link MordantHelper#progressBar} 渲染每个分区的使用率条。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DiskWidget extends TuiWidget {

    /** 进度条总长度（字符数） */
    /** Bar_width */
    private static final int BAR_WIDTH = 15;

    /**
     * 构造磁盘监控组件。
     *
     * @param id    组件标识，默认 {@code "disk"}
     * @param title 组件标题
     */
    public DiskWidget(String id, String title) {
        super(id, title);
    }

    /**
     * 构造磁盘监控组件（使用默认 id 和标题）。
     */
    public DiskWidget() {
        this("disk", "磁盘使用率");
    }

    @Override
    public String render(String data) {
        if (data == null || data.isEmpty() || "N/A".equals(data)) {
            return MordantHelper.panel("无磁盘数据", getTitle(), "single");
        }

        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");

        for (String line : lines) {
            String[] parts = line.split("\\|");
            if (parts.length < 4) {
                continue;
            }
            String mount = parts[0].trim();
            String used = parts[1].trim();
            String total = parts[2].trim();
            double usage = parseDoubleSafe(parts[3]);

            String bar = MordantHelper.progressBar(usage, BAR_WIDTH);
            String colorName = getColorName(usage);
            String mountLabel = MordantHelper.bold(mount);
            String usageLabel = MordantHelper.color(
                    String.format("%5.1f%%", usage), colorName);

            sb.append(mountLabel).append(" ")
                    .append(used).append("/").append(total).append("GB\n")
                    .append(bar).append(" ").append(usageLabel).append("\n");
        }

        if (sb.isEmpty()) {
            return MordantHelper.panel("无磁盘数据", getTitle(), "single");
        }
        return MordantHelper.panel(sb.toString().trim(), getTitle(), "single");
    }

    @Override
    protected String getDefaultData() {
        return "C:\\|0|0|0.0";
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
        if (usage >= 90) {
            return "red";
        } else if (usage >= 75) {
            return "yellow";
        } else {
            return "green";
        }
    }
}
