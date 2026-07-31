package com.chua.common.support.datasearch.music.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.music.model.MusicOverview;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategory;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategoryCatalog;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategoryGroup;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategoryResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistDetail;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSearchResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSummary;
import com.chua.common.support.datasearch.music.model.MusicSearchResult;
import com.chua.common.support.datasearch.music.model.MusicSourceOption;
import com.chua.common.support.datasearch.music.model.MusicTrackDetail;
import com.chua.common.support.datasearch.music.model.MusicTrackSummary;
import com.chua.common.support.datasearch.music.spi.support.AbstractHttpMusicSourceProvider;
import com.chua.common.support.datasearch.music.spi.support.TencentSignSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.chua.common.support.utils.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ音乐（腾讯音乐）源提供者
 * 实现QQ音乐平台的搜索、歌单、歌曲详情等功能
 * 
 * @author CH
 * @since 1.0.0
*/
@Spi("tx")
public class TencentMusicSourceProvider extends AbstractHttpMusicSourceProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HOT_TAG_PATTERN = Pattern.compile("data-id=\"(\\w+)\">(.+?)</a>");
    private static final String MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";

    @Override
    public MusicSourceOption getSource() {
        return MusicSourceOption.builder()
                .code("tx")
                .name("QQ音乐")
                .description("CeruMusic JS SDK - Tencent Music")
                .enabled(Boolean.TRUE)
                .build();
    }

    @Override
    public MusicOverview getOverview() {
        List<String> hotKeywords = fetchHotKeywords();
        List<MusicPlaylistSummary> featured = getCategoryPlaylists("", 1, 12).getPlaylists();
        return overviewOf(hotKeywords, featured);
    }

    @Override
    public MusicSearchResult search(String keyword, int page, int pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> comm = new LinkedHashMap<>();
        comm.put("ct", "11");
        comm.put("cv", "14090508");
        comm.put("v", "14090508");
        comm.put("tmeAppID", "qqmusic");
        comm.put("phonetype", "EBG-AN10");
        comm.put("deviceScore", "553.47");
        comm.put("devicelevel", "50");
        comm.put("newdevicelevel", "20");
        comm.put("rom", "HuaWei/EMOTION/EmotionUI_14.2.0");
        comm.put("os_ver", "12");
        comm.put("OpenUDID", "0");
        comm.put("OpenUDID2", "0");
        comm.put("QIMEI36", "0");
        comm.put("udid", "0");
        comm.put("chid", "0");
        comm.put("aid", "0");
        comm.put("oaid", "0");
        comm.put("taid", "0");
        comm.put("tid", "0");
        comm.put("wid", "0");
        comm.put("uid", "0");
        comm.put("sid", "0");
        comm.put("modeSwitch", "6");
        comm.put("teenMode", "0");
        comm.put("ui_mode", "2");
        comm.put("nettype", "1020");
        comm.put("v4ip", "");
        request.put("comm", comm);
        Map<String, Object> searchParam = new LinkedHashMap<>();
        searchParam.put("search_type", 0);
        searchParam.put("searchid", String.valueOf(System.currentTimeMillis()));
        searchParam.put("query", keyword);
        searchParam.put("page_num", page);
        searchParam.put("num_per_page", pageSize);
        searchParam.put("highlight", 0);
        searchParam.put("nqc_flag", 0);
        searchParam.put("multi_zhida", 0);
        searchParam.put("cat", 2);
        searchParam.put("grp", 1);
        searchParam.put("sin", 0);
        searchParam.put("sem", 0);
        request.put("req", Map.of(
                "module", "music.search.SearchCgiService",
                "method", "DoSearchForQQMusicMobile",
                "param", searchParam
        ));
        JsonNode root = postSigned(request);
        JsonNode data = path(root, "req", "data");
        List<MusicTrackSummary> tracks = new ArrayList<>();
        for (JsonNode item : elements(path(data, "body", "item_song"))) {
            MusicTrackSummary summary = toTrackSummary(item);
            if (summary != null) {
                tracks.add(summary);
            }
        }
        return searchResult(keyword, page, pageSize, longValue(data, "meta", "estimate_sum"), tracks);
    }

    @Override
    public MusicPlaylistSearchResult searchPlaylists(String keyword, int page, int pageSize) {
        String url = "http://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist?page_no=" + Math.max(0, page - 1)
                + "&num_per_page=" + pageSize
                + "&format=json&query=" + encode(keyword)
                + "&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8";
        JsonNode root = getJson(url, builder -> builder
                .header("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")
                .header("Referer", "http://y.qq.com/portal/search.html"));
        List<MusicPlaylistSummary> playlists = new ArrayList<>();
        for (JsonNode item : elements(path(root, "data", "list"))) {
            playlists.add(MusicPlaylistSummary.builder()
                    .playlistId(text(item, "dissid"))
                    .source(getSource().getCode())
                    .title(text(item, "dissname"))
                    .description(text(item, "introduction").replace("<br>", "\n"))
                    .coverUrl(text(item, "imgurl"))
                    .author(text(item, "creator", "name"))
                    .trackCount(integer(item, "song_count"))
                    .accentColor("linear-gradient(135deg, #382419 0%, #a86d44 100%)")
                    .build());
        }
        return MusicPlaylistSearchResult.builder()
                .source(getSource().getCode())
                .keyword(keyword)
                .page(page)
                .pageSize(pageSize)
                .total(longValue(root, "data", "sum"))
                .playlists(playlists)
                .build();
    }

    @Override
    public MusicPlaylistCategoryCatalog getPlaylistCategoryCatalog() {
        JsonNode root = getJson("https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=%7B%22tags%22%3A%7B%22method%22%3A%22get_all_categories%22%2C%22param%22%3A%7B%22qq%22%3A%22%22%7D%2C%22module%22%3A%22playlist.PlaylistAllCategoriesServer%22%7D%7D");
        List<MusicPlaylistCategoryGroup> groups = new ArrayList<>();
        List<MusicPlaylistCategory> hotTags = new ArrayList<>();
        int groupIndex = 0;
        for (JsonNode group : elements(path(root, "tags", "data", "v_group"))) {
            List<MusicPlaylistCategory> tags = new ArrayList<>();
            for (JsonNode item : elements(group.path("v_item"))) {
                MusicPlaylistCategory tag = MusicPlaylistCategory.builder()
                        .tagId(text(item, "id"))
                        .name(text(item, "name"))
                        .hot(groupIndex == 0 && hotTags.size() < 8)
                        .build();
                tags.add(tag);
                if (tag.getHot()) {
                    hotTags.add(tag);
                }
            }
            groups.add(MusicPlaylistCategoryGroup.builder()
                    .groupId(text(group, "group_id"))
                    .name(text(group, "group_name"))
                    .tags(tags)
                    .build());
            groupIndex += 1;
        }

        String hotHtml = getText("https://c.y.qq.com/node/pc/wk_v15/category_playlist.html");
        Matcher matcher = HOT_TAG_PATTERN.matcher(hotHtml);
        hotTags.clear();
        while (matcher.find() && hotTags.size() < 8) {
            hotTags.add(MusicPlaylistCategory.builder()
                    .tagId(matcher.group(1))
                    .name(matcher.group(2))
                    .hot(true)
                    .build());
        }

        return MusicPlaylistCategoryCatalog.builder()
                .source(getSource().getCode())
                .hotTags(hotTags)
                .groups(groups)
                .build();
    }

    @Override
    public MusicPlaylistCategoryResult getCategoryPlaylists(String tagId, int page, int pageSize) {
        JsonNode playlist;
        if (StringUtils.hasText(tagId)) {
            String url = "https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data="
                    + encode("{\"comm\":{\"cv\":1602,\"ct\":20},\"playlist\":{\"method\":\"get_category_content\",\"param\":{\"titleid\":"
                    + Integer.parseInt(tagId)
                    + ",\"caller\":\"0\",\"category_id\":"
                    + Integer.parseInt(tagId)
                    + ",\"size\":"
                    + pageSize
                    + ",\"page\":"
                    + Math.max(0, page - 1)
                    + ",\"use_page\":1},\"module\":\"playlist.PlayListCategoryServer\"}}");
            playlist = path(getJson(url), "playlist", "data", "content");
        } else {
            String url = "https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data="
                    + encode("{\"comm\":{\"cv\":1602,\"ct\":20},\"playlist\":{\"method\":\"get_playlist_by_tag\",\"param\":{\"id\":10000000,\"sin\":"
                    + (pageSize * Math.max(0, page - 1))
                    + ",\"size\":"
                    + pageSize
                    + ",\"order\":5,\"cur_page\":"
                    + page
                    + "},\"module\":\"playlist.PlayListPlazaServer\"}}");
            playlist = path(getJson(url), "playlist", "data");
        }
        List<MusicPlaylistSummary> playlists = new ArrayList<>();
        JsonNode rawList = StringUtils.hasText(tagId) ? playlist.path("v_item") : playlist.path("v_playlist");
        for (JsonNode item : elements(rawList)) {
            JsonNode basic = StringUtils.hasText(tagId) ? item.path("basic") : item;
            playlists.add(MusicPlaylistSummary.builder()
                    .playlistId(text(basic, "tid"))
                    .source(getSource().getCode())
                    .title(firstNonBlank(text(basic, "title"), text(basic, "dissname")))
                    .description(text(basic, "desc").replace("<br>", "\n"))
                    .coverUrl(firstNonBlank(text(basic, "cover", "medium_url"), text(basic, "cover_url_medium"), text(basic, "imgurl")))
                    .author(firstNonBlank(text(basic, "creator", "nick"), text(basic, "creator_info", "nick")))
                    .trackCount(integer(basic, "song_count"))
                    .accentColor("linear-gradient(135deg, #382419 0%, #a86d44 100%)")
                    .build());
        }
        return MusicPlaylistCategoryResult.builder()
                .source(getSource().getCode())
                .tagId(tagId)
                .categoryName(StringUtils.hasText(tagId) ? "分类歌单" : "热门")
                .page(page)
                .pageSize(pageSize)
                .total(StringUtils.hasText(tagId) ? longValue(playlist, "total_cnt") : longValue(playlist, "total"))
                .playlists(playlists)
                .build();
    }

    @Override
    public MusicPlaylistDetail getPlaylistDetail(String playlistId) {
        String url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid="
                + encode(playlistId)
                + "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0";
        JsonNode root = getJson(url, builder -> builder
                .header("Origin", "https://y.qq.com")
                .header("Referer", "https://y.qq.com/n/yqq/playsquare/" + playlistId + ".html"));
        JsonNode cd = path(root, "cdlist").path(0);
        List<MusicTrackSummary> tracks = new ArrayList<>();
        for (JsonNode item : elements(cd.path("songlist"))) {
            MusicTrackSummary summary = toTrackSummary(item);
            if (summary != null) {
                tracks.add(summary);
            }
        }
        return MusicPlaylistDetail.builder()
                .playlistId(playlistId)
                .source(getSource().getCode())
                .title(text(cd, "dissname"))
                .description(text(cd, "desc").replace("<br>", "\n"))
                .coverUrl(text(cd, "logo"))
                .author(text(cd, "nickname"))
                .trackCount(tracks.size())
                .tracks(tracks)
                .build();
    }

    @Override
    public MusicTrackDetail getTrackDetail(String trackId) {
        JsonNode item = path(postJson(MUSICU_URL, Map.of(
                "comm", Map.of("ct", "19", "cv", "1859", "uin", "0"),
                "req", Map.of(
                        "module", "music.pf_song_detail_svr",
                        "method", "get_song_detail_yqq",
                        "param", Map.of("song_type", 0, "song_mid", trackId)
                )
        ), builder -> builder.header("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")), "req", "data", "track_info");

        String songMid = text(item, "mid");
        String songId = text(item, "id");
        String mediaMid = text(item, "file", "media_mid");
        String albumMid = text(item, "album", "mid");
        String cover = StringUtils.hasText(albumMid)
                ? "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albumMid + ".jpg"
                : "";
        String quality = selectQuality(item);
        return MusicTrackDetail.builder()
                .trackId(trackId)
                .source(getSource().getCode())
                .title(text(item, "title") + text(item, "title_extra"))
                .artist(joinNames(item.path("singer"), "name"))
                .album(text(item, "album", "name"))
                .coverUrl(cover)
                .durationSeconds(integer(item, "interval"))
                .lyrics(fetchLyrics(songId))
                .streamUrl(fetchStreamUrl(songMid, mediaMid, quality))
                .build();
    }

    private JsonNode postSigned(Object body) {
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化QQ音乐请求体失败", e);
        }
        String sign = TencentSignSupport.sign(json);
        return postJson("https://u.y.qq.com/cgi-bin/musics.fcg?sign=" + sign, body,
                builder -> builder.header("User-Agent", "QQMusic 14090508(android 12)"));
    }

    private List<String> fetchHotKeywords() {
        JsonNode root = postJson(MUSICU_URL, Map.of(
                "comm", Map.of("ct", "19", "cv", "1803", "guid", "0", "tmeAppID", "qqmusic", "uin", "0", "wid", "0"),
                "hotkey", Map.of(
                        "method", "GetHotkeyForQQMusicPC",
                        "module", "tencent_musicsoso_hotkey.HotkeyService",
                        "param", Map.of("search_id", "", "uin", 0)
                )
        ), builder -> builder.header("Referer", "https://y.qq.com/portal/player.html"));
        List<String> result = new ArrayList<>();
        for (JsonNode item : elements(path(root, "hotkey", "data", "vec_hotkey"))) {
            result.add(item.path("query").asText(""));
        }
        return result;
    }

    private String fetchLyrics(String songId) {
        Map<String, Object> lyricParam = new LinkedHashMap<>();
        lyricParam.put("format", "json");
        lyricParam.put("crypt", 0);
        lyricParam.put("ct", 19);
        lyricParam.put("cv", 1873);
        lyricParam.put("interval", 0);
        lyricParam.put("lrc_t", 0);
        lyricParam.put("qrc", 0);
        lyricParam.put("roma", 0);
        lyricParam.put("songID", Integer.parseInt(songId));
        lyricParam.put("trans", 1);
        lyricParam.put("type", -1);
        JsonNode root = postJson(MUSICU_URL, Map.of(
                "comm", Map.of("ct", "19", "cv", "1859", "uin", "0"),
                "req", Map.of(
                        "module", "music.musichallSong.PlayLyricInfo",
                        "method", "GetPlayLyricInfo",
                        "param", lyricParam
                )
        ), builder -> builder
                .header("Referer", "https://y.qq.com")
                .header("User-Agent", DESKTOP_UA));
        return firstNonBlank(text(root, "req", "data", "lyric"), text(root, "req", "data", "trans"));
    }

    private String fetchStreamUrl(String songMid, String mediaMid, String quality) {
        String filename;
        if ("flac".equals(quality)) {
            filename = "F000" + mediaMid + ".flac";
        } else if ("320k".equals(quality)) {
            filename = "M800" + mediaMid + ".mp3";
        } else {
            filename = "M500" + mediaMid + ".mp3";
        }
        JsonNode root = postJson(MUSICU_URL, Map.of(
                "comm", Map.of("cv", 4747474, "ct", 24, "format", "json", "uin", "0"),
                "req_1", Map.of(
                        "module", "vkey.GetVkeyServer",
                        "method", "CgiGetVkey",
                        "param", Map.of(
                                "guid", "7332953645",
                                "loginflag", 1,
                                "filename", List.of(filename),
                                "songmid", List.of(songMid),
                                "songtype", List.of(0),
                                "uin", "0",
                                "platform", "20"
                        )
                )
        ), builder -> builder.header("Referer", "https://y.qq.com"));
        String sip = path(root, "req_1", "data", "sip").path(0).asText("");
        String purl = path(root, "req_1", "data", "midurlinfo").path(0).path("purl").asText("");
        if (!StringUtils.hasText(sip) || !StringUtils.hasText(purl)) {
            throw new IllegalStateException("QQ音乐播放链接解析失败");
        }
        return sip + purl;
    }

    private String selectQuality(JsonNode item) {
        JsonNode file = item.path("file");
        if (file.path("size_flac").asLong(0L) > 0) {
            return "flac";
        }
        if (file.path("size_320mp3").asLong(0L) > 0) {
            return "320k";
        }
        return "128k";
    }

    private MusicTrackSummary toTrackSummary(JsonNode item) {
        if (!StringUtils.hasText(text(item, "file", "media_mid"))) {
            return null;
        }
        String albumMid = text(item, "album", "mid");
        return MusicTrackSummary.builder()
                .trackId(text(item, "mid"))
                .source(getSource().getCode())
                .title(firstNonBlank(text(item, "title"), text(item, "name")) + text(item, "title_extra"))
                .artist(joinNames(item.path("singer"), "name"))
                .album(firstNonBlank(text(item, "album", "name"), text(item, "albumName")))
                .coverUrl(StringUtils.hasText(albumMid)
                        ? "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albumMid + ".jpg"
                        : "")
                .durationSeconds(integer(item, "interval"))
                .build();
    }

    private String joinNames(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : elements(array)) {
            String value = item.path(field).asText("");
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return String.join(",", values);
    }
}


