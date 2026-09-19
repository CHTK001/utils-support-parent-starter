package com.chua.deeplearning.support.image;

/**
 * Florence-2 视觉理解任务类型枚举。
 *
 * <p>每个枚举值对应 Florence-2 模型的一个 prompt 前缀，用于控制模型执行不同类型的视觉理解任务。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum UnderstandTask {
    /**
     * 生成简短图像描述
    */
    CAPTION("<CAPTION>"),
    /**
     * 生成详细图像描述
    */
    DETAILED_CAPTION("<DETAILED_CAPTION>"),
    /**
     * 生成更详细的图像描述
    */
    MORE_DETAILED_CAPTION("<MORE_DETAILED_CAPTION>"),
    /**
     * 光学字符识别
    */
    OCR("<OCR>"),
    /**
     * 带区域的 OCR
    */
    OCR_WITH_REGION("<OCR_WITH_REGION>"),
    /**
     * 开放词汇目标检测
    */
    OD("<OD>"),
    /**
     * 密集区域 caption
    */
    DENSE_REGION_CAPTION("<DENSE_REGION_CAPTION>"),
    /**
     * Caption 到短语接地
    */
    CAPTION_TO_PHRASE_GROUNDING("<CAPTION_TO_PHRASE_GROUNDING>"),
    /**
     * 指代表达式分割
    */
    REFERRING_EXPRESSION_SEGMENTATION("<REFERRING_EXPRESSION_SEGMENTATION>"),
    /**
     * 区域到分割
    */
    REGION_TO_SEGMENTATION("<REGION_TO_SEGMENTATION>"),
    /**
     * 开放词汇检测
    */
    OPEN_VOCABULARY_DETECTION("<OPEN_VOCABULARY_DETECTION>"),
    /**
     * 区域到类别
    */
    REGION_TO_CATEGORY("<REGION_TO_CATEGORY>"),
    /**
     * 区域到描述
    */
    REGION_TO_DESCRIPTION("<REGION_TO_DESCRIPTION>"),
    /**
     * 区域到 OCR
    */
    REGION_TO_OCR("<REGION_TO_OCR>"),
    /**
     * 区域提议
    */
    REGION_PROPOSAL("<REGION_PROPOSAL>");

    /**
     * 提示符 前缀
    */
    private final String prompt;

    /**
     * 构造方法，创建 UnderstandTask 实例。
     *
     * @param prompt 提示词，不允许为 null
     */
    UnderstandTask(String prompt) {
        this.prompt = prompt;
    }

    /**
     * 获取 提示符 前缀。
     *
     * @return prompt 前缀字符串
     */
    public String prompt() {
        return prompt;
    }

    /**
     * 将 输入 插入 提示符，生成完整提示词。
     *
     * @param input 输入文本，替换 提示符 中的 {输入} 占位符
     * @return 完整 提示符
     */
    public String promptWithInput(String input) {
        return prompt.replace("{input}", input);
    }
}
