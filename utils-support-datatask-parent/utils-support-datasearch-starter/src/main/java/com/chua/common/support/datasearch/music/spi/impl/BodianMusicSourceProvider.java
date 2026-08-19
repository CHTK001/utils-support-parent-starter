package com.chua.common.support.datasearch.music.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.music.model.MusicOverview;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategoryCatalog;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategoryResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistDetail;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSearchResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSummary;
import com.chua.common.support.datasearch.music.model.MusicSearchResult;
import com.chua.common.support.datasearch.music.model.MusicSourceOption;
import com.chua.common.support.datasearch.music.model.MusicTrackDetail;
import com.chua.common.support.datasearch.music.model.MusicTrackSummary;
import com.chua.common.support.datasearch.music.spi.support.AbstractHttpMusicSourceProvider;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.chua.common.support.utils.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 波点音乐源提供者
 * 波点歌单镜像源，歌曲详情复用酷我公开接口
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Spi("bd")
public class BodianMusicSourceProvider extends AbstractHttpMusicSourceProvider {

    /** Playlist_ids_env */
    private static final String PLAYLIST_IDS_ENV = "MUSIC_BD_PLAYLIST_IDS";
    /** Playlist_ids_property */
    private static final String PLAYLIST_IDS_PROPERTY = "music.bd.playlist-ids";
    @Override
    /** 获取Source */
    public MusicSourceOption getSource() {
        return MusicSourceOption.builder()
                .code("bd")
                .name("波点音乐")
                .description("波点歌单镜像源，歌曲详情复用酷我公开接口")
                .enabled(Boolean.TRUE)
                .build();
    }

    @Override
    /** 获取Overview */
    public MusicOverview getOverview() {
        List<MusicPlaylistSummary> featured = new ArrayList<>();
        for (String playlistId : configuredPlaylistIds()) {
            try {
                featured.add(toSummary(getPlaylistDetail(playlistId)));
            } catch (Exception ignored) {
            }
        }
        return overviewOf(List.of("歌单", "精选", "收藏"), featured);
    }

    @Override
    /** 搜索 */
    public MusicSearchResult search(String keyword, int page, int pageSize) {
        JsonNode root = getJson("https://bd-api.kuwo.cn/api/search/searchKey?key=" + encode(keyword)
                + "&pn=" + page + "&rn=" + pageSize, this::applyHeaders);
        JsonNode data = path(root, "data");
        long total = longValue(data, "total");
        List<MusicTrackSummary> tracks = new ArrayList<>();
        for (JsonNode item : elements(path(data, "list"))) {
            tracks.add(MusicTrackSummary.builder()
                    .trackId(text(item, "id"))
                    .source(getSource().getCode())
                    .title(text(item, "name"))
                    .artist(joinArtists(item.path("artists")))
                    .album(text(item, "album"))
                    .coverUrl(firstNonBlank(text(item, "albumPic"), text(item, "pic")))
                    .durationSeconds(integer(item, "duration"))
                    .build());
        }
        return searchResult(keyword, page, pageSize, total, tracks);
    }

    @Override
    /** 搜索Playlists */
    public MusicPlaylistSearchResult searchPlaylists(String keyword, int page, int pageSize) {
        List<MusicPlaylistSummary> playlists = new ArrayList<>();
        if (StringUtils.hasText(keyword) && keyword.chars().allMatch(Character::isDigit)) {
            try {
                playlists.add(toSummary(getPlaylistDetail(keyword.trim())));
            } catch (Exception ignored) {
            }
        }
        return MusicPlaylistSearchResult.builder()
                .source(getSource().getCode())
                .keyword(keyword)
                .page(page)
                .pageSize(pageSize)
                .total((long) playlists.size())
                .playlists(playlists)
                .build();
    }

    @Override
    /** 获取PlaylistCategoryCatalog */
    public MusicPlaylistCategoryCatalog getPlaylistCategoryCatalog() {
        return MusicPlaylistCategoryCatalog.builder()
                .source(getSource().getCode())
                .hotTags(List.of())
                .groups(List.of())
                .build();
    }

    @Override
    /** 获取CategoryPlaylists */
    public MusicPlaylistCategoryResult getCategoryPlaylists(String tagId, int page, int pageSize) {
        List<MusicPlaylistSummary> playlists = new ArrayList<>();
        for (String playlistId : configuredPlaylistIds()) {
            try {
                playlists.add(toSummary(getPlaylistDetail(playlistId)));
            } catch (Exception ignored) {
            }
        }
        int fromIndex = Math.max(0, (page - 1) * pageSize);
        int toIndex = Math.min(playlists.size(), fromIndex + pageSize);
        return MusicPlaylistCategoryResult.builder()
                .source(getSource().getCode())
                .tagId(tagId)
                .categoryName("波点歌单")
                .page(page)
                .pageSize(pageSize)
                .total((long) playlists.size())
                .playlists(fromIndex >= playlists.size() ? List.of() : playlists.subList(fromIndex, toIndex))
                .build();
    }

