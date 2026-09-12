package com.chua.common.support.datasearch.idiom.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
* 成语信息模型。
*
* <p>对应 chinese-xinhua 语料中单个成语词条：
* 词形、拼音、首字母缩写、出处、释义与例句。
*
* @author CH
* @since 4.0.0.42
 */
public class IdiomInfo {

    /**
    * 成语（如：守株待兔）
     */
    private final String word;

    /**
    * 拼音（如：shǒu zhū dài tù）
     */
    private final String pinyin;

    /**
    * 首字母缩写（如：szdt）
     */
    private final String abbreviation;

    /**
    * 出处（典籍来源，可能为空）
     */
    private final String derivation;

    /**
    * 释义
     */
    private final String explanation;

    /**
    * 例句（可能为空）
     */
    private final String example;

    /**
    * 创建 idiom信息 实例
    *
    * @param word         成语
    * @param pinyin       拼音
    * @param abbreviation 首字母缩写
    * @param derivation   出处
    * @param explanation  释义
    * @param example      例句
     */
    public IdiomInfo(String word, String pinyin, String abbreviation, String derivation, String explanation, String example) {
        this.word = word;
        this.pinyin = pinyin;
        this.abbreviation = abbreviation;
        this.derivation = derivation;
        this.explanation = explanation;
        this.example = example;
    }

    /**
    * 获取成语
    *
    * @return 获取word的结果
     */
    public String getWord() {
        return word;
    }

    /**
    * 获取拼音
    *
    * @return 获取pinyin的结果
     */
    public String getPinyin() {
        return pinyin;
    }

    /**
    * 获取首字母缩写
    *
    * @return 获取abbreviation的结果
     */
    public String getAbbreviation() {
        return abbreviation;
    }

    /**
    * 获取出处
    *
    * @return 获取derivation的结果
     */
    public String getDerivation() {
        return derivation;
    }

    /**
    * 获取释义
    *
    * @return 获取解释的结果
     */
    public String getExplanation() {
        return explanation;
    }

    /**
    * 获取例句
    *
    * @return 获取example的结果
     */
    public String getExample() {
        return example;
    }

    /**
    * 转换为 映射 用于 JSON 序列化
    *
    * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("word", word);
        map.put("pinyin", pinyin);
        map.put("abbreviation", abbreviation);
        map.put("derivation", derivation);
        map.put("explanation", explanation);
        map.put("example", example);
        return map;
    }
}
