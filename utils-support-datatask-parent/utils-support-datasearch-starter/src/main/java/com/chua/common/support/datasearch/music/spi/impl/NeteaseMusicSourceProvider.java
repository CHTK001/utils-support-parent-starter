package com.chua.common.support.datasearch.music.spi.impl;

import com.chua.common.support.datasearch.music.model.MusicOverview;
import com.chua.common.support.datasearch.music.model.MusicPlaylistDetail;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSearchResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSummary;
import com.chua.common.support.datasearch.music.model.MusicSearchResult;
import com.chua.common.support.datasearch.music.model.MusicSourceOption;
import com.chua.common.support.datasearch.music.model.MusicTrackDetail;
import com.chua.common.support.datasearch.music.model.MusicTrackSummary;
import com.chua.common.support.datasearch.music.spi.support.AbstractHttpMusicSourceProvider;
import com.chua.common.support.datasearch.music.spi.support.NeteaseCryptoSupport;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云音乐数据源实现。
 *
 * <p>通过 weapi 加密请求 {@code https://music.163.com/weapi/cloudsearch/get/web}
 * 搜索歌曲（类型=1）与歌单（类型=1000），加密由 {@link NeteaseCryptoSupport} 完成。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("netease")
public class NeteaseMusicSourceProvider extends AbstractHttpMusicSourceProvider {

    /**
     * 搜索接口地址
    */
    private static final String SEARCH_URL = "https://music.163.com/weapi/cloudsearch/get/web?csrf_token=";

    @Override
    /**
     * 获取源
    */
    public MusicSourceOption getSource() {
        return MusicSourceOption.builder()
                .code("netease")
                .name("网易云音乐")
                .description("Netease Cloud Music")
                .enabled(Boolean.TRUE)
                .build();
    }

    @Override
    /**
     * 获取Overview
    */
    public MusicOverview getOverview() {
        return overviewOf(List.of(), List.of());
    }

    @Override
    /**
     * 搜索歌曲
    */
    public MusicSearchResult search(String keyword, int page, int pageSize) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("s", keyword);
        payload.put("type", 1);
        payload.put("limit", pageSize);
        payload.put("offset", Math.max(0, (page - 1) * pageSize));
        payload.put("total", true);
        JsonNode result = postEncrypted(payload).path("result");
        List<MusicTrackSummary> tracks = new ArrayList<>();
        for (JsonNode item : elements(result.path("songs"))) {
            MusicTrackSummary summary = toTrackSummary(item);
            if (summary != null) {
                tracks.add(summary);
            }
        }
        return searchResult(keyword, page, pageSize, longValue(result, "songCount"), tracks);
    }

    @Override
    /**
     * 搜索歌单
    */
    public MusicPlaylistSearchResult searchPlaylists(String keyword, int page, int pageSize) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("s", keyword);
        payload.put("type", 1000);
        payload.put("limit", pageSize);
        payload.put("offset", Math.max(0, (page - 1) * pageSize));
        payload.put("total", true);
        JsonNode result = postEncrypted(payload).path("result");
        List<MusicPlaylistSummary> playlists = new ArrayList<>();
        for (JsonNode item : elements(result.path("playlists"))) {
            MusicPlaylistSummary summary = toPlaylistSummary(item);
            if (summary != null) {
                playlists.add(summary);
            }
        }
        return MusicPlaylistSearchResult.builder()
                .source(getSource().getCode())
                .keyword(keyword)
                .page(page)
                .pageSize(pageSize)
                .total(longValue(result, "playlistCount"))
                .playlists(playlists)
                .build();
    }

    @Override
    /**
     * 获取歌单详情
    */
    public MusicPlaylistDetail getPlaylistDetail(String playlistId) {
        return MusicPlaylistDetail.builder()
                .playlistId(playlistId)
                .source(getSource().getCode())
                .build();
    }

    @Override
    /**
     * 获取歌曲播放详情
    */
    public MusicTrackDetail getTrackDetail(String trackId) {
        return MusicTrackDetail.builder()
                .trackId(trackId)
                .source(getSource().getCode())
                .build();
    }

    /**
     * 发送 weapi 加密搜索请求。
     *
     * @param payload 明文参数
     * @return 响应根节点
     */
    private JsonNode postEncrypted(Map<String, Object> payload) {
        Map<String, String> enc = NeteaseCryptoSupport.weapi(payload);
        return postForm(SEARCH_URL, enc, builder -> builder
                .header("Referer", "https://music.163.com/")
                .header("Cookie", "os=pc; NMTID=" + java.util.UUID.randomUUID()));
    }

    /**
     * 歌曲节点转摘要。
     *
     * @param item 歌曲节点
     * @return 摘要；缺 标识 时返回 空
     */
    private MusicTrackSummary toTrackSummary(JsonNode item) {
        String trackId = text(item, "id");
        if (trackId.isEmpty()) {
            return null;
        }
        return MusicTrackSummary.builder()
                .trackId(trackId)
                .source(getSource().getCode())
                .title(text(item, "name"))
                .artist(joinNames(item.path("artists"), "name"))
                .album(firstNonBlank(text(item, "album", "name"), text(item, "al", "name")))
                .coverUrl(firstNonBlank(text(item, "album", "picUrl"), text(item, "al", "picUrl")))
                .durationSeconds(integer(item, "dt") / 1000)
                .build();
    }

    /**
     * 歌单节点转摘要。
     *
     * @param item 歌单节点
     * @return 摘要
     */
    private MusicPlaylistSummary toPlaylistSummary(JsonNode item) {
        return MusicPlaylistSummary.builder()
                .playlistId(text(item, "id"))
                .source(getSource().getCode())
                .title(text(item, "name"))
                .description(text(item, "description"))
                .coverUrl(text(item, "coverImgUrl"))
                .author(text(item, "creator", "nickname"))
                .trackCount(integer(item, "trackCount"))
                .playCount(longValue(item, "playCount"))
                .build();
    }

    /**
     * 拼接数组字段（如歌手名）。
     *
     * @param array 数组节点
     * @param field 字段名
     * @return 逗号分隔字符串
     */
    private String joinNames(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : elements(array)) {
            String value = item.path(field).asText("");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return String.join(",", values);
    }
}
