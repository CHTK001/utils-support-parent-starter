package com.chua.common.support.datasearch.poetry.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 古诗词信息模型。
*
* <p>对应 chinese-poetry 全唐诗语料中单首诗词：
* 标题、作者、朝代与正文段落。
*
* @author CH
* @since 4.0.0.42
 */
public class PoetryInfo {

    /**
    * 标题（如：静夜思）
    */
    private final String title;

    /**
    * 作者（如：李白）
    */
    private final String author;

    /**
    * 朝代（如：唐代）
    */
    private final String dynasty;

    /**
    * 正文段落（每句或每行）
    */
    private final List<String> paragraphs;

    /**
    * 创建 poetry信息 实例
    *
    * @param title      标题
    * @param author     作者
    * @param dynasty    朝代
    * @param paragraphs 正文段落
    */
    public PoetryInfo(String title, String author, String dynasty, List<String> paragraphs) {
        this.title = title;
        this.author = author;
        this.dynasty = dynasty;
        this.paragraphs = paragraphs;
    }

    /**
    * 获取标题
    *
    * @return 获取title的结果
    */
    public String getTitle() {
        return title;
    }

    /**
    * 获取作者
    *
    * @return 获取作者的结果
    */
    public String getAuthor() {
        return author;
    }

    /**
    * 获取朝代
    *
    * @return 获取dynasty的结果
    */
    public String getDynasty() {
        return dynasty;
    }

    /**
    * 获取正文段落
    *
    * @return 获取paragraphs的结果
    */
    public List<String> getParagraphs() {
        return paragraphs;
    }

    /**
    * 获取正文全文（段落以换行连接）
    *
    * @return 全文
    */
    public String getContent() {
        if (paragraphs == null || paragraphs.isEmpty()) {
            return "";
        }
        return String.join("\n", paragraphs);
    }

    /**
    * 转换为 映射 用于 JSON 序列化
    *
    * @return Map 表示
    */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("author", author);
        map.put("dynasty", dynasty);
        map.put("content", getContent());
        map.put("paragraphs", paragraphs);
        return map;
    }
}
