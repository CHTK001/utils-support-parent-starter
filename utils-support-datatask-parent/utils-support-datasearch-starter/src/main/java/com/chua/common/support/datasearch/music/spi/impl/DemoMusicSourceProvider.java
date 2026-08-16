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
import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 演示音乐源提供者
 * 内置演示音源数据，后续可替换为真实 SPI 实现
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Spi("demo")
public class DemoMusicSourceProvider implements MusicSourceProvider {

    private static final List<MusicPlaylistCategory> HOT_TAGS = List.of(
            tag("", "热门", true),
            tag("late-night", "深夜", true),
            tag("city-pop", "城市流行", true),
            tag("focus", "专注", true)
    );

    private static final List<MusicPlaylistCategoryGroup> CATEGORY_GROUPS = List.of(
            MusicPlaylistCategoryGroup.builder()
                    .groupId("scene")
                    .name("场景")
                    .tags(List.of(
                            tag("commute", "通勤", false),
                            tag("focus", "专注", false),
                            tag("night-drive", "夜驾", false)
                    ))
                    .build(),
            MusicPlaylistCategoryGroup.builder()
                    .groupId("style")
                    .name("风格")
                    .tags(List.of(
                            tag("city-pop", "城市流行", false),
                            tag("synthwave", "合成浪潮", false),
                            tag("ambient", "氛围", false)
                    ))
                    .build()
    );

    private static final Map<String, List<String>> CATEGORY_PLAYLISTS = createCategoryPlaylists();

    private static final List<MusicTrackDetail> TRACKS = List.of(
            track("starlit-drift", "Starlit Drift", "Lumen Harbor", "Signal Bloom", 228,
                    "https://images.unsplash.com/photo-1511379938547-c1f69419868d?auto=format&fit=crop&w=600&q=80",
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                    "[00:00.00]Starlit Drift\n[00:12.00]The city wakes in static blue\n[00:28.00]We ride the wires into view\n[00:45.00]Hold the pulse and stay in tune"),
            track("violet-circuit", "Violet Circuit", "Astra Vale", "After Neon", 204,
                    "https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?auto=format&fit=crop&w=600&q=80",
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                    "[00:00.00]Violet Circuit\n[00:15.00]Low lights shimmer in the rain\n[00:34.00]Every echo knows your name"),
            track("glass-harbor", "Glass Harbor", "North Arcade", "After Neon", 251,
                    "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=600&q=80",
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
                    "[00:00.00]Glass Harbor\n[00:11.00]Silver wake and tidal glow\n[00:31.00]Where the quiet engines go"),
            track("quiet-heat", "Quiet Heat", "Mira Sloane", "Velvet Transit", 196,
                    "https://images.unsplash.com/photo-1507838153414-b4b713384a76?auto=format&fit=crop&w=600&q=80",
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
                    "[00:00.00]Quiet Heat\n[00:17.00]Soft sparks under cinema skies\n[00:37.00]We hide the fire in our eyes"),
            track("night-trace", "Night Trace", "Mira Sloane", "Velvet Transit", 214,
                    "https://images.unsplash.com/photo-1498038432885-c6f3f1b912ee?auto=format&fit=crop&w=600&q=80",
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
                    "[00:00.00]Night Trace\n[00:12.00]Streetlights bend like satellite beams\n[00:26.00]We map the dark between the seams"),
            track("analog-heart", "Analog Heart", "Parhelion", "Signal Bloom", 239,
                    "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=600&q=80",
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3",
                    "[00:00.00]Analog Heart\n[00:13.00]Tape hiss under moonlit glass\n[00:33.00]Old ghosts hum as moments pass")
    );

    private static final Map<String, MusicPlaylistDetail> PLAYLISTS = createPlaylists();

    @Override
    public MusicSourceOption getSource() {
        return MusicSourceOption.builder()
                .code("demo")
                .name("Demo Station")
                .description("内置演示音源，后续可替换为真实 SPI 实现")
                .enabled(Boolean.TRUE)
                .build();
    }

