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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rutor（rutor.info）磁力资源提供者。
 *
 * <p>数据源：<a href="https://rutor.info">rutor.info</a>，搜索页
 * {@code /search/0/0/000/{pageIndex}/{关键词}}；注意站点页码从 <b>0</b> 开始，
 * 即对外第 1 页对应 {@code pageIndex=0}。</p>
 *
 * <p>结果行 {@code #index tr.gai/tum} 中直接带有磁力链接
 * （{@code a[href^=magnet:]}），但站点给出的 {@code dn} 固定为 {@code rutor.info}，
 * 这里会用真实标题覆盖 {@code dn} 并保留后面的 tracker 参数。</p>
 *
 * <p>该站为俄语综合索引，同一关键词会命中音乐、游戏、软件、书籍，
 * 这里会按标题特征过滤掉非影视条目（见 {@link #isNonVideo(String)}），
 * 只保留影视/剧集/动漫/纪录/体育类内容。</p>
 *
 * <p>该站通常需要代理访问，代理配置见 {@link MagnetSupport}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("rutor")
public class RutorResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://rutor.info";

    /**
     * 站点每页条数
     */
    private static final int SITE_PAGE_SIZE = 100;

    /**
     * 磁力中的 btih
     */
    private static final Pattern BTIH_PATTERN = Pattern.compile("urn:btih:([0-9a-fA-F]{40})");

    /**
     * 日期（如 14 Окт 26）
     */
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{1,2})\\s+([\\p{IsCyrillic}]+)\\.?\\s+(\\d{2,4})");

    /**
     * 俄语月份缩写
     */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("янв", 1), Map.entry("фев", 2), Map.entry("мар", 3), Map.entry("апр", 4),
            Map.entry("май", 5), Map.entry("мая", 5), Map.entry("июн", 6), Map.entry("июл", 7),
            Map.entry("авг", 8), Map.entry("сен", 9), Map.entry("окт", 10), Map.entry("ноя", 11),
            Map.entry("дек", 12));

    /**
     * 文件大小（如 402.26 MB）
     */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("^\\s*[0-9.,]+\\s*[KMGTP]?B\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * 影视特征（出现则一定保留）
     */
    private static final Pattern VIDEO_PATTERN = Pattern.compile(
            "\\b(BDRip|BDRemux|Blu-?Ray|Remux|WEB-?DL|WEB-?Rip|WEBRip|HDTV|HDTVRip|DVDRip|HDRip|SATRip"
                    + "|IPTV|UHD|1080[pi]|2160p|720p|480p|HEVC|AVC|x264|x265|H\\.?264|H\\.?265)\\b"
                    + "|\\[S\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * 非影视特征（音乐/游戏/软件/书籍）
     */
    private static final Pattern NON_VIDEO_PATTERN = Pattern.compile(
            "\\b(MP3|FLAC|APE|ALAC|WAV|M4A|Discography|OST|FB2|EPUB|MOBI|PDF|DJVU|RePack"
                    + "|Portable|VPN|Office|Windows|Nintendo|XBOX|PS[45])\\b"
                    + "|\\bPC\\b|x86\\s*x64|online installation|книг|Дискография|Саундтрек",
            Pattern.CASE_INSENSITIVE);

    /**
     * 创建 Rutor资源提供者 实例
     */
    public RutorResourceProvider() {
        super();
    }

    /**
     * 创建 Rutor资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public RutorResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("rutor");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        try {
            // 站点页码从 0 开始
            String url = getBaseUrl() + "/search/0/0/000/" + (page - 1) + "/"
                    + MagnetSupport.encode(keyword);
            Document document = Jsoup.parse(MagnetSupport.get(url, getBaseUrl() + "/"), getBaseUrl());

            List<VideoInfoResult> results = Wraps.list();
            Set<String> seen = new HashSet<>();
            for (Element row : document.select("#index tr")) {
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
            return ReturnPageResult.error("rutor 磁力检索失败: " + e.getMessage());
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
        Element magnetLink = row.selectFirst("a[href^=magnet:]");
        if (magnetLink == null) {
            return null;
        }
        String hash = MagnetSupport.group(BTIH_PATTERN, magnetLink.attr("href"));
        if (!StringUtils.hasText(hash) || !seen.add(hash.toLowerCase(Locale.ROOT))) {
            return null;
        }

        Elements cells = row.select("td");
        Element titleLink = row.selectFirst("a[href^=/torrent/]");
        String title = titleLink == null ? "" : MagnetSupport.clean(titleLink.text());
        if (!StringUtils.hasText(title)) {
            title = hash;
        }
        if (isNonVideo(title)) {
            return null;
        }

        String dateText = cells.isEmpty() ? "" : MagnetSupport.clean(cells.get(0).text());
        // 部分分类的行没有「评论」列，按内容识别大小，避免错位
        String size = "";
        for (Element cell : cells) {
            String value = MagnetSupport.clean(cell.text());
            if (SIZE_PATTERN.matcher(value).matches()) {
                size = value;
                break;
            }
        }
        String seeders = MagnetSupport.clean(text(row.selectFirst("span.green")));
        String leechers = MagnetSupport.clean(text(row.selectFirst("span.red")));
        String magnet = fillTitle(magnetLink.attr("href").trim(), title);

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(hash.toLowerCase(Locale.ROOT));
        info.setVideoName(title);
        info.setVideoTitle(title);
        info.setVideoPlatform(name());
        info.setVideoUrl(magnet);
        info.setDownloadUrls(magnet);
        info.setVideoSize(size);
        info.setVideoType("video");
        info.setVideoCategory("video");
        info.setVideoPopularity(seeders);
        info.setVideoRemark(buildRemark(seeders, leechers));
        info.setVideoPublishDate(parseDate(dateText));
        info.setTags("magnet,rutor");
        info.setDownloadList(List.of(MagnetSupport.download(title, magnet, size, name(), dateText, "")));
        return info;
    }

    /**
     * 判断是否为非影视条目。
     *
     * <p>rutor 是综合索引，同一关键词会命中音乐、游戏、软件、书籍。
     * 这里只在「命中非影视标记且没有影视标记」时丢弃，
     * 避免误杀标题里恰好含 PC/OST 等词的影片。</p>
     *
     * @param title 资源标题
     * @return 非影视返回 true
     */
    private static boolean isNonVideo(String title) {
        return NON_VIDEO_PATTERN.matcher(title).find() && !VIDEO_PATTERN.matcher(title).find();
    }

    /**
     * 用真实标题覆盖磁力中的 {@code dn}，保留其后的 tracker 参数。
     *
     * @param magnet 站点原始磁力（dn 固定为 rutor.info）
     * @param title  资源标题
     * @return 修正后的磁力
     */
    private static String fillTitle(String magnet, String title) {
        if (!StringUtils.hasText(magnet)) {
            return magnet;
        }
        int dn = magnet.indexOf("&dn=");
        int tracker = dn >= 0 ? magnet.indexOf("&tr=", dn + 4) : -1;
        String head = (dn >= 0 ? magnet.substring(0, dn) : magnet) + "&dn=" + MagnetSupport.encode(title);
        return tracker >= 0 ? head + magnet.substring(tracker) : head;
    }

    /**
     * 构建备注。
     *
     * @param seeders  做种数
     * @param leechers 下载数
     * @return 备注文本
     */
    private static String buildRemark(String seeders, String leechers) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(seeders)) {
            builder.append("做种=").append(seeders);
        }
        if (StringUtils.hasText(leechers)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("下载=").append(leechers);
        }
        return builder.toString();
    }

    /**
     * 解析俄语日期（如 14 Окт 26）。
     *
     * @param value 日期文本
     * @return 时间，解析失败返回 空
     */
    private static LocalDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("сегодня")) {
            return LocalDateTime.now();
        }
        if (text.startsWith("вчера")) {
            return LocalDateTime.now().minusDays(1);
        }
        Matcher matcher = DATE_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            int day = Integer.parseInt(matcher.group(1));
            String monthText = matcher.group(2).toLowerCase(Locale.ROOT);
            Integer month = MONTHS.get(monthText.length() > 3 ? monthText.substring(0, 3) : monthText);
            if (month == null) {
                return null;
            }
            int year = Integer.parseInt(matcher.group(3));
            if (year < 100) {
                year += 2000;
            }
            return LocalDate.of(year, month, day).atStartOfDay();
        } catch (Exception e) {
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
        return "rutor";
    }
}
