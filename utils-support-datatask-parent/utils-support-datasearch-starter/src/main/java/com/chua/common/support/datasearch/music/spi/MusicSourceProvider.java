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
     * @return Music来源Option 对象
     */
    MusicSourceOption getSource();

    /**
     * 首页概览
     * @return MusicOverview 对象
     */
    MusicOverview getOverview();

    /**
     * 搜索歌曲
     * @param keyword 方法入参 keyword
     * @param page 页，不允许为 null
     * @param pageSize 页大小，不允许为 null
     * @return Music搜索结果 对象
     */
    MusicSearchResult search(String keyword, int page, int pageSize);

    /**
     * 搜索歌单
     * @param keyword 方法入参 keyword
     * @param page 页，不允许为 null
     * @param pageSize 页大小，不允许为 null
     * @return MusicPlaylist搜索结果 对象
     */
    MusicPlaylistSearchResult searchPlaylists(String keyword, int page, int pageSize);

    /**
     * 歌单分类目录
     * @return MusicPlaylistCategoryCatalog 对象
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
     * @param tagId tagID，不允许为 null
     * @param page 页，不允许为 null
     * @param pageSize 页大小，不允许为 null
     * @return MusicPlaylistCategory结果 对象
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
     * @param playlistId playlistID，不允许为 null
     * @return MusicPlaylistDetail 对象
     */
    MusicPlaylistDetail getPlaylistDetail(String playlistId);

    /**
     * 读取歌曲播放详情
     * @param trackId trackID，不允许为 null
     * @return MusicTrackDetail 对象
     */
    MusicTrackDetail getTrackDetail(String trackId);
}
