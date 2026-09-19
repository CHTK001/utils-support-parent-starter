package com.chua.common.support.datasearch.hanzi.spi;

import com.chua.common.support.datasearch.hanzi.model.HanziInfo;

import java.util.List;

/**
 * 汉字字典数据提供者 SPI 接口。
 *
 * <p>封装汉字的精确查询、模糊搜索与随机获取能力。
 * 各实现通过 SPI 机制注册，例如基于 chinese-xinhua 字库的在线数据源。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface HanziProvider {

    /**
     * 获取数据源名称
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 精确查询汉字。
     *
     * <p>字完全匹配，找不到时返回 null。
     *
     * @param character 单个汉字（如：中）
     * @return 汉字信息；不存在返回 空
     */
    HanziInfo get(String character);

    /**
     * 按关键词搜索汉字（匹配拼音、部首或释义）。
     *
     * @param keyword 关键词
     * @param limit   返回条数上限（小于等于 0 时返回全部）
     * @return 匹配的汉字列表
     */
    List<HanziInfo> search(String keyword, int limit);

    /**
     * 获取随机汉字。
     *
     * @return 随机汉字
     */
    HanziInfo random();
}
