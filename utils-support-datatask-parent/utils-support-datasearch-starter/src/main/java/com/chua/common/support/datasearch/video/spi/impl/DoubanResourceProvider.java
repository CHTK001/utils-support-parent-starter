package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.video.spi.DownloadLinkProvider;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 豆瓣资源提供者实现。
 * <p>
 * 通过豆瓣搜索页面抓取视频的标题、评分、简介、演职员、年份、类型等信息，
 * 并提供下载链接查询能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("douban")
public class DoubanResourceProvider extends AbstractResourceProvider {

    /**
     * 日志对象。
     */
    private static final Logger log = LoggerFactory.getLogger(DoubanResourceProvider.class);

    /**
     * 豆瓣搜索接口地址前缀。
     */
    private static final String DOUBAN_SEARCH_URL = "https://www.douban.com/search?cat=1002&q=";

    /**
     * 请求超时时间，单位毫秒。
     */
    private static final int TIMEOUT = 10000;

    /**
     * 模拟浏览器的 用户-Agent。
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    /**
     * 构造豆瓣资源提供者。
     */
    public DoubanResourceProvider() {
        super();
    }

    /**
     * 构造豆瓣资源提供者。
     *
     * @param videoSource 视频数据源
     */
    public DoubanResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    /**
     * 搜索Resource
    */
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }

        log.info("从豆瓣资源检索视频: {}", keyword);
        List<VideoInfoResult> results = new ArrayList<>();

        try {
            String searchUrl = buildSearchUrl(keyword);
            Document document = Jsoup.connect(searchUrl)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .get();

            Elements items = document.select("div.result");
            for (Element item : items) {
                VideoInfoResult videoInfo = parseSearchItem(item, videoSearch);
                if (videoInfo != null) {
                    results.add(videoInfo);
                    if (results.size() >= 10) {
                        break;
                    }
                }
            }

            if (results.isEmpty()) {
                return ReturnPageResult.error("豆瓣资源未找到相关视频");
            }

            return buildPageResult(results, videoSearch);
        } catch (IOException e) {
            log.error("豆瓣资源检索失败", e);
            return ReturnPageResult.error("豆瓣资源检索失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("豆瓣资源检索过程中发生未知错误", e);
            return ReturnPageResult.error("豆瓣资源检索失败: " + e.getMessage());
        }
    }

    /**
     * 构建搜索 URL。
     *
     * @param keyword 关键词
     * @return 完整搜索 URL
     */
    private String buildSearchUrl(String keyword) {
        return DOUBAN_SEARCH_URL + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    /**
     * 将结果列表封装为分页响应。
     *
     * @param results     结果列表
     * @param videoSearch 搜索参数
     * @return 分页结果
     */
    private ReturnPageResult<VideoInfoResult> buildPageResult(
            List<VideoInfoResult> results, VideoSearch videoSearch) {
        int size = results.size();
        int pageSize = videoSearch.getPageSize();
        int totalPages = pageSize > 0 ? (size + pageSize - 1) / pageSize : 0;

        PageResult<VideoInfoResult> page = PageResult.<VideoInfoResult>builder()
                .data(results)
                .pageNo(videoSearch.getPage())
                .pageSize(pageSize)
                .total(size)
                .totalPages(totalPages)
                .build();

        return ReturnPageResult.of(page);
    }

    /**
     * 解析单个搜索结果条目。
     *
     * @param item        搜索结果 DOM 元素
     * @param videoSearch 搜索参数
     * @return 视频信息对象，解析失败返回 空
     */
    private VideoInfoResult parseSearchItem(Element item, VideoSearch videoSearch) {
        try {
            Element titleElement = item.selectFirst("div.title h3 a");
            if (titleElement == null) {
                return null;
            }

            String title = titleElement.text().trim();
            String detailUrl = titleElement.attr("href");

            String rating = parseRating(item);
            String description = parseDescription(item);
            String coverUrl = parseCover(item);

            Document detailDoc = fetchDetailPage(detailUrl);
            if (detailDoc != null) {
                return parseDetailPage(detailDoc, title, rating, description, coverUrl);
            }

            return buildVideoInfo(title, rating, description, coverUrl);
        } catch (Exception e) {
            log.warn("解析豆瓣搜索结果项失败", e);
            return null;
        }
    }

    /**
     * 解析搜索条目中的评分文本。
     *
     * @param item 搜索结果 DOM 元素
     * @return 评分字符串，缺失时返回“暂无评分”
     */
    private String parseRating(Element item) {
        Element ratingElement = item.selectFirst("div.rating_nums");
        if (ratingElement != null) {
            return ratingElement.text().trim();
        }
        return "暂无评分";
    }

    /**
     * 解析搜索条目中的简介文本。
     *
     * @param item 搜索结果 DOM 元素
     * @return 简介字符串，缺失时返回空串
     */
    private String parseDescription(Element item) {
        Element descElement = item.selectFirst("div.content p");
        if (descElement != null) {
            return descElement.text().trim();
        }
        return "";
    }

    /**
     * 解析搜索条目中的封面地址。
     *
     * @param item 搜索结果 DOM 元素
     * @return 封面 URL，缺失时返回空串
     */
    private String parseCover(Element item) {
        Element coverElement = item.selectFirst("div.pic img");
        if (coverElement != null) {
            return coverElement.attr("src");
        }
        return "";
    }

    /**
     * 获取详情页文档。
     * <p>
     * 添加延迟以避免请求过快被封，并处理跳转 URL。
     * </p>
     *
     * @param detailUrl 详情页 URL
     * @return Jsoup 文档，获取失败返回 空
     */
    private Document fetchDetailPage(String detailUrl) {
        try {
            TimeUnit.MILLISECONDS.sleep(500);

            String html = HttpClientFactory
                    .of(detailUrl)
                    .get()
                    .getBodyString();

            Pattern pattern = Pattern.compile(
                    "window\\.location\\.replace\\('(https?://[^']*)'\\)");
            Matcher matcher = pattern.matcher(html);
            if (!matcher.find()) {
                return null;
            }

            String url = matcher.group(1);
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .get();
        } catch (Exception e) {
            log.warn("获取豆瓣详情页面失败: {}", detailUrl, e);
            return null;
        }
    }

    /**
     * 从详情页解析完整视频信息。
     *
     * @param detailDoc  详情页 DOM
     * @param title      标题
     * @param rating     评分
     * @param description 简介
     * @param coverUrl   封面 URL
     * @return 完整的视频信息对象
     */
    private VideoInfoResult parseDetailPage(Document detailDoc, String title,
                                            String rating, String description, String coverUrl) {
        String actualRating = parseDetailRating(detailDoc);
        Element infoElement = detailDoc.selectFirst("div#info");
        if (infoElement == null) {
            return buildVideoInfo(title, actualRating, description, coverUrl);
        }

        String infoText = infoElement.text();
        InfoFields fields = parseInfoText(infoText);
        return buildVideoInfo(title, actualRating, description, coverUrl, fields);
    }

    /**
     * 解析详情页的评分文本。
     *
     * @param detailDoc 详情页 DOM
     * @return 评分字符串，解析失败返回“暂无评分”
     */
    private String parseDetailRating(Document detailDoc) {
        try {
            return detailDoc.expectFirst(
                    "#interest_sectl > div.rating_wrap.clearbox > div.rating_self.clearfix > strong"
            ).text();
        } catch (Exception e) {
            return "暂无评分";
        }
    }

    /**
     * 详情页解析出的结构化字段集合。
     *
     * @param director 导演
     * @param writer   编剧
     * @param actors   主演
     * @param year     年份
     * @param type     类型
     * @param area     制片国家/地区
     * @param language 语言
     * @param alias    又名
     */
    private record InfoFields(
            String director,
            String writer,
            String actors,
            String year,
            String type,
            String area,
            String language,
            String alias
    ) {}

    /**
     * 解析详情页 信息 文本中的结构化字段。
     *
     * @param infoText 信息 纯文本
     * @return 解析出的字段集合
     */
    private InfoFields parseInfoText(String infoText) {
        String director = extractBetween(infoText, "导演:", "编剧:", "主演:");
        String writer = extractBetween(infoText, "编剧:", "主演:");
        String actors = extractBetween(infoText, "主演:", "类型:");
        String year = extractYear(infoText);
        String type = extractBetween(infoText, "类型:", "制片国家/地区:");
        String area = extractBetween(infoText, "制片国家/地区:", "语言:");
        String language = extractLanguage(infoText);
        String alias = extractAlias(infoText);

        return new InfoFields(director, writer, actors, year, type, area, language, alias);
    }

    /**
     * 从 信息 文本中提取单字段值。
     * <p>
     * 从 {@code start} 标记后开始，到 {@code ends} 中任意一个标记前结束（不含标记）。
     * </p>
     *
     * @param text   信息 全文
     * @param start  起始标记（含）
     * @param ends   结束标记列表
     * @return 提取值，找不到返回空字符串
     */
    private String extractBetween(String text, String start, String... ends) {
        if (!text.contains(start)) {
            return "";
        }

        int from = text.indexOf(start) + start.length();
        for (String end : ends) {
            int idx = text.indexOf(end, from);
            if (idx >= 0) {
                return text.substring(from, idx).trim();
            }
        }

        return text.substring(from).trim();
    }

    /**
     * 提取上映年份。
     *
     * @param infoText 信息 文本
     * @return 年份字符串
     */
    private String extractYear(String infoText) {
        if (!infoText.contains("上映日期:")) {
            return "";
        }

        int from = infoText.indexOf("上映日期:") + 5;
        String raw = infoText.substring(from);

        int end = raw.indexOf("片长:");
        if (end >= 0) {
            raw = raw.substring(0, end).trim();
        }

        int paren = raw.indexOf("(");
        if (paren >= 0) {
            raw = raw.substring(0, paren).trim();
        }

        return raw;
    }

    /**
     * 提取语言字段。
     *
     * @param infoText 信息 文本
     * @return 语言字符串
     */
    private String extractLanguage(String infoText) {
        if (!infoText.contains("语言:")) {
            return "";
        }

        int from = infoText.indexOf("语言:");
        try {
            return infoText.substring(from + 3, infoText.indexOf("上映日期", from + 1)).trim();
        } catch (Exception e) {
            return infoText.substring(from + 3).trim();
        }
    }

    /**
     * 提取又名字段。
     *
     * @param infoText 信息 文本
     * @return 又名字符串
     */
    private String extractAlias(String infoText) {
        if (!infoText.contains("又名:")) {
            return "";
        }

        int from = infoText.indexOf("又名:");
        int end = infoText.indexOf("IMDb", from + 1);
        if (end >= 0) {
            return infoText.substring(from + 3, end).trim();
        }

        return infoText.substring(from + 3).trim();
    }

    /**
     * 根据结构化字段组装 视频信息结果 对象。
     *
     * @param title      标题
     * @param rating     评分
     * @param description 简介
     * @param coverUrl   封面 URL
     * @param fields     解析字段集合
     * @return 视频信息对象
     */
    private VideoInfoResult buildVideoInfo(String title, String rating,
                                           String description, String coverUrl, InfoFields fields) {
        VideoInfoResult videoInfo = new VideoInfoResult();
        videoInfo.setVideoName(title);
        videoInfo.setVideoAliasName(title);
        videoInfo.setVideoDirector(Joiner.on(",").join(fields.director().split("/")));
        videoInfo.setVideoActor(Joiner.on(",").join(fields.actors().split("/")));
        videoInfo.setVideoType(fields.type());
        videoInfo.setVideoDistrict(Joiner.on(",").join(fields.area().split("/")));
        videoInfo.setVideoWriter(Joiner.on(",").join(fields.writer().split("/")));
        videoInfo.setVideoLanguage(fields.language());
        videoInfo.setVideoAliasName(fields.alias());
        videoInfo.setVideoDescription(description);
        videoInfo.setVideoScore(Converter.toBigDecimal(rating));
        videoInfo.setVideoCover(coverUrl);
        videoInfo.setVideoRelease(fields.year());

        if (fields.year().contains("-")) {
            videoInfo.setVideoYear(Converter.convertIfNecessary(fields.year(), LocalDateTime.class)
                    .getYear());
        } else {
            videoInfo.setVideoYear(Converter.convertIfNecessary(fields.year(), Integer.class));
        }

        return videoInfo;
    }

    /**
     * 构造最小可用 视频信息结果（用于详情页解析失败时的兜底）。
     *
     * @param title      标题
     * @param rating     评分
     * @param description 简介
     * @param coverUrl   封面 URL
     * @return 视频信息对象
     */
    private VideoInfoResult buildVideoInfo(String title, String rating,
                                           String description, String coverUrl) {
        VideoInfoResult videoInfo = new VideoInfoResult();
        videoInfo.setVideoName(title);
        videoInfo.setVideoAliasName(title);
        videoInfo.setVideoDescription(description);
        videoInfo.setVideoScore(Converter.toBigDecimal(rating));
        videoInfo.setVideoCover(coverUrl);
        return videoInfo;
    }
}