    @Override
    /** 获取PlaylistDetail */
    public MusicPlaylistDetail getPlaylistDetail(String playlistId) {
        String requestId = String.valueOf(System.nanoTime());
        JsonNode infoRoot = getJson("https://bd-api.kuwo.cn/api/service/playlist/info/" + encode(playlistId)
                + "?reqId=" + requestId + "&source=5", this::applyHeaders);
        JsonNode info = path(infoRoot, "data");
        if (info == null || info.isMissingNode() || info.isNull() || info.isEmpty()) {
            throw new IllegalArgumentException("未找到波点歌单 " + playlistId);
        }
        int total = integer(info, "musicNum");
        if (total <= 0) {
            total = 100;
        }
        int pageSize = Math.min(Math.max(total, 100), 500);
        JsonNode listRoot = getJson("https://bd-api.kuwo.cn/api/service/playlist/" + encode(playlistId)
                + "/musicList?reqId=" + requestId + "&source=5&pn=1&rn=" + pageSize, this::applyHeaders);
        List<MusicTrackSummary> tracks = new ArrayList<>();
        for (JsonNode item : elements(path(listRoot, "data", "list"))) {
            tracks.add(MusicTrackSummary.builder()
                    .trackId(text(item, "id"))
                    .source(getSource().getCode())
                    .title(text(item, "name"))
                    .artist(joinArtists(item.path("artists")))
                    .album(text(item, "album"))
                    .coverUrl(firstNonBlank(text(item, "albumPic"), text(item, "pic")))
                    .durationSeconds(integer(item, "duration"))
                    .build());
        }
        return MusicPlaylistDetail.builder()
                .playlistId(playlistId)
                .source(getSource().getCode())
                .title(text(info, "name"))
                .description(text(info, "description"))
                .coverUrl(firstNonBlank(text(info, "pic"), text(info, "cover"), text(info, "img")))
                .author(firstNonBlank(text(info, "userName"), text(info, "creator"), "波点音乐"))
                .trackCount(tracks.size())
                .tracks(tracks)
                .build();
    }

    @Override
    /** 获取TrackDetail */
    public MusicTrackDetail getTrackDetail(String trackId) {
        JsonNode root = getJson("https://bd-api.kuwo.cn/api/service/song/detail/" + encode(trackId)
                + "?reqId=" + System.nanoTime() + "&source=5", this::applyHeaders);
        JsonNode data = path(root, "data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new IllegalArgumentException("未找到歌曲详情 " + trackId);
        }
        return MusicTrackDetail.builder()
                .trackId(trackId)
                .source(getSource().getCode())
                .title(text(data, "name"))
                .artist(joinArtists(data.path("artists")))
                .album(text(data, "album"))
                .coverUrl(firstNonBlank(text(data, "albumPic"), text(data, "pic")))
                .streamUrl(text(data, "url"))
                .durationSeconds(integer(data, "duration"))
                .lyrics(text(data, "lyrics"))
                .playCount(longValue(data, "playCount"))
                .commentCount(longValue(data, "commentCount"))
                .build();
    }

    /** 应用Headers */
    private void applyHeaders(HttpClientBuilder builder) {
        builder.header("User-Agent", MOBILE_UA)
                .header("plat", "h5")
                .header("Referer", "https://bd.kuwo.cn/");
    }

    /** ToSummary */
    private MusicPlaylistSummary toSummary(MusicPlaylistDetail detail) {
        return MusicPlaylistSummary.builder()
                .playlistId(detail.getPlaylistId())
                .source(getSource().getCode())
                .title(detail.getTitle())
                .description(detail.getDescription())
                .coverUrl(detail.getCoverUrl())
                .author(detail.getAuthor())
                .trackCount(detail.getTrackCount())
                .accentColor("linear-gradient(135deg, #1d2632 0%, #4d6d8d 100%)")
                .build();
    }

    /** ConfiguredPlaylistIds */
    private List<String> configuredPlaylistIds() {
        String value = System.getProperty(PLAYLIST_IDS_PROPERTY);
        if (!StringUtils.hasText(value)) {
            value = System.getenv(PLAYLIST_IDS_ENV);
        }
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("[,;\\s]+"))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    /** 合并Artists */
    private String joinArtists(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : elements(node)) {
            String name = text(item, "name");
            if (StringUtils.hasText(name)) {
                names.add(name);
            }
        }
        return String.join(" / ", names);
    }
}


