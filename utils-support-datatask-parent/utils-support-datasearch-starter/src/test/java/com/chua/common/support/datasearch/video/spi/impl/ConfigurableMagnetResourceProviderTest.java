package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.ConfigurableMagnetResourceProvider.ScrapeConfig;
import com.chua.common.support.spi.ServiceProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通用可配置磁力源（html-magnet）离线回归测试。
 *
 * <p>使用固定 HTML 校验解析逻辑，不发起网络请求：</p>
 * <ol>
 *   <li>SPI 注册名为 {@code HTML-MAGNET}；</li>
 *   <li>未配置站点时返回成功空页，不污染聚合检索；</li>
 *   <li>地址模板支持 {@code {kw}} / {@code {page}} / {@code {pageIndex}}；</li>
 *   <li>标题、大小、日期、磁力均按选择器提取，同 infohash 去重；</li>
 *   <li>无磁力且未配置详情选择器的条目被跳过；</li>
 *   <li>磁力 {@code dn} 为域名占位时替换为真实标题，并保留 tracker；</li>
 *   <li>配置优先级：{@link VideoSource} 字段优先于系统属性。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ConfigurableMagnetResourceProviderTest {

    /**
     * 通过数
     */
    private static int pass = 0;

    /**
     * 失败数
     */
    private static int fail = 0;

    /**
     * 固定 infohash A
     */
    private static final String HASH_A = "0123456789abcdef0123456789abcdef01234567";

    /**
     * 固定 infohash B
     */
    private static final String HASH_B = "fedcba9876543210fedcba9876543210fedcba98";

    /**
     * 列表页样例
     */
    private static final String LIST_HTML = "<html><body>"
            + "<table class='torrent-list'><tbody>"
            + "<tr>"
            + "  <td class='name'><a class='title' href='/view/1'>One Piece 1080p WEB-DL</a></td>"
            + "  <td class='size'>1.4 GB</td>"
            + "  <td class='date'>2026-10-12</td>"
            + "  <td><a class='magnet' href='magnet:?xt=urn:btih:" + HASH_A
            + "&amp;dn=example.com&amp;tr=udp://tracker.example:80'>磁力</a></td>"
            + "</tr>"
            + "<tr>"
            + "  <td class='name'><a class='title' href='/view/2'>One Piece 720p</a></td>"
            + "  <td class='size'>700 MB</td>"
            + "  <td class='date'>2025/01/02 03:04:05</td>"
            + "  <td><a class='magnet' href='magnet:?xt=urn:btih:"
            + HASH_B.toUpperCase() + "'>磁力</a></td>"
            + "</tr>"
            + "<tr>"
            + "  <td class='name'><a class='title' href='/view/3'>No Magnet Row</a></td>"
            + "  <td class='size'>10 MB</td>"
            + "  <td class='date'>2024.05.06</td>"
            + "  <td><span>none</span></td>"
            + "</tr>"
            + "<tr>"
            + "  <td class='name'><a class='title' href='/view/1'>One Piece 1080p WEB-DL</a></td>"
            + "  <td class='size'>1.4 GB</td>"
            + "  <td class='date'>2026-10-12</td>"
            + "  <td><a class='magnet' href='magnet:?xt=urn:btih:" + HASH_A + "'>磁力</a></td>"
            + "</tr>"
            + "</tbody></table>"
            + "</body></html>";

    /**
     * 剪贴板属性样例（部分站点把磁力放在 data-clipboard-text）
     */
    private static final String CLIPBOARD_HTML = "<html><body>"
            + "<div class='row'>"
            + "  <a class='name' href='/x'>Clipboard Item</a>"
            + "  <button class='copy' data-clipboard-text='magnet:?xt=urn:btih:" + HASH_B + "'>复制</button>"
            + "</div>"
            + "</body></html>";

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        verifyRegistration();
        verifyUnconfigured();
        verifyUrlTemplate();
        verifyParsing();
        verifyClipboardMagnet();
        verifyMagnetNormalize();
        verifyDateParse();
        verifyConfigPriority();
        verifyMultiSiteConfig();
        summary();
    }

    /**
     * 校验 SPI 注册。
     */
    private static void verifyRegistration() {
        Map<String, ResourceProvider> providers = ServiceProvider.of(ResourceProvider.class).list();
        check("SPI 已注册 HTML-MAGNET", providers != null && providers.containsKey("HTML-MAGNET"));
        check("已注册实现为通用配置类", providers != null
                && providers.get("HTML-MAGNET") instanceof ConfigurableMagnetResourceProvider);
    }

    /**
     * 校验未配置站点时返回成功空页。
     */
    private static void verifyUnconfigured() {
        ResourceProvider provider = new ConfigurableMagnetResourceProvider();
        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch("one"));
        check("未配置时检索成功", response != null && response.isSuccess());
        check("未配置时结果为空", items(response).isEmpty());
        check("空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());
    }

    /**
     * 校验地址模板替换。
     */
    private static void verifyUrlTemplate() {
        check("模板替换 {kw}", "https://a.test/s?q=one%20piece".equals(
                ConfigurableMagnetResourceProvider.buildUrl("https://a.test/s?q={kw}", "one piece", 1)));
        check("模板替换 {page}", "https://a.test/s?q=x&p=3".equals(
                ConfigurableMagnetResourceProvider.buildUrl("https://a.test/s?q={kw}&p={page}", "x", 3)));
        check("模板替换 {pageIndex} 从 0 开始", "https://a.test/s?q=x&p=2".equals(
                ConfigurableMagnetResourceProvider.buildUrl("https://a.test/s?q={kw}&p={pageIndex}", "x", 3)));
        check("模板同时含 page 与 pageIndex",
                "https://a.test/2/3".equals(ConfigurableMagnetResourceProvider.buildUrl(
                        "https://a.test/{pageIndex}/{page}", "x", 3)));
    }

    /**
     * 校验列表页解析。
     */
    private static void verifyParsing() {
        ScrapeConfig config = ScrapeConfig.of(source(".row, tr", "a.title", "", "", ".size", ".date"));
        List<VideoInfoResult> list = new ConfigurableMagnetResourceProvider()
                .parsePage(LIST_HTML, "https://a.test/search?q=one", 10, config);

        check("解析结果数为 2（无磁力行跳过、重复 hash 去重）", list.size() == 2);
        if (list.size() != 2) {
            return;
        }
        VideoInfoResult first = list.get(0);
        VideoInfoResult second = list.get(1);

        check("标题按选择器提取", "One Piece 1080p WEB-DL".equals(first.getVideoName()));
        check("originId 为小写 infohash", HASH_A.equals(first.getVideoOriginId()));
        check("平台标记为 html-magnet", "html-magnet".equals(first.getVideoPlatform()));
        check("大小按选择器提取", "1.4 GB".equals(first.getVideoSize()));
        check("日期按 yyyy-MM-dd 解析",
                LocalDateTime.of(2026, 10, 12, 0, 0).equals(first.getVideoPublishDate()));
        check("域名 dn 已替换为真实标题", first.getVideoUrl().contains("dn=One%20Piece%201080p%20WEB-DL"));
        check("替换 dn 后保留 tracker", first.getVideoUrl().contains("&tr=udp://tracker.example:80"));
        check("下载项携带同一磁力", hasMagnetDownload(first));
        check("标签包含来源", "magnet,html-magnet".equals(first.getTags()));

        check("第二个 infohash 已小写", HASH_B.equals(second.getVideoOriginId()));
        check("缺少 dn 时补充真实标题", second.getVideoUrl().contains("dn=One%20Piece%20720p"));
        check("日期按 yyyy/MM/dd HH:mm:ss 解析",
                LocalDateTime.of(2025, 1, 2, 3, 4, 5).equals(second.getVideoPublishDate()));

        List<VideoInfoResult> limited = new ConfigurableMagnetResourceProvider()
                .parsePage(LIST_HTML, "https://a.test/", 1, config);
        check("单页上限生效", limited.size() == 1);
    }

    /**
     * 校验 data-clipboard-text 中的磁力提取。
     */
    private static void verifyClipboardMagnet() {
        ScrapeConfig config = ScrapeConfig.of(source(".row", "a.name", "button.copy", "", "", ""));
        List<VideoInfoResult> list = new ConfigurableMagnetResourceProvider()
                .parsePage(CLIPBOARD_HTML, "https://a.test/", 10, config);
        check("自定义磁力选择器可用", list.size() == 1);
        check("从 data-clipboard-text 提取磁力", list.size() == 1
                && list.get(0).getVideoUrl().startsWith("magnet:?xt=urn:btih:" + HASH_B));
    }

    /**
     * 校验磁力 dn 规范化。
     */
    private static void verifyMagnetNormalize() {
        String base = "magnet:?xt=urn:btih:" + HASH_A;
        check("dn 为域名时替换", ConfigurableMagnetResourceProvider
                .normalizeMagnet(base + "&dn=tracker.test", "Real Title").contains("dn=Real%20Title"));
        check("dn 有意义时保留", (base + "&dn=Already%20Good").equals(
                ConfigurableMagnetResourceProvider.normalizeMagnet(base + "&dn=Already%20Good", "Other")));
        check("缺少 dn 时追加", (base + "&dn=Real%20Title").equals(
                ConfigurableMagnetResourceProvider.normalizeMagnet(base, "Real Title")));
        check("dn 替换保留 &tr",
                (base + "&dn=Real%20Title&tr=udp://t:80").equals(ConfigurableMagnetResourceProvider
                        .normalizeMagnet(base + "&dn=tracker.test&tr=udp://t:80", "Real Title")));
    }

    /**
     * 校验日期解析。
     */
    private static void verifyDateParse() {
        check("解析 yyyy-MM-dd", LocalDateTime.of(2026, 10, 12, 0, 0)
                .equals(ConfigurableMagnetResourceProvider.parseDate("2026-10-12")));
        check("解析 yyyy/MM/dd", LocalDateTime.of(2025, 1, 2, 0, 0)
                .equals(ConfigurableMagnetResourceProvider.parseDate("2025/01/02")));
        check("解析 yyyy.MM.dd", LocalDateTime.of(2024, 5, 6, 0, 0)
                .equals(ConfigurableMagnetResourceProvider.parseDate("2024.05.06")));
        check("解析 yyyy-MM-dd HH:mm", LocalDateTime.of(2026, 1, 2, 3, 4)
                .equals(ConfigurableMagnetResourceProvider.parseDate("2026-01-02 03:04")));
        check("非法日期返回空", ConfigurableMagnetResourceProvider.parseDate("3 days ago") == null);
        check("空日期返回空", ConfigurableMagnetResourceProvider.parseDate("") == null);
    }

    /**
     * 校验配置优先级：VideoSource 字段优先于系统属性。
     */
    private static void verifyConfigPriority() {
        String key = "video.search.html.item";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, ".from-property");
            ScrapeConfig config = ScrapeConfig.of(source(".from-source", "a.title", "", "", "", ""));
            check("VideoSource 字段优先于系统属性", ".from-source".equals(config.itemSelector));

            ScrapeConfig fromProperty = ScrapeConfig.of(new VideoSource());
            check("无字段时回退系统属性", ".from-property".equals(fromProperty.itemSelector));
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    /**
     * 校验多站点配置解析。
     */
    private static void verifyMultiSiteConfig() {
        String key = "video.search.html.sites";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "["
                    + "{\"name\":\"nyaa\",\"url\":\"https://nyaa.si/?q={kw}&p={page}\","
                    + "\"item\":\"table.torrent-list tbody tr\",\"title\":\"td:nth-child(2) a\","
                    + "\"size\":\"td:nth-child(4)\",\"date\":\"td:nth-child(5)\"},"
                    + "{\"name\":\"zz\",\"url\":\"https://z.test/list/{kw}/{page}\","
                    + "\"item\":\"tbody\",\"title\":\"h4 a\",\"max\":5}]");
            List<ScrapeConfig> sites = ScrapeConfig.sites(new VideoSource());
            check("多站点配置解析为 2 个站点", sites.size() == 2);
            if (sites.size() == 2) {
                check("站点名称解析正确",
                        "nyaa".equals(sites.get(0).siteName) && "zz".equals(sites.get(1).siteName));
                check("站点条目选择器解析正确",
                        "table.torrent-list tbody tr".equals(sites.get(0).itemSelector));
                check("站点模板解析正确", sites.get(1).urlTemplate.contains("/list/{kw}/{page}"));
                check("站点 max 解析正确", Integer.valueOf(5).equals(sites.get(1).maxResource));
                check("未配置 max 时为 null", sites.get(0).maxResource == null);
            }

            System.setProperty(key, "not-json");
            check("非法多站点配置返回空", ScrapeConfig.sites(new VideoSource()).isEmpty());

            System.clearProperty(key);
            VideoSource single = source(".row", "a.title", "", "", "", "");
            single.setVideoSourceUrl("https://a.test/s?q={kw}");
            List<ScrapeConfig> fallback = ScrapeConfig.sites(single);
            check("无多站点配置时回退单站点", fallback.size() == 1);
            check("单站点名称使用默认值", !fallback.isEmpty() && "html-magnet".equals(fallback.get(0).siteName));
            check("完全未配置时返回空", ScrapeConfig.sites(new VideoSource()).isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    /**
     * 构造带选择器的视频源。
     *
     * @param item   条目选择器
     * @param title  标题选择器
     * @param magnet 磁力选择器
     * @param detail 详情页选择器
     * @param size   大小选择器
     * @param date   日期选择器
     * @return 视频源
     */
    private static VideoSource source(String item, String title, String magnet,
                                      String detail, String size, String date) {
        VideoSource source = new VideoSource();
        source.setVideoSourceItemSelector(item);
        source.setVideoSourceTitleSelector(title);
        source.setVideoSourceMagnetSelector(magnet);
        source.setVideoSourceDetailSelector(detail);
        source.setVideoSourceSizeSelector(size);
        source.setVideoSourceDateSelector(date);
        return source;
    }

    /**
     * 提取结果列表。
     *
     * @param response 响应
     * @return 条目列表
     */
    private static List<VideoInfoResult> items(ReturnPageResult<VideoInfoResult> response) {
        if (response == null || response.getData() == null || response.getData().getData() == null) {
            return List.of();
        }
        return response.getData().getData();
    }

    /**
     * 判断下载列表首项是否携带磁力。
     *
     * @param item 视频条目
     * @return 携带返回 true
     */
    private static boolean hasMagnetDownload(VideoInfoResult item) {
        List<?> downloads = item.getDownloadList();
        if (downloads == null || downloads.isEmpty()) {
            return false;
        }
        Object first = downloads.get(0);
        return first instanceof VideoDownload download
                && download.getVideoDownloadMagnetic() != null
                && download.getVideoDownloadMagnetic().equals(item.getVideoUrl())
                && "magnet".equals(download.getVideoDownloadType());
    }

    /**
     * 输出汇总并在失败时退出非零。
     */
    private static void summary() {
        System.out.println("[ConfigurableMagnetResourceProviderTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 断言。
     *
     * @param name 用例名
     * @param ok   是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
