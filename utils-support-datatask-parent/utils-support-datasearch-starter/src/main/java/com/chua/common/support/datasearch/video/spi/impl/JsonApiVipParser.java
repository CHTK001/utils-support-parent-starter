package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.video.model.VipParseRequest;
import com.chua.common.support.datasearch.video.model.VipParseResult;
import com.chua.common.support.datasearch.video.model.VideoPlayAddress;
import com.chua.common.support.datasearch.video.model.VideoPlayAddressChannel;
import com.chua.common.support.datasearch.video.spi.VipParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON API VIP 解析器。
 *
 * <p>通过已知的 JSON API 接口解析视频播放地址。
 * 支持 OpenArt 等采用通用 JSON API 格式的视频站点。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("json")
public class JsonApiVipParser implements VipParser {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(JsonApiVipParser.class);

    @Override
    public String name() {
        return "json";
    }

    @Override
    public boolean supports(String source) {
        return "json".equals(source) || "json-api".equals(source) || "generic".equals(source);
    }

    @Override
    public VipParseResult parse(VipParseRequest request) {
        String url = request.getUrl();
        if (url == null || url.isBlank()) {
            return VipParseResult.error("URL 为空");
        }
        int timeout = request.getTimeoutMs() > 0 ? request.getTimeoutMs() : 10000;
        try {
            ClientResponse response = HttpClientFactory.of(url)
                    .readTimeout(timeout)
                    .get();
            if (!response.isSuccess()) {
                return VipParseResult.error("HTTP " + response.getStatusCode());
            }
            JsonNode node = Json.parse(response.getBodyString());
            return parseJsonResponse(node, url);
        } catch (Exception e) {
            log.warn("[json] VIP 解析失败: url={}, err={}", url, e.getMessage());
            return VipParseResult.error("解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析 JSON 响应中的播放地址。
     *
     * @param root JSON 根节点
     * @param url  原始 URL
     * @return 解析结果
     */
    @SuppressWarnings("unchecked")
    private VipParseResult parseJsonResponse(JsonNode root, String url) {
        // 尝试从标准 JSON API 响应提取播放地址
        JsonNode playUrl = root.get("play_url");
        if (playUrl.isMissingValue()) {
            playUrl = root.get("playurl");
        }
        if (playUrl.isMissingValue()) {
            playUrl = root.get("data");
            if (!playUrl.isMissingValue()) {
                playUrl = playUrl.get("play_url");
            }
            if (playUrl != null && !playUrl.isMissingValue()) {
                playUrl = playUrl.get("url");
            }
        }
        if (playUrl == null || playUrl.isMissingValue()) {
            return VipParseResult.error("无法从 JSON 响应提取播放地址");
        }
        String directUrl = playUrl.toStringValue();
        if (directUrl == null || directUrl.isBlank()) {
            return VipParseResult.error("JSON 播放地址为空");
        }
        VideoPlayAddress addr = new VideoPlayAddress();
        addr.setVideoPlayAddressName("JSON API 直链");
        addr.setVideoPlayAddressCode("json");
        VideoPlayAddressChannel ch = new VideoPlayAddressChannel();
        ch.setVideoPlayAddressChannelName("直链");
        ch.setVideoPlayAddressUrl(directUrl);
        addr.setVideoPlayAddressChannels(List.of(ch));
        VipParseResult result = VipParseResult.success(List.of(addr));
        JsonNode title = root.get("title");
        if (!title.isMissingValue()) {
            result.setTitle(title.toStringValue());
        }
        return result;
    }
}