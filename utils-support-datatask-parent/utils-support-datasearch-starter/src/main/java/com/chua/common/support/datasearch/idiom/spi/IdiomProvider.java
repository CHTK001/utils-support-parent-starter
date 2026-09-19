package com.chua.common.support.datasearch.idiom.spi;

import com.chua.common.support.datasearch.idiom.model.IdiomInfo;

import java.util.List;

/**
 * 成语数据提供者 SPI 接口。
 *
 * <p>封装中文成语的精确查询、模糊搜索与随机获取能力。
 * 各实现通过 SPI 机制注册，例如基于 chinese-xinhua 语料库的在线数据源。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface IdiomProvider {

    /**
     * 获取数据源名称
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 精确查询成语。
     *
     * <p>词形完全匹配，找不到时返回 null。
     *
     * @param word 成语词形（如：守株待兔）
     * @return 成语信息；不存在返回 空
     */
    IdiomInfo get(String word);

    /**
     * 按关键词模糊搜索成语。
     *
     * <p>匹配词形或释义中包含关键词的成语，返回前 {@code limit} 条。
     *
     * @param keyword 关键词
     * @param limit   返回条数上限（小于等于 0 时返回全部）
     * @return 匹配的成语列表
     */
    List<IdiomInfo> search(String keyword, int limit);

    /**
     * 获取随机成语。
     *
     * @return 随机成语
     */
    IdiomInfo random();

    /**
     * 成语接龙：找出以指定字开头的成语。
     *
     * <p>若 {@code text} 为单个汉字，则直接以其为首字匹配；
     * 若为成语，则取其末字作为首字匹配（如 {@code 守株待兔 -> 兔}），
     * 匹配不到时返回空列表。
     *
     * @param text  接龙起点（成语或单个汉字）
     * @param limit 返回条数上限（小于等于 0 时返回全部）
     * @return 以接龙首字开头的成语列表
     */
    List<IdiomInfo> chain(String text, int limit);

    /**
     * 按首字查找成语。
     *
     * @param firstChar 首字（单个汉字）
     * @param limit     返回条数上限（小于等于 0 时返回全部）
     * @return 以该字开头的成语列表
     */
    List<IdiomInfo> findByFirstChar(String firstChar, int limit);
}
