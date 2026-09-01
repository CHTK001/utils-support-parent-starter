package com.chua.deeplearning.support.image;
public enum UnderstandTask {
    CAPTION("<CAPTION>"), DETAILED_CAPTION("<DETAILED_CAPTION>"),
    MORE_DETAILED_CAPTION("<MORE_DETAILED_CAPTION>"),
    OCR("<OCR>"), OCR_WITH_REGION("<OCR_WITH_REGION>"),
    OD("<OD>"), DENSE_REGION_CAPTION("<DENSE_REGION_CAPTION>"),
    CAPTION_TO_PHRASE_GROUNDING("<CAPTION_TO_PHRASE_GROUNDING>"),
    REFERRING_EXPRESSION_SEGMENTATION("<REFERRING_EXPRESSION_SEGMENTATION>"),
    REGION_TO_SEGMENTATION("<REGION_TO_SEGMENTATION>"),
    OPEN_VOCABULARY_DETECTION("<OPEN_VOCABULARY_DETECTION>"),
    REGION_TO_CATEGORY("<REGION_TO_CATEGORY>"),
    REGION_TO_DESCRIPTION("<REGION_TO_DESCRIPTION>"),
    REGION_TO_OCR("<REGION_TO_OCR>"),
    REGION_PROPOSAL("<REGION_PROPOSAL>");
    private final String prompt;
    UnderstandTask(String prompt) { this.prompt = prompt; }
    public String prompt() { return prompt; }
    public String promptWithInput(String input) { return prompt.replace("{input}", input); }
}