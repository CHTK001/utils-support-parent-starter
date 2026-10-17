package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LimeTorrents 磁力资源提供者。
 *
 * <p>数据源：<a href="https://www.limetorrents.lol">limetorrents.lol</a>，搜索页
 * {@code /search/all/{关键词}/{page}/} 的结果行
 * {@code table.table2 tr div.tt-name} 中，下载按钮指向
 * {@code itorrents.net/torrent/{infohash}.torrent}，因此一次请求即可从列表页
 * 直接拿到 infohash 并拼接磁力链接，无需再请求详情页。</p>
 *
 * <p>列表页每页 20 条；站点标题在列表中被截断，这里优先使用下载链接
 * {@code title} 参数中的完整标题。</p>
 *
 * <p>该站通常需要代理访问，代理配置见 {@link MagnetSupport}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("limetorrents")
public class LimetorrentsResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://www.limetorrents.lol";

    /**
     * 站点每页条数
     */
    private static final int SITE_PAGE_SIZE = 20;

    /**
     * 下载链接中的 infohash
     */
    private static final Pattern HASH_PATTERN =
            Pattern.compile("itorrents\\.net/torrent/([0-9a-fA-F]{40})\\.torrent");

    /**
     * 下载链接中的标题参数
     */
    private static final Pattern TITLE_PATTERN = Pattern.compile("[?&]title=([^&]+)");

    /**
     * 相对时间（如 26 days ago）
     */
    private static final Pattern RELATIVE_PATTERN = Pattern.compile(
            "(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);

    /**
     * 创建 LimeTorrents资源提供者 实例
     */
    public LimetorrentsResourceProvider() {
        super();
    }

    /**
     * 创建 LimeTorrents资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public LimetorrentsResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("limetorrents");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        try {
            String url = getBaseUrl() + "/search/all/" + MagnetSupport.encode(keyword)
                    + (page > 1 ? "/" + page + "/" : "/");
            Document document = Jsoup.parse(MagnetSupport.get(url, getBaseUrl() + "/"), getBaseUrl());

            List<VideoInfoResult> results = Wraps.list();
            Set<String> seen = new HashSet<>();
            for (Element row : document.select("table.table2 tr")) {
                VideoInfoResult info = parseRow(row, seen);
                if (info != null) {
                    results.add(info);
                }
            }
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(page)
                    .pageSize(SITE_PAGE_SIZE)
                    .total((long) page * SITE_PAGE_SIZE)
                    .totalPages(page + 1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("limetorrents 磁力检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析单行结果。
     *
     * @param row  表格行
     * @param seen 已出现的 infohash（去重）
     * @return 视频信息，无效行返回 空
     */
    private VideoInfoResult parseRow(Element row, Set<String> seen) {
        Element nameArea = row.selectFirst("div.tt-name");
        if (nameArea == null) {
            return null;
        }

        Element torrentLink = null;
        Element detailLink = null;
        for (Element link : nameArea.select("a[href]")) {
            String href = link.attr("href");
            if (torrentLink == null && HASH_PATTERN.matcher(href).find()) {
                torrentLink = link;
            } else if (detailLink == null && href.contains("-torrent-")) {
                detailLink = link;
            }
        }
        if (torrentLink == null) {
            return null;
        }

        String href = torrentLink.attr("href");
        String hash = MagnetSupport.group(HASH_PATTERN, href);
        if (!StringUtils.hasText(hash) || !seen.add(hash.toLowerCase(Locale.ROOT))) {
            return null;
        }

        String title = MagnetSupport.group(TITLE_PATTERN, href);
        if (StringUtils.hasText(title)) {
            title = URLDecoder.decode(title, StandardCharsets.UTF_8).replace('-', ' ');
        } else if (detailLink != null) {
            title = MagnetSupport.clean(detailLink.text());
            if (title.endsWith("..")) {
                title = title.substring(0, title.length() - 2).trim();
            }
        }
        title = MagnetSupport.clean(title);
        if (!StringUtils.hasText(title)) {
            title = hash;
        }

        Elements normalCells = row.select("td.tdnormal");
        String dateText = normalCells.isEmpty() ? "" : MagnetSupport.clean(normalCells.get(0).text());
        String size = normalCells.size() > 1 ? MagnetSupport.clean(normalCells.get(1).text()) : "";
        String seeders = MagnetSupport.clean(text(row.selectFirst("td.tdseed")));
        String leechers = MagnetSupport.clean(text(row.selectFirst("td.tdleech")));
        String category = parseCategory(dateText);

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(hash.toLowerCase(Locale.ROOT));
        info.setVideoName(title);
        info.setVideoTitle(title);
        info.setVideoPlatform(name());
        info.setVideoUrl(MagnetSupport.magnet(hash, title));
        info.setDownloadUrls(info.getVideoUrl());
        info.setVideoSize(size);
        info.setVideoType(category);
        info.setVideoCategory(categoryKind(category));
        info.setVideoPopularity(seeders);
        info.setVideoRemark(buildRemark(seeders, leechers));
        info.setVideoPublishDate(parseRelativeDate(dateText));
        info.setTags("magnet,limetorrents");
        info.setDownloadList(List.of(MagnetSupport.download(title, info.getVideoUrl(), size, name(),
                dateText, category)));
        return info;
    }

    /**
     * 从「时间 - in 分类.」文本中提取分类。
     *
     * @param value 原始文本
     * @return 分类，未命中返回 空字符串
     */
    private static String parseCategory(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        int index = value.lastIndexOf(" in ");
        if (index < 0) {
            return "";
        }
        String category = value.substring(index + 4).trim();
        while (category.endsWith(".")) {
            category = category.substring(0, category.length() - 1).trim();
        }
        return category;
    }

    /**
     * 分类转统一分类。
     *
     * @param category 站内分类
     * @return 统一分类
     */
    private static String categoryKind(String category) {
        if (!StringUtils.hasText(category)) {
            return "video";
        }
        String value = category.toLowerCase(Locale.ROOT);
        if (value.contains("anime")) {
            return "anime";
        }
        if (value.contains("tv")) {
            return "tv";
        }
        if (value.contains("movie") || value.contains("film")) {
            return "movie";
        }
        return "video";
    }

    /**
     * 构建备注。
     *
     * @param seeders  做种数
     * @param leechers 下载数
     * @return 备注文本
     */
    private static String buildRemark(String seeders, String leechers) {
        if (!StringUtils.hasText(seeders) && !StringUtils.hasText(leechers)) {
            return "";
        }
        return "做种=" + seeders + ", 下载=" + leechers;
    }

    /**
     * 解析相对时间（如「26 days ago」）。
     *
     * @param value 原始文本
     * @return 时间，解析失败返回 空
     */
    private static LocalDateTime parseRelativeDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.toLowerCase(Locale.ROOT);
        if (text.contains("just now") || text.contains("today")) {
            return LocalDateTime.now();
        }
        if (text.contains("yesterday")) {
            return LocalDateTime.now().minusDays(1);
        }
        Matcher matcher = RELATIVE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "second":
                return now.minusSeconds(amount);
            case "minute":
                return now.minusMinutes(amount);
            case "hour":
                return now.minusHours(amount);
            case "day":
                return now.minusDays(amount);
            case "week":
                return now.minusWeeks(amount);
            case "month":
                return now.minusMonths(amount);
            case "year":
                return now.minusYears(amount);
            default:
                return null;
        }
    }

    /**
     * 读取元素文本。
     *
     * @param element 元素
     * @return 文本，元素为空返回 空字符串
     */
    private static String text(Element element) {
        return element == null ? "" : MagnetSupport.clean(element.text());
    }

    /**
     * 获取站点地址（支持 {@link VideoSource} 注入覆盖）。
     *
     * @return 站点地址（无末尾斜杠）
     */
    protected String getBaseUrl() {
        if (videoSource != null && StringUtils.hasText(videoSource.getVideoSourceUrl())) {
            String url = videoSource.getVideoSourceUrl().trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return DEFAULT_BASE_URL;
    }

    /**
     * 提供者名称。
     *
     * @return 名称
     */
    public String name() {
        return "limetorrents";
    }
}
