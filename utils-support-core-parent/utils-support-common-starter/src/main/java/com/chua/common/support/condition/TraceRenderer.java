package com.chua.common.support.condition;

import java.util.List;

/**
 * 分支流程渲染器：把 {@link Step} 序列画成竖向流程图，最后一条用
 * {@code └─} 收尾，命中用 {@code ✓}、未命中用 {@code ✗}、跳过用 {@code -}。
 *
 * @author CH
 * @since 4.0.0.53
 */
final class TraceRenderer {

    /**
     * 步骤类型列宽度（含序号后的点与空格）。
     */
    private static final int KIND_WIDTH = 11;

    /**
     * 业务标签列宽度。
     */
    private static final int LABEL_WIDTH = 16;

    private TraceRenderer() {
    }

    /**
     * 渲染整个流程。
     *
     * @param title   首行标题
     * @param steps   步骤记录
     * @param footers 收尾行（如 result / matched），挂在树末尾
     * @return 多行流程文本
     */
    static String render(String title, List<Step> steps, List<String> footers) {
        StringBuilder out = new StringBuilder(title);
        int total = steps.size() + footers.size();
        for (int i = 0; i < steps.size(); i++) {
            appendNode(out, i == total - 1, renderStep(i, steps.get(i)));
        }
        for (int i = 0; i < footers.size(); i++) {
            appendNode(out, i == footers.size() - 1, footers.get(i));
        }
        return out.toString();
    }

    private static void appendNode(StringBuilder out, boolean last, String text) {
        out.append('\n').append(last ? "└─ " : "├─ ").append(text);
    }

    private static String renderStep(int index, Step step) {
        String label = step.label() == null ? "" : step.label();
        StringBuilder line = new StringBuilder()
                .append(String.format("%d. %-" + KIND_WIDTH + "s %-" + LABEL_WIDTH + "s %s",
                        index + 1, step.kind(), label, step.state().display()));
        if (step.detail() != null) {
            line.append("  ").append(step.detail());
        }
        return line.toString();
    }
}
