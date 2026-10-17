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
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 动漫花园（dmhy）磁力资源提供者。
 *
 * <p>数据源：<a href="https://share.dmhy.org">share.dmhy.org</a>，
 * 搜索页 {@code /topics/list/page/{page}?keyword={关键词}} 的每条
 * {@code #topic_list tbody tr} 中直接带有磁力链接
 * （{@code a[href^=magnet:]}，btih 为 32 位 base32），因此一次请求即可拿到整页磁力。</p>
 *
 * <p>结果同时写入 {@link VideoInfoResult#getVideoUrl()} 与
 * {@link VideoInfoResult#getDownloadList()}，平台标记为 {@code dmhy}。</p>
 *
 * <p>该站通常需要代理访问，代理配置见 {@link MagnetSupport}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("dmhy")
public class DmhyResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://share.dmhy.org";

    /**
     * 磁力中的 btih
     */
    private static final Pattern BTIH_PATTERN = Pattern.compile("urn:btih:([A-Za-z0-9]+)");

    /**
     * 发布时间（页面同时输出展示时间与隐藏时间，取第一个即可）
     */
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2})");

    /**
     * 文件大小
     */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("^\\d+(?:\\.\\d+)?\\s*[KMGT]B$", Pattern.CASE_INSENSITIVE);

    /**
     * 时间格式
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /**
     * 创建 动漫花园资源提供者 实例
     */
    public DmhyResourceProvider() {
        super();
    }

    /**
     * 创建 动漫花园资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public DmhyResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("dmhy");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        try {
            String url = getBaseUrl() + "/topics/list/page/" + page + "?keyword=" + MagnetSupport.encode(keyword);
            Document document = Jsoup.parse(MagnetSupport.get(url, getBaseUrl() + "/"), getBaseUrl());

            List<VideoInfoResult> results = Wraps.list();
            for (Element row : document.select("#topic_list tbody tr")) {
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
                    .pageSize(results.size())
                    .total(results.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("dmhy 磁力检索失败: " + e.getMessage());
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
        Element titleLink = row.selectFirst("td.title a");
        if (magnetLink == null || titleLink == null) {
            return null;
        }
        String title = MagnetSupport.clean(titleLink.text());
        String magnet = magnetLink.attr("href").trim();
        String hash = MagnetSupport.group(BTIH_PATTERN, magnet);
        if (!StringUtils.hasText(title) || !StringUtils.hasText(hash)) {
            return null;
        }
        // 站点给出的 dn 为空，补上标题并保留后面的 tracker 参数
        if (magnet.contains("&dn=&")) {
            magnet = magnet.replace("&dn=&", "&dn=" + MagnetSupport.encode(title) + "&");
        } else if (!magnet.contains("&dn=")) {
            magnet = magnet + "&dn=" + MagnetSupport.encode(title);
        }

        Elements cells = row.select("td");
        String dateText = cells.isEmpty() ? "" : MagnetSupport.group(DATE_PATTERN, cells.get(0).text());
        String category = cells.size() > 1 ? MagnetSupport.clean(cells.get(1).text()) : "";
        String size = findSize(cells);
        String publisher = cells.isEmpty() ? "" : MagnetSupport.clean(cells.get(cells.size() - 1).text());

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(hash);
        info.setVideoName(title);
        info.setVideoTitle(title);
        info.setVideoPlatform("dmhy");
        info.setVideoUrl(magnet);
        info.setDownloadUrls(magnet);
        info.setVideoSize(size);
        info.setVideoType(category);
        info.setVideoCategory("anime");
        info.setVideoPublishDate(parseDate(dateText));
        info.setVideoRemark(StringUtils.hasText(publisher) ? "发布人=" + publisher : "");
        info.setTags("magnet,dmhy");
        info.setDownloadList(List.of(MagnetSupport.download(title, magnet, size, "dmhy", dateText, category)));
        return info;
    }

    /**
     * 从单元格中查找文件大小。
     *
     * @param cells 单元格集合
     * @return 文件大小，未找到返回 空字符串
     */
    private static String findSize(Elements cells) {
        for (Element cell : cells) {
            String text = MagnetSupport.clean(cell.text());
            if (SIZE_PATTERN.matcher(text).matches()) {
                return text;
            }
        }
        return "";
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
        return "dmhy";
    }
}
