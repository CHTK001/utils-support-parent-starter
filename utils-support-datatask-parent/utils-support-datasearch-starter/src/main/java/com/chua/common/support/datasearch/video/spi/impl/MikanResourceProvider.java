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
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蜜柑计划（mikanani.me）磁力资源提供者。
 *
 * <p>数据源：<a href="https://mikanani.me">mikanani.me</a>，字幕组动漫 RSS 搜索
 * {@code /RSS/Search?searchstr={关键词}}，每个 {@code item} 的
 * {@code link} 形如 {@code /Home/Episode/{infohash}}。</p>
 *
 * <p>经实测该路径中的 40 位十六进制串就是种子的 infohash
 * （与下载得到的 {@code .torrent} 的 info 段 SHA-1 完全一致），
 * 因此无需下载种子即可直接拼接磁力链接，一次请求拿到整页结果。</p>
 *
 * <p>站点为中文动漫字幕组索引，检索关键词建议使用番剧名（中/日/罗马字均可）。
 * RSS 搜索一次返回全部命中且不支持页码，{@code page > 1} 时返回空页。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("mikan")
public class MikanResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://mikanani.me";

    /**
     * RSS 中的 infohash（40 位十六进制）
     */
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-fA-F]{40}");

    /**
     * 描述中的体积（如 [360.2 MB]）
     */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("\\[\\s*([0-9.]+\\s*[KMGTP]?B)\\s*]", Pattern.CASE_INSENSITIVE);

    /**
     * 字幕组（标题开头的方括号）
     */
    private static final Pattern SUBGROUP_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)]");

    /**
     * 清晰度
     */
    private static final Pattern QUALITY_PATTERN =
            Pattern.compile("(\\d{3,4}[pi]|4K|8K|BD|WEB-?DL|WEBRip|HEVC)", Pattern.CASE_INSENSITIVE);

    /**
     * RSS 时间格式
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * 创建 蜜柑计划资源提供者 实例
     */
    public MikanResourceProvider() {
        super();
    }

    /**
     * 创建 蜜柑计划资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public MikanResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("mikan");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        if (page > 1) {
            // RSS 搜索不分页
            return ReturnPageResult.empty();
        }
        try {
            String url = getBaseUrl() + "/RSS/Search?searchstr=" + MagnetSupport.encode(keyword);
            String body = MagnetSupport.get(url, getBaseUrl() + "/");
            Document document = Jsoup.parse(body, getBaseUrl(), Parser.xmlParser());

            List<VideoInfoResult> results = Wraps.list();
            for (Element item : document.select("item")) {
                VideoInfoResult info = parseItem(item);
                if (info != null) {
                    results.add(info);
                }
            }
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(1)
                    .pageSize(results.size())
                    .total(results.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("mikan 磁力检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析单个 RSS 条目。
     *
     * @param item item 元素
     * @return 视频信息，无效项返回 空
     */
    private VideoInfoResult parseItem(Element item) {
        String title = text(item.selectFirst("title"));
        Element link = item.selectFirst("link");
        String episodeUrl = link == null ? "" : link.text().trim();
        String hash = lastSegment(episodeUrl);
        if (!StringUtils.hasText(title) || !HASH_PATTERN.matcher(hash).matches()) {
            return null;
        }

        String description = text(item.selectFirst("description"));
        String size = MagnetSupport.group(SIZE_PATTERN, description);
        if (!StringUtils.hasText(size)) {
            long length = longValue(text(item.selectFirst("contentLength")));
            size = formatSize(length);
        }

        String publishTime = text(item.selectFirst("pubDate"));
        LocalDateTime publishDate = parseDate(publishTime);
        String subgroup = MagnetSupport.group(SUBGROUP_PATTERN, title);
        String quality = MagnetSupport.group(QUALITY_PATTERN, title);
        String magnet = MagnetSupport.magnet(hash, title);

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(hash.toLowerCase(Locale.ROOT));
        info.setVideoName(title);
        info.setVideoTitle(title);
        info.setVideoPlatform(name());
        info.setVideoUrl(magnet);
        info.setDownloadUrls(magnet);
        info.setVideoSize(size);
        info.setVideoQuality(quality);
        info.setVideoType("anime");
        info.setVideoCategory("anime");
        info.setVideoPublishDate(publishDate);
        info.setVideoRemark(StringUtils.hasText(subgroup) ? "字幕组=" + subgroup : "");
        info.setVideoDescription(MagnetSupport.truncate(description, 500));
        info.setTags("magnet,mikan,anime");
        info.setDownloadList(List.of(MagnetSupport.download(title, magnet, size, name(),
                publishTime, quality)));
        return info;
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
     * 取路径最后一段。
     *
     * @param url 地址
     * @return 最后一段，无路径返回 空字符串
     */
    private static String lastSegment(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String value = url.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    /**
     * 解析文本为长整数。
     *
     * @param value 文本
     * @return 长整数，解析失败返回 0
     */
    private static long longValue(String value) {
        if (!StringUtils.hasText(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 字节数转可读大小。
     *
     * @param bytes 字节数
     * @return 可读大小，无效值返回 空字符串
     */
    private static String formatSize(long bytes) {
        if (bytes <= 0L) {
            return "";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int unit = 0;
        while (value >= 1024D && unit < units.length - 1) {
            value /= 1024D;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    /**
     * 解析 RSS 发布时间。
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
        return "mikan";
    }
}
