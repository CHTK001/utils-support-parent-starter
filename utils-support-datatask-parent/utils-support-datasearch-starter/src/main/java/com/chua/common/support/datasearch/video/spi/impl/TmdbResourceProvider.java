package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.utils.DateUtils;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoMark;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.video.spi.DownloadLinkProvider;
import com.chua.common.support.datasearch.video.spi.data.TmdbGenre;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TMDB接口
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("tmdb")
public class TmdbResourceProvider extends AbstractResourceProvider {
    static final String IMAGE_URL = "https://image.tmdb.org/t/p/w500";

    /** 创建 TmdbResourceProvider 实例 */
    public TmdbResourceProvider() {
        super();
    }

    /**
     * 创建 TmdbResourceProvider 实例
     * @param videoSource videoSource
     */
    public TmdbResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    /** 搜索Resource */
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        try {
            String url = videoSource.getVideoSourceUrl();
            if (url == null || url.isBlank()) {
                return ReturnPageResult.empty();
            }
            ClientResponse response = HttpClientFactory.of(url)
                    .json()
                    .query("include_adult", "true")
                    .query("include_video", "true")
                    .query("page", String.valueOf(videoSearch.getPage()))
                    .query("year", videoSearch.getYear() == null ? null : String.valueOf(videoSearch.getYear()))
                    .query("sort_by", videoSearch.getOrder())
                    .header("Authorization", "Bearer %s".formatted(videoSource.getVideoSourceToken()))
                    .get();

            TmdbResult content = Json.fromJson(response.getBodyString(), TmdbResult.class);
            if (null == content || null == content.getResults()) {
                return ReturnPageResult.empty();
            }

            List<TmdbResult.ResultsDTO> results = content.getResults();
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            Integer totalPages = content.getTotalPages();
            List<VideoInfoResult> videoInfoResults = results.stream().map(item -> {
                VideoInfoResult videoInfoResult = new VideoInfoResult();
                videoInfoResult.setVideoOriginId(String.valueOf(item.getId()));
                videoInfoResult.setVideoTitle(item.getOriginalTitle());
                videoInfoResult.setVideoLanguage(item.getOriginalLanguage());
                videoInfoResult.setVideoDescription(item.getOverview());
                videoInfoResult.setVideoPublishDate(DateUtils.parseLocalDateTimeSafe(item.getReleaseDate()));
                videoInfoResult.setVideoVoteCount(item.getVoteCount());
                videoInfoResult.setVideoPlatform("TMDB");
                videoInfoResult.setVideoPopularity(String.valueOf(item.getPopularity()));
                videoInfoResult.setVideoCategory("MV");
                videoInfoResult.setVideoScore(BigDecimal.valueOf(item.getVoteAverage()));
                videoInfoResult.setVideoCover(IMAGE_URL + item.getPosterPath());
                List<Integer> genreIds = item.getGenreIds();
                videoInfoResult.setVideoType(genreIds.stream().map(TmdbGenre::getNameById).collect(Collectors.joining()));

                VideoMark videoMark = new VideoMark();
                videoMark.setVideoMarkType("TMDB");
                videoMark.setVideoMarkPeople(item.getVoteCount());
                videoMark.setVideoMarkScore(BigDecimal.valueOf(item.getVoteAverage()));

                videoInfoResult.setVideoMarkList(Collections.singletonList(videoMark));
                return videoInfoResult;
            }).collect(Collectors.toList());

            int total = totalPages != null ? totalPages : 0;
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(videoInfoResults)
                    .pageNo(videoSearch.getPage())
                    .pageSize(videoSearch.getPageSize())
                    .total(total)
                    .totalPages(videoSearch.getPageSize() > 0 ? (total + videoSearch.getPageSize() - 1) / videoSearch.getPageSize() : 0)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("TMDB资源检索失败: " + e.getMessage());
        }
    }

    /**
     * TMDB搜索结果封装类
     */
    @NoArgsConstructor
    @Data
    static class TmdbResult {

        /** 页码 */
        @JsonProperty("page")
        /** 页 */
        private Integer page;

        /** 结果列表 */
        @JsonProperty("results")
        /** Results */
        private List<ResultsDTO> results;

        /** 总页数 */
        @JsonProperty("total_pages")
        /** 总数pages */
        private Integer totalPages;

        /** 总结果数 */
        @JsonProperty("total_results")
        /** 总数results */
        private Integer totalResults;

        @NoArgsConstructor
        @Data
        public static class ResultsDTO {

            /** 是否成人内容 */
            @JsonProperty("adult")
            /** Adult */
            private Boolean adult;

            /** 背景图路径 */
            @JsonProperty("backdrop_path")
            /** Backdrop路径 */
            private String backdropPath;

            /** 类型标识列表 */
            @JsonProperty("genre_ids")
            /** GenreIDS */
            private List<Integer> genreIds;

            /** 标识 */
            @JsonProperty("id")
            /** ID */
            private Integer id;

            /** 原始语言 */
            @JsonProperty("original_language")
            /** Original语言 */
            private String originalLanguage;

            /** 原始标题 */
            @JsonProperty("original_title")
            /** Original标题 */
            private String originalTitle;

            /** 概述 */
            @JsonProperty("overview")
            /** Overview */
            private String overview;

            /** 热度 */
            @JsonProperty("popularity")
            /** Popularity */
            private Double popularity;

            /** 海报路径 */
            @JsonProperty("poster_path")
            /** Poster路径 */
            private String posterPath;

            /** 发布日期 */
            @JsonProperty("release_date")
            /** Release日期 */
            private String releaseDate;

            /** 标题 */
            @JsonProperty("title")
            /** 标题 */
            private String title;

            /** 是否视频 */
            @JsonProperty("video")
            /** 视频 */
            private Boolean video;

            /** 平均评分 */
            @JsonProperty("vote_average")
            /** Voteaverage */
            private Double voteAverage;

            /** 评分数 */
            @JsonProperty("vote_count")
            /** Vote数量 */
            private Integer voteCount;
        }
    }
}

