package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.utils.ObjectUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.video.spi.DownloadLinkProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * B站资源提供者实现
 *
 * @author CH
 */
@Spi("bilibili")
public class BilibiliResourceProvider extends AbstractResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(BilibiliResourceProvider.class);
    private static final String BILIBILI_SEARCH_API = "https://api.bilibili.com/x/web-interface/search/all/v2";
    private static final String BILIBILI_VIDEO_URL = "https://www.bilibili.com/video/";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public BilibiliResourceProvider() {
        super();
    }

    public BilibiliResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }

        log.info("从B站资源检索视频: {}", keyword);
        List<VideoInfoResult> results = new ArrayList<>();

        try {
            // 发送请求
            String responseBody = HttpClientFactory.of(BILIBILI_SEARCH_API)
                    .query("keyword", keyword)
                    .query("search_type", ObjectUtils.defaultIfNull(getSearchType(videoSearch), "1"))
                    .query("page", String.valueOf(videoSearch.getPage()))
                    .query("order", ObjectUtils.defaultIfNull(videoSearch.getOrder(), "totalrank"))
                    .query("platform", "pc")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .get()
                    .getBodyString();

            // 解析响应
            if (responseBody != null) {
                JsonNode rootNode = objectMapper.readTree(responseBody);

                // 检查响应状态
                int code = rootNode.path("code").asInt(-1);
                if (code == 0) {
                    JsonNode resultNode = rootNode.path("data").path("result");

                    // 遍历视频结果
                    for (JsonNode typeNode : resultNode) {
                        if ("video".equals(typeNode.path("result_type").asText())) {
                            JsonNode videoList = typeNode.path("data");

                            for (JsonNode video : videoList) {
                                String title = video.path("title").asText();
                                // 去除HTML标签
                                title = title.replaceAll("</?em.*?>", "");

                                String bvid = video.path("bvid").asText();
                                String author = video.path("author").asText();
                                String description = video.path("description").asText();
                                String coverUrl = video.path("pic").asText();
                                String duration = video.path("duration").asText();
                                String pubDate = video.path("pubdate").asText();

                                // 创建VideoInfo对象
                                VideoInfoResult videoInfo = new VideoInfoResult();
                                videoInfo.setVideoName(title);
                                videoInfo.setVideoAliasName(title);
                                videoInfo.setVideoDirector(author);
                                videoInfo.setVideoActor(author);
                                videoInfo.setVideoDescription(description);
                                videoInfo.setVideoScore(Converter.convertIfNecessary(video.path("score").asText(), BigDecimal.class));
                                videoInfo.setVideoCover(coverUrl);
                                videoInfo.setVideoYear(Converter.convertIfNecessary(pubDate, Integer.class));

                                results.add(videoInfo);

                                // 限制结果数量
                                if (results.size() >= 10) {
                                    break;
                                }
                            }
                            break;
                        }
                    }
                } else {
                    String message = rootNode.path("message").asText("未知错误");
                    return ReturnPageResult.error("B站API返回错误: " + message);
                }
            }

            if (results.isEmpty()) {
                return ReturnPageResult.error("B站资源未找到相关视频");
            }

            int size = results.size();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(videoSearch.getPage())
                    .pageSize(videoSearch.getPageSize())
                    .total(size)
                    .totalPages(videoSearch.getPageSize() > 0 ? (size + videoSearch.getPageSize() - 1) / videoSearch.getPageSize() : 0)
                    .build());
        } catch (Exception e) {
            log.error("B站资源检索失败", e);
            return ReturnPageResult.error("B站资源检索失败: " + e.getMessage());
        }
    }

    private String getSearchType(VideoSearch videoSearch) {
        //0=综合（默认），1=视频，2=番剧，3=影视，5=用户，6=专栏，7=直播，8=相簿，9=话题，12=课程
        String category = videoSearch.getVideoType();
        if (StringUtils.hasText(category)) {
            return switch (category) {
                case "video" -> "1";
                case "anime" -> "2";
                case "movie" -> "3";
                case "user" -> "5";
                case "column" -> "6";
                case "live" -> "7";
                case "album" -> "8";
                case "topic" -> "9";
                case "course" -> "12";
                default -> "0";
            };
        }
        return "0";
    }
}

