package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.video.model.VipParseRequest;
import com.chua.common.support.datasearch.video.model.VipParseResult;
import com.chua.common.support.datasearch.video.spi.VipParser;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 直链 VIP 解析器。
 *
 * <p>直接解析视频 URL，判断是否为直链（.mp4/.m3u8 等后缀）。
 * 若为直链则直接返回，不通过第三方接口。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("direct")
public class DirectVipParser implements VipParser {

    @Override
    public String name() {
        return "direct";
    }

    @Override
    public VipParseResult parse(VipParseRequest request) {
        String url = request.getUrl();
        if (url == null || url.isBlank()) {
            return VipParseResult.error("URL 为空");
        }
        if (isDirectUrl(url)) {
            com.chua.common.support.datasearch.video.model.VideoPlayAddress addr =
                    new com.chua.common.support.datasearch.video.model.VideoPlayAddress();
            addr.setVideoPlayAddressName("直链");
            addr.setVideoPlayAddressCode("direct");
            com.chua.common.support.datasearch.video.model.VideoPlayAddressChannel ch =
                    new com.chua.common.support.datasearch.video.model.VideoPlayAddressChannel();
            ch.setVideoPlayAddressChannelName("直链播放");
            ch.setVideoPlayAddressUrl(url);
            addr.setVideoPlayAddressChannels(List.of(ch));
            return VipParseResult.success(List.of(addr));
        }
        return VipParseResult.error("非直链格式: " + url);
    }

    /**
     * 判断是否为视频直链格式。
     *
     * @param url URL
     * @return 是否直链
     */
    private boolean isDirectUrl(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".m3u8") || lower.endsWith(".ts")
                || lower.endsWith(".flv") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.contains(".mp4?") || lower.contains(".m3u8?");
    }
}
