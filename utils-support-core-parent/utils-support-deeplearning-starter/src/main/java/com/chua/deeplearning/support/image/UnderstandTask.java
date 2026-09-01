package com.chua.deeplearning.support.image;

/**
 * Florence-2 多模态理解任务类型。
 *
 * <p>每个任务对应一个预置的 prompt 模板，调用方无需关心内部字符串。</p>
 *
 * <pre>{@code
 * VirtualClient.create()
 *     .understand(imageBytes, UnderstandTask.CAPTION)
 *     .text();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum UnderstandTask {

    /** 图像整体描述 */
    CAPTION("<CAPTION>"),

    /** 详细描述 */
    DETAILED_CAPTION("<DETAILED_CAPTION>"),

    /** 段落级描述 */
    MORE_DETAILED_CAPTION("<MORE_DETAILED_CAPTION>"),

    /** 提取图中文字 */
    OCR("<OCR>"),

    /** 提取文字 + 位置 */
    OCR_WITH_REGION("<OCR_WITH_REGION>"),

    /** 开放词汇物体检测（返回边界框描述） */
    OD("<OD>"),

    /** 密集区域标注（物体 + 描述） */
    DENSE_REGION_CAPTION("<DENSE_REGION_CAPTION>"),

    /** 定位 caption 中提到的短语 */
    CAPTION_TO_PHRASE_GROUNDING("<CAPTION_TO_PHRASE_GROUNDING>"),

    /** 根据描述定位并分割区域 */
    REFERRING_EXPRESSION_SEGMENTATION("<REFERRING_EXPRESSION_SEGMENTATION>"),

    /** 区域转分割多边形 */
    REGION_TO_SEGMENTATION("<REGION_TO_SEGMENTATION>"),

    /** 开放词汇检测 */
    OPEN_VOCABULARY_DETECTION("<OPEN_VOCABULARY_DETECTION>"),

    /** 区域分类 */
    REGION_TO_CATEGORY("<REGION_TO_CATEGORY>"),

    /** 区域描述 */
    REGION_TO_DESCRIPTION("<REGION_TO_DESCRIPTION>"),

    /** 区域文字提取 */
    REGION_TO_OCR("<REGION_TO_OCR>"),

    /** 区域提议 */
    REGION_PROPOSAL("<REGION_PROPOSAL>");

    /** 对应的 Florence-2 prompt 前缀 */
    private final String prompt;

    UnderstandTask(String prompt) {
        this.prompt = prompt;
    }

    /**
     * 获取原始 prompt 字符串。
     */
    public String prompt() {
        return prompt;
    }

    /**
     * 获取带参版本（用于需要输入的任务）。
     *
     * @param input 任务输入（如描述文本、区域标识）
     */
    public String promptWithInput(String input) {
        return prompt.replace("{input}", input);
    }
}