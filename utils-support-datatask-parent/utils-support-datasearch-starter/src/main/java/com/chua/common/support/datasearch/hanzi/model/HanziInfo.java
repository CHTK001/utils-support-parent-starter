package com.chua.common.support.datasearch.hanzi.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 汉字字典信息模型。
 *
 * <p>对应 chinese-xinhua 字库中单个汉字词条：
 * 汉字、拼音、部首、笔画、释义与更多信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HanziInfo {

    /**
     * 汉字（如：中）
     */
    private final String character;

    /**
     * 拼音（如：zhōng）
     */
    private final String pinyin;

    /**
     * 部首（如：丨）
     */
    private final String radicals;

    /**
     * 笔画数
     */
    private final String strokes;

    /**
     * 释义
     */
    private final String explanation;

    /**
     * 更多信息（如组词、相关词条）
     */
    private final String more;

    /**
     * 创建 HanziInfo 实例
     *
     * @param character   汉字
     * @param pinyin      拼音
     * @param radicals    部首
     * @param strokes     笔画数
     * @param explanation 释义
     * @param more        更多信息
     */
    public HanziInfo(String character, String pinyin, String radicals, String strokes, String explanation, String more) {
        this.character = character;
        this.pinyin = pinyin;
        this.radicals = radicals;
        this.strokes = strokes;
        this.explanation = explanation;
        this.more = more;
    }

    /** 获取汉字 */
    public String getCharacter() {
        return character;
    }

    /** 获取拼音 */
    public String getPinyin() {
        return pinyin;
    }

    /** 获取部首 */
    public String getRadicals() {
        return radicals;
    }

    /** 获取笔画数 */
    public String getStrokes() {
        return strokes;
    }

    /** 获取释义 */
    public String getExplanation() {
        return explanation;
    }

    /** 获取更多信息 */
    public String getMore() {
        return more;
    }

    /**
     * 转换为 Map 用于 JSON 序列化
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("character", character);
        map.put("pinyin", pinyin);
        map.put("radicals", radicals);
        map.put("strokes", strokes);
        map.put("explanation", explanation);
        map.put("more", more);
        return map;
    }
}