    @Override
    public MusicOverview getOverview() {
        return MusicOverview.builder()
                .hotKeywords(List.of("city pop", "ambient", "late night", "synthwave", "vocal"))
                .featuredPlaylists(PLAYLISTS.values().stream()
                        .map(this::toPlaylistSummary)
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    public MusicSearchResult search(String keyword, int page, int pageSize) {
        String actualKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<MusicTrackSummary> matched = TRACKS.stream()
                .filter(it -> it.getTitle().toLowerCase(Locale.ROOT).contains(actualKeyword)
                        || it.getArtist().toLowerCase(Locale.ROOT).contains(actualKeyword)
                        || it.getAlbum().toLowerCase(Locale.ROOT).contains(actualKeyword))
                .map(this::toTrackSummary)
                .collect(Collectors.toList());
        int fromIndex = Math.max(0, (page - 1) * pageSize);
        int toIndex = Math.min(matched.size(), fromIndex + pageSize);
        List<MusicTrackSummary> pageItems = fromIndex >= matched.size()
                ? List.of()
                : matched.subList(fromIndex, toIndex);
        return MusicSearchResult.builder()
                .source("demo")
                .keyword(keyword)
                .page(page)
                .pageSize(pageSize)
                .total((long) matched.size())
                .tracks(pageItems)
                .build();
    }

    @Override
    public MusicPlaylistSearchResult searchPlaylists(String keyword, int page, int pageSize) {
        String actualKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<MusicPlaylistSummary> matched = PLAYLISTS.values().stream()
                .filter(it -> it.getTitle().toLowerCase(Locale.ROOT).contains(actualKeyword)
                        || it.getAuthor().toLowerCase(Locale.ROOT).contains(actualKeyword)
                        || it.getDescription().toLowerCase(Locale.ROOT).contains(actualKeyword))
                .map(this::toPlaylistSummary)
                .collect(Collectors.toList());
        int fromIndex = Math.max(0, (page - 1) * pageSize);
        int toIndex = Math.min(matched.size(), fromIndex + pageSize);
        List<MusicPlaylistSummary> pageItems = fromIndex >= matched.size()
                ? List.of()
                : matched.subList(fromIndex, toIndex);
        return MusicPlaylistSearchResult.builder()
                .source("demo")
                .keyword(keyword)
                .page(page)
                .pageSize(pageSize)
                .total((long) matched.size())
                .playlists(pageItems)
                .build();
    }

    @Override
    public MusicPlaylistCategoryCatalog getPlaylistCategoryCatalog() {
        return MusicPlaylistCategoryCatalog.builder()
                .source("demo")
                .hotTags(HOT_TAGS)
                .groups(CATEGORY_GROUPS)
                .build();
    }

    @Override
    public MusicPlaylistCategoryResult getCategoryPlaylists(String tagId, int page, int pageSize) {
        String actualTagId = tagId == null ? "" : tagId.trim();
        List<String> playlistIds = CATEGORY_PLAYLISTS.getOrDefault(actualTagId, CATEGORY_PLAYLISTS.getOrDefault("", List.of()));
        List<MusicPlaylistSummary> matched = playlistIds.stream()
                .map(PLAYLISTS::get)
                .filter(item -> item != null)
                .map(this::toPlaylistSummary)
                .collect(Collectors.toList());
        int fromIndex = Math.max(0, (page - 1) * pageSize);
        int toIndex = Math.min(matched.size(), fromIndex + pageSize);
        List<MusicPlaylistSummary> pageItems = fromIndex >= matched.size()
                ? List.of()
                : matched.subList(fromIndex, toIndex);
        return MusicPlaylistCategoryResult.builder()
                .source("demo")
                .tagId(actualTagId)
                .categoryName(resolveCategoryName(actualTagId))
                .page(page)
                .pageSize(pageSize)
                .total((long) matched.size())
                .playlists(pageItems)
                .build();
    }

    @Override
    public MusicPlaylistDetail getPlaylistDetail(String playlistId) {
        MusicPlaylistDetail detail = PLAYLISTS.get(playlistId);
        if (detail == null) {
            throw new IllegalArgumentException("歌单不存在 " + playlistId);
        }
        return detail;
    }

    @Override
    public MusicTrackDetail getTrackDetail(String trackId) {
        return TRACKS.stream()
                .filter(it -> it.getTrackId().equals(trackId))
                .findFirst()
                 .orElseThrow(() -> new IllegalArgumentException("歌曲不存在 " + trackId));
    }

    private MusicPlaylistSummary toPlaylistSummary(MusicPlaylistDetail detail) {
        return MusicPlaylistSummary.builder()
                .playlistId(detail.getPlaylistId())
                .source(detail.getSource())
                .title(detail.getTitle())
                .description(detail.getDescription())
                .coverUrl(detail.getCoverUrl())
                .author(detail.getAuthor())
                .trackCount(detail.getTrackCount())
                .accentColor("linear-gradient(135deg, #12212c 0%, #2f5d73 100%)")
                .build();
    }

    private MusicTrackSummary toTrackSummary(MusicTrackDetail detail) {
        return MusicTrackSummary.builder()
                .trackId(detail.getTrackId())
                .source(detail.getSource())
                .title(detail.getTitle())
                .artist(detail.getArtist())
                .album(detail.getAlbum())
                .coverUrl(detail.getCoverUrl())
                .durationSeconds(detail.getDurationSeconds())
                .build();
    }

    private static MusicTrackDetail track(String trackId, String title, String artist, String album,
                                          int durationSeconds, String coverUrl, String streamUrl, String lyrics) {
        return MusicTrackDetail.builder()
                .trackId(trackId)
                .source("demo")
                .title(title)
                .artist(artist)
                .album(album)
                .coverUrl(coverUrl)
                .streamUrl(streamUrl)
                .durationSeconds(durationSeconds)
                .lyrics(lyrics)
                .build();
    }

    private static Map<String, MusicPlaylistDetail> createPlaylists() {
        Map<String, MusicPlaylistDetail> result = new LinkedHashMap<>();
        result.put("after-neon", playlist(
                "after-neon",
                "After Neon",
                "城市夜行、霓虹流速和轻合成器氛围的入口歌单曲",
                "Signal Desk",
                "https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?auto=format&fit=crop&w=800&q=80",
                List.of("starlit-drift", "violet-circuit", "glass-harbor")
        ));
        result.put("velvet-transit", playlist(
                "velvet-transit",
                "Velvet Transit",
                "更柔和的人声与慢速律动，适合长时间工作或夜间通勤时",
                "Signal Desk",
                "https://images.unsplash.com/photo-1507838153414-b4b713384a76?auto=format&fit=crop&w=800&q=80",
                List.of("quiet-heat", "night-trace", "analog-heart")
        ));
        return result;
    }

    private static Map<String, List<String>> createCategoryPlaylists() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("", List.of("after-neon", "velvet-transit"));
        result.put("late-night", List.of("after-neon"));
        result.put("city-pop", List.of("after-neon"));
        result.put("focus", List.of("velvet-transit"));
        result.put("commute", List.of("after-neon", "velvet-transit"));
        result.put("night-drive", List.of("after-neon"));
        result.put("synthwave", List.of("after-neon"));
        result.put("ambient", List.of("velvet-transit"));
        return result;
    }

    private static MusicPlaylistCategory tag(String tagId, String name, boolean hot) {
        return MusicPlaylistCategory.builder()
                .tagId(tagId)
                .name(name)
                .hot(hot)
                .build();
    }

    private String resolveCategoryName(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return "热门";
        }
        return HOT_TAGS.stream()
                .filter(item -> item.getTagId().equals(tagId))
                .findFirst()
                .map(MusicPlaylistCategory::getName)
                .orElseGet(() -> CATEGORY_GROUPS.stream()
                        .flatMap(item -> item.getTags().stream())
                        .filter(item -> item.getTagId().equals(tagId))
                        .findFirst()
                        .map(MusicPlaylistCategory::getName)
                        .orElse("分类歌单"));
    }

    private static MusicPlaylistDetail playlist(String playlistId, String title, String description,
                                                String author, String coverUrl, List<String> trackIds) {
        List<MusicTrackSummary> tracks = new ArrayList<>();
        for (String trackId : trackIds) {
            MusicTrackDetail detail = TRACKS.stream()
                    .filter(it -> it.getTrackId().equals(trackId))
                    .findFirst()
                    .orElseThrow();
            tracks.add(MusicTrackSummary.builder()
                    .trackId(detail.getTrackId())
                    .source(detail.getSource())
                    .title(detail.getTitle())
                    .artist(detail.getArtist())
                    .album(detail.getAlbum())
                    .coverUrl(detail.getCoverUrl())
                    .durationSeconds(detail.getDurationSeconds())
                    .build());
        }
        return MusicPlaylistDetail.builder()
                .playlistId(playlistId)
                .source("demo")
                .title(title)
                .description(description)
                .coverUrl(coverUrl)
                .author(author)
                .trackCount(tracks.size())
                .tracks(tracks)
                .build();
    }
}


