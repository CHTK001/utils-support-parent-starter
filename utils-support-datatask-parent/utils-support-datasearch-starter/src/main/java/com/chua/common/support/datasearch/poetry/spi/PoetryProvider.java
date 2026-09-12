package com.chua.common.support.datasearch.poetry.spi;

import com.chua.common.support.datasearch.poetry.model.PoetryInfo;

import java.util.List;

/**
* 古诗词数据提供者 SPI 接口。
*
* <p>封装中国古典诗词的随机、作者检索与关键词搜索能力。
* 各实现通过 SPI 机制注册，例如基于 chinese-Poetry 全唐诗语料的在线数据源。
*
* @author CH
* @since 4.0.0.42
 */
public interface PoetryProvider {

    /**
    * 获取数据源名称
    *
    * @return 数据源名称
     */
    String name();

    /**
    * 获取随机诗词。
    *
    * @return 随机诗词
     */
    PoetryInfo random();

    /**
    * 按作者检索诗词。
    *
    * @param author 作者名（如：李白）
    * @param limit  返回条数上限（小于等于 0 时返回全部）
    * @return 该作者的诗词列表
     */
    List<PoetryInfo> byAuthor(String author, int limit);

    /**
    * 按关键词搜索诗词（匹配标题或正文）。
    *
    * @param keyword 关键词
    * @param limit   返回条数上限（小于等于 0 时返回全部）
    * @return 匹配的诗词列表
     */
    List<PoetryInfo> search(String keyword, int limit);
}
