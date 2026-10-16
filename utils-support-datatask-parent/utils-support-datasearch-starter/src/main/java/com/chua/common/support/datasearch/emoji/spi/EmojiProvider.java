package com.chua.common.support.datasearch.emoji.spi;

import com.chua.common.support.datasearch.emoji.model.EmojiInfo;

import java.util.List;

/**
 * 表情包数据提供者 SPI 接口。
 *
 * <p>封装按关键词检索表情包的能力。各实现通过 SPI 机制注册，
 * 例如基于「发表情」垂直站点的数据源、基于搜索引擎图片检索的通用数据源。</p>
 *
 * <p>调用方可通过 {@code ServiceProvider.of(EmojiProvider.class).list()} 获取全部实现，
 * 并按需聚合；不同实现返回的 {@link EmojiInfo#getSource()} 用于区分来源。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EmojiProvider {

    /**
     * 获取数据源名称。
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 按关键词分页检索表情包。
     *
     * <p>关键词为空时返回空列表；页码小于 1 时按第 1 页处理。</p>
     *
     * @param keyword 关键词（如：狗子）
     * @param page    页码，从 1 开始
     * @return 匹配的表情包列表，找不到返回空列表
     */
    List<EmojiInfo> search(String keyword, int page);

    /**
     * 按关键词检索第一页表情包。
     *
     * @param keyword 关键词
     * @return 匹配的表情包列表
     */
    default List<EmojiInfo> search(String keyword) {
        return search(keyword, 1);
    }
}
