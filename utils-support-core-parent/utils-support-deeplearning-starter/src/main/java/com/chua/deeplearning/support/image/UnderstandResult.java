package com.chua.deeplearning.support.image;

/**
 * 视觉理解任务结果。
 *
 * <p>封装模型对图像的理解输出，包含任务类型和识别文本。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class UnderstandResult {

    /** 执行的任务类型 */
    private final UnderstandTask task;
    /** 模型返回的文本结果 */
    private final String text;

    /**
     * 创建理解结果。
     *
     * @param task 执行的任务类型
     * @param text 模型返回文本，可为 null（自动 trim 为 null）
     */
    public UnderstandResult(UnderstandTask task, String text) {
        this.task = task;
        this.text = text != null ? text.trim() : "";
    }

    /**
     * 获取任务类型。
     *
     * @return 任务类型
     */
    public UnderstandTask getTask() {
        return task;
    }

    /**
     * 获取识别文本。
     *
     * @return 识别文本
     */
    public String getText() {
        return text;
    }

    /**
     * 判断是否有有效结果（非空文本）。
     *
     * @return 有有效结果返回 true
     */
    public boolean hasResult() {
        return text != null && !text.isBlank();
    }
}
