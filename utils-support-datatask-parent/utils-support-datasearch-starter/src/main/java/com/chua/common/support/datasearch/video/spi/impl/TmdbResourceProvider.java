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
 * @since 2025/9/16 16:26
 */
@Spi("tmdb")
public class TmdbResourceProvider extends AbstractResourceProvider {
    static final String IMAGE_URL = "https://image.tmdb.org/t/p/w500";

    public TmdbResourceProvider() {
        super();
    }

    public TmdbResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
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

        @JsonProperty("page")
        private Integer page;

        @JsonProperty("results")
        private List<ResultsDTO> results;

        @JsonProperty("total_pages")
        private Integer totalPages;

        @JsonProperty("total_results")
        private Integer totalResults;

        @NoArgsConstructor
        @Data
        public static class ResultsDTO {

            @JsonProperty("adult")
            private Boolean adult;

            @JsonProperty("backdrop_path")
            private String backdropPath;

            @JsonProperty("genre_ids")
            private List<Integer> genreIds;

            @JsonProperty("id")
            private Integer id;

            @JsonProperty("original_language")
            private String originalLanguage;

            @JsonProperty("original_title")
            private String originalTitle;

            @JsonProperty("overview")
            private String overview;

            @JsonProperty("popularity")
            private Double popularity;

            @JsonProperty("poster_path")
            private String posterPath;

            @JsonProperty("release_date")
            private String releaseDate;

            @JsonProperty("title")
            private String title;

            @JsonProperty("video")
            private Boolean video;

            @JsonProperty("vote_average")
            private Double voteAverage;

            @JsonProperty("vote_count")
            private Integer voteCount;
        }
    }
}

