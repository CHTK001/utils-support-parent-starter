package com.chua.deeplearning.support.image;

import java.util.Locale;

/**
 * 指令式视觉语言模型任务类型。
 *
 * <p>与 {@link UnderstandTask}（Florence-2 的 prompt 前缀枚举）并列：{@code UnderstandTask}
 * 描述「用哪段固定前缀驱动模型」，本枚举描述「让模型做什么事」，由具体实现翻译成自己的
 * 指令（如 WeVisOne-VL 的 {@code detection} / {@code matting}）。
 *
 * <p>约定：{@link #prompt()} 返回实现侧约定的任务键（小写、下划线分隔），
 * 实现不得擅自改名，否则跨语言服务无法对齐。
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum VlmTask {
    /**
     * 按描述定位全部同类实例，返回检测框
     */
    DETECTION("detection"),
    /**
     * 指代定位：按一句话描述定位目标实例
     */
    REFERRING("referring"),
    /**
     * 指向：返回目标中心点而非框
     */
    POINT("point"),
    /**
     * 计数（带框）：返回数量并给出每个实例的框
     */
    COUNT_BOX("count_box"),
    /**
     * 计数（带点）：返回数量并给出每个实例的中心点
     */
    COUNT_POINT("count_point"),
    /**
     * 指代分割：返回框与二值/概率掩码
     */
    SEGMENTATION("segmentation"),
    /**
     * 抠图：返回框与 alpha matte（连续透明度）
     */
    MATTING("matting"),
    /**
     * 整图文字识别
     */
    OCR("ocr"),
    /**
     * 按文字定位：定位图中指定文字所在框
     */
    OCR_GROUNDING("ocr_grounding"),
    /**
     * 视觉提示：给定参考框，定位图中相似目标
     */
    VISUAL_PROMPT("visual_prompt"),
    /**
     * 自由指令：原始 query 直接作为提示词
     */
    FREEFORM("freeform");

    /**
     * 实现侧任务键
     */
    private final String prompt;

    /**
     * 构造方法。
     *
     * @param prompt 任务键（小写、下划线分隔）
     */
    VlmTask(String prompt) {
        this.prompt = prompt;
    }

    /**
     * 获取任务键。
     *
     * @return 任务键
     */
    public String prompt() {
        return prompt;
    }

    /**
     * 按任务键解析枚举（忽略大小写）。
     *
     * @param prompt 任务键
     * @return 匹配的枚举；未匹配返回 {@code null}
     */
    public static VlmTask of(String prompt) {
        if (prompt == null) {
            return null;
        }
        String normalized = prompt.trim().toLowerCase(Locale.ROOT);
        for (VlmTask task : values()) {
            if (task.prompt.equals(normalized) || task.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return task;
            }
        }
        return null;
    }
}