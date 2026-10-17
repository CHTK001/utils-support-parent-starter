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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Torrent Kitty 磁力资源提供者。
 *
 * <p>数据源：<a href="https://www.torrentkitty.tv">torrentkitty.tv</a>，搜索页
 * {@code /search/{关键词}/{page}} 的 {@code #archiveResult tr} 中直接带有磁力链接
 * （{@code a[href^=magnet:]}，btih 为 40 位十六进制）。</p>
 *
 * <p>该站为综合磁力索引（影视、音乐、软件等），因此不强行归类到
 * movie/anime，仅标记平台为 {@code torrentkitty}。</p>
 *
 * <p>该站通常需要代理访问，代理配置见 {@link MagnetSupport}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("torrentkitty")
public class TorrentkittyResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://www.torrentkitty.tv";

    /**
     * 磁力中的 btih
     */
    private static final Pattern BTIH_PATTERN = Pattern.compile("urn:btih:([A-Za-z0-9]+)");

    /**
     * 创建 Torrent Kitty资源提供者 实例
     */
    public TorrentkittyResourceProvider() {
        super();
    }

    /**
     * 创建 Torrent Kitty资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public TorrentkittyResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("torrentkitty");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        try {
            String path = getBaseUrl() + "/search/" + MagnetSupport.encode(keyword) + "/";
            String url = page > 1 ? path + page : path;
            Document document = Jsoup.parse(MagnetSupport.get(url, getBaseUrl() + "/"), getBaseUrl());

            List<VideoInfoResult> results = Wraps.list();
            for (Element row : document.select("#archiveResult tr")) {
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
            return ReturnPageResult.error("torrentkitty 磁力检索失败: " + e.getMessage());
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
        Element nameCell = row.selectFirst("td.name");
        if (magnetLink == null || nameCell == null) {
            return null;
        }

        String title = MagnetSupport.clean(nameCell.text());
        String magnet = magnetLink.attr("href").trim();
        String hash = MagnetSupport.group(BTIH_PATTERN, magnet);
        if (!StringUtils.hasText(title) || !StringUtils.hasText(hash)) {
            return null;
        }

        String size = text(row.selectFirst("td.size"));
        String dateText = text(row.selectFirst("td.date"));

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(hash);
        info.setVideoName(title);
        info.setVideoTitle(title);
        info.setVideoPlatform("torrentkitty");
        info.setVideoUrl(magnet);
        info.setDownloadUrls(magnet);
        info.setVideoSize(size);
        info.setVideoPublishDate(parseDate(dateText));
        info.setTags("magnet,torrentkitty");
        info.setDownloadList(List.of(MagnetSupport.download(title, magnet, size, "torrentkitty", dateText, "")));
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
     * 解析发布日期。
     *
     * <p>该站部分条目的日期是占位值（如 2069-12-31），未来日期直接忽略。</p>
     *
     * @param value 日期文本
     * @return 时间，解析失败或为未来日期返回 空
     */
    private static LocalDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            if (date.getYear() > Year.now().getValue()) {
                return null;
            }
            return date.atStartOfDay();
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
        return "torrentkitty";
    }
}
