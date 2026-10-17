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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 种子搜（zhongziso.com）磁力资源提供者。
 *
 * <p>数据源：<a href="https://www.zhongziso.com">zhongziso.com</a>，列表页
 * {@code /list/{关键词}/{page}} 每个 {@code tbody} 为一条结果：
 * {@code h4 a} 是标题（详情链接 {@code /info-{infohash}}），
 * 第二个 {@code tr} 中直接带有磁力链接，因此一次请求即可拿到整页磁力。</p>
 *
 * <p>每页固定 15 条，页码直接拼在路径中（{@code /list/one/2}），已实测生效。</p>
 *
 * <p>该站以中文影视/动漫资源为主，通常需要代理访问，代理配置见 {@link MagnetSupport}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zhongziso")
public class ZhongzisoResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://www.zhongziso.com";

    /**
     * 站点每页条数
     */
    private static final int SITE_PAGE_SIZE = 15;

    /**
     * 磁力中的 btih
     */
    private static final Pattern BTIH_PATTERN = Pattern.compile("urn:btih:([0-9a-fA-F]{40})");

    /**
     * 创建 种子搜资源提供者 实例
     */
    public ZhongzisoResourceProvider() {
        super();
    }

    /**
     * 创建 种子搜资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public ZhongzisoResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("zhongziso");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        try {
            String url = getBaseUrl() + "/list/" + MagnetSupport.encode(keyword) + "/" + page;
            Document document = Jsoup.parse(MagnetSupport.get(url, getBaseUrl() + "/"), getBaseUrl());

            List<VideoInfoResult> results = Wraps.list();
            Set<String> seen = new HashSet<>();
            for (Element group : document.select("tbody")) {
                VideoInfoResult info = parseGroup(group, seen);
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
            return ReturnPageResult.error("zhongziso 磁力检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析一个结果分组。
     *
     * @param group 结果 tbody
     * @param seen  已出现的 infohash（去重）
     * @return 视频信息，无效分组返回 空
     */
    private VideoInfoResult parseGroup(Element group, Set<String> seen) {
        Element magnetLink = group.selectFirst("a[href^=magnet:]");
        if (magnetLink == null) {
            return null;
        }
        String magnet = magnetLink.attr("href").trim();
        String hash = MagnetSupport.group(BTIH_PATTERN, magnet);
        if (!StringUtils.hasText(hash) || !seen.add(hash.toLowerCase(Locale.ROOT))) {
            return null;
        }

        Element titleLink = group.selectFirst("h4 a");
        // 站点把搜索词包在 span.highlight 中，必须用 text() 取整段，否则会丢关键词
        String title = titleLink == null ? "" : MagnetSupport.clean(titleLink.text());
        if (title.endsWith("New!")) {
            title = MagnetSupport.clean(title.substring(0, title.length() - 4));
        }
        if (!StringUtils.hasText(title)) {
            title = hash;
        }

        // 站点把「发布时间 / 大小 / 热度」依次放在三个 strong 中
        Elements strongs = group.select("strong");
        String dateText = strongs.size() > 0 ? MagnetSupport.clean(strongs.get(0).text()) : "";
        String size = strongs.size() > 1 ? MagnetSupport.clean(strongs.get(1).text()) : "";
        String heat = strongs.size() > 2 ? MagnetSupport.clean(strongs.get(2).text()) : "";

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
        info.setVideoPopularity(heat);
        info.setVideoRemark(StringUtils.hasText(heat) ? "热度=" + heat : "");
        info.setVideoPublishDate(parseDate(dateText));
        info.setTags("magnet,zhongziso");
        info.setDownloadList(List.of(MagnetSupport.download(title, magnet, size, name(), dateText, "")));
        return info;
    }

    /**
     * 解析发布日期。
     *
     * @param value 日期文本（yyyy-MM-dd）
     * @return 时间，解析失败返回 空
     */
    private static LocalDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim()).atStartOfDay();
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
        return "zhongziso";
    }
}
