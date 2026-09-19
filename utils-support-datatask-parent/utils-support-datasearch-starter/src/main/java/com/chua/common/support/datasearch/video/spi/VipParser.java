package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.video.model.VipParseRequest;
import com.chua.common.support.datasearch.video.model.VipParseResult;

/**
 * VIP 解析器 SPI。
 *
 * <p>每种 VIP 来源（如 bilibili/douyin/youku）一个实现，
 * 解析视频播放页 URL 并返回可播放的地址列表。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public interface VipParser {

    /**
     * 获取提供者名称（如 bilibili）。
     *
     * @return 提供者名称
     */
    String name();

    /**
     * 尝试解析并返回结果。
     *
     * @param request VIP 解析请求
     * @return 解析结果
     */
    VipParseResult parse(VipParseRequest request);

    /**
     * 是否支持解析指定来源。
     *
     * @param source 来源编码
     * @return 支持返回 true
     */
    default boolean supports(String source) {
        return name().equalsIgnoreCase(source);
    }
}
