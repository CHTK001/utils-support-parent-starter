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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Nyaa（nyaa.si）磁力资源提供者。
 *
 * <p>数据源：<a href="https://nyaa.si">nyaa.si</a>，搜索页
 * {@code /?f=0&c=0_0&q={关键词}&p={page}} 的
 * {@code table.torrent-list tbody tr} 中带有完整的磁力链接
 * （{@code a[href^=magnet:]}，btih 为 40 位十六进制）。</p>
 *
 * <p>相比 Nyaa 的 RSS 接口（不支持翻页，{@code &p=} 会被忽略），
 * 这里使用 HTML 列表页，页码可真正生效。</p>
 *
 * <p>该站通常需要代理访问，代理配置见 {@link MagnetSupport}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("nyaa")
public class NyaaResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://nyaa.si";

    /**
     * 磁力中的 btih
     */
    private static final Pattern BTIH_PATTERN = Pattern.compile("urn:btih:([A-Za-z0-9]+)");

    /**
     * 时间格式
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 列表页每页条数（站点固定）
     */
    private static final int SITE_PAGE_SIZE = 75;

    /**
     * 创建 Nyaa资源提供者 实例
     */
    public NyaaResourceProvider() {
        super();
    }

    /**
     * 创建 Nyaa资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public NyaaResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("nyaa");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        try {
            String url = getBaseUrl() + "/?f=0&c=0_0&q=" + MagnetSupport.encode(keyword) + "&p=" + page;
            Document document = Jsoup.parse(MagnetSupport.get(url, getBaseUrl() + "/"), getBaseUrl());

            List<VideoInfoResult> results = Wraps.list();
            for (Element row : document.select("table.torrent-list tbody tr")) {
                VideoInfoResult info = parseRow(row);
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
            return ReturnPageResult.error("nyaa 磁力检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析单行结果。
     *
     * @param row 表格行
     * @return 视频信息，无效行返回 空
     */
    private VideoInfoResult parseRow(Element row) {
        Element magnetLink = row.selectFirst("a[href^=magnet:]");
        Element titleLink = row.selectFirst("td[colspan=2] a");
        if (titleLink == null) {
            titleLink = row.selectFirst("td:nth-child(2) a");
        }
        if (magnetLink == null || titleLink == null) {
            return null;
        }

        String title = MagnetSupport.clean(titleLink.text());
        // 优先使用站点给出的完整磁力（自带 tracker），缺失时用 infohash 重新拼接
        String magnet = magnetLink.attr("href").trim();
        String hash = MagnetSupport.group(BTIH_PATTERN, magnet);
        if (!StringUtils.hasText(title) || !StringUtils.hasText(hash)) {
            return null;
        }
        if (!magnet.toLowerCase().startsWith("magnet:")) {
            magnet = MagnetSupport.magnet(hash, title);
        }

        Elements cells = row.select("td");
        String category = "";
        Element categoryIcon = row.selectFirst("td img.category-icon");
        if (categoryIcon != null) {
            category = MagnetSupport.clean(categoryIcon.attr("alt"));
        }
        String size = cells.size() > 3 ? MagnetSupport.clean(cells.get(3).text()) : "";
        String dateText = cells.size() > 4 ? MagnetSupport.clean(cells.get(4).text()) : "";
        String seeders = cells.size() > 5 ? MagnetSupport.clean(cells.get(5).text()) : "";
        String leechers = cells.size() > 6 ? MagnetSupport.clean(cells.get(6).text()) : "";

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(hash);
        info.setVideoName(title);
        info.setVideoTitle(title);
        info.setVideoPlatform("nyaa");
        info.setVideoUrl(magnet);
        info.setDownloadUrls(magnet);
        info.setVideoSize(size);
        info.setVideoType(category);
        info.setVideoCategory(category.startsWith("Anime") ? "anime" : "other");
        info.setVideoPublishDate(parseDate(dateText));
        info.setVideoPopularity(seeders);
        info.setVideoRemark(buildRemark(seeders, leechers));
        info.setTags("magnet,nyaa");
        info.setDownloadList(List.of(MagnetSupport.download(title, magnet, size, "nyaa", dateText, category)));
        return info;
    }

    /**
     * 构建备注（做种/下载人数）。
     *
     * @param seeders  做种数
     * @param leechers 下载数
     * @return 备注
     */
    private static String buildRemark(String seeders, String leechers) {
        if (!StringUtils.hasText(seeders) && !StringUtils.hasText(leechers)) {
            return "";
        }
        return "做种=" + seeders + ", 下载=" + leechers;
    }

    /**
     * 解析发布时间。
     *
     * @param value 时间文本
     * @return 时间，解析失败返回 空
     */
    private static LocalDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
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
        return "nyaa";
    }
}
