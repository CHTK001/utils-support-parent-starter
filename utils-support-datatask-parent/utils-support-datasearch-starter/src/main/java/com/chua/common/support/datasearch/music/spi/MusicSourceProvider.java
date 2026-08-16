package com.chua.common.support.datasearch.music.spi;

import com.chua.common.support.datasearch.music.model.MusicOverview;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategoryCatalog;
import com.chua.common.support.datasearch.music.model.MusicPlaylistCategoryResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistDetail;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSearchResult;
import com.chua.common.support.datasearch.music.model.MusicSearchResult;
import com.chua.common.support.datasearch.music.model.MusicSourceOption;
import com.chua.common.support.datasearch.music.model.MusicTrackDetail;

import java.util.List;

/**
 * 音乐音源 SPI
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface MusicSourceProvider {

    /**
     * 当前音源基础信息
     */
    MusicSourceOption getSource();

    /**
     * 首页概览
     */
    MusicOverview getOverview();

    /**
     * 搜索歌曲
     */
    MusicSearchResult search(String keyword, int page, int pageSize);

    /**
     * 搜索歌单
     */
    MusicPlaylistSearchResult searchPlaylists(String keyword, int page, int pageSize);

    /**
     * 歌单分类目录
     */
    default MusicPlaylistCategoryCatalog getPlaylistCategoryCatalog() {
        return MusicPlaylistCategoryCatalog.builder()
                .source(getSource().getCode())
                .hotTags(List.of())
                .groups(List.of())
                .build();
    }

    /**
     * 按分类读取歌单
     */
    default MusicPlaylistCategoryResult getCategoryPlaylists(String tagId, int page, int pageSize) {
        return MusicPlaylistCategoryResult.builder()
                .source(getSource().getCode())
                .tagId(tagId)
                .categoryName(tagId)
                .page(page)
                .pageSize(pageSize)
                .total(0L)
                .playlists(List.of())
                .build();
    }

    /**
     * 读取歌单详情
     */
    MusicPlaylistDetail getPlaylistDetail(String playlistId);

    /**
     * 读取歌曲播放详情
     */
    MusicTrackDetail getTrackDetail(String trackId);
}
