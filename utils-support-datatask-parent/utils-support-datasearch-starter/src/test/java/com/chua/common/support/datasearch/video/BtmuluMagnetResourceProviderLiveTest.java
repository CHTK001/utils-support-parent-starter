package com.chua.common.support.datasearch.video;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.BtmuluMagnetResourceProvider;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 磁力资源提供者联调测试（真实抓取 btmulu.net）。
 *
 * <p>回归点：</p>
 * <ol>
 *   <li>搜索结果页里的详情链接是 {@code /hash/{40位infohash}.html}，
 *       必须能由 infohash 直接拼出 {@code magnet:?xt=urn:btih:...}，无需二次请求详情页；</li>
 *   <li>结果列表混有广告条目（外链而非 {@code /hash/}），必须跳过，
 *       否则会产出空 infohash；</li>
 *   <li>{@code page} 参数需真正透传给站点（{@code ?page=N}），
 *       第 2 页结果不应与第 1 页重复；</li>
 *   <li>磁力需同时落到 {@code videoUrl} 与下载列表的 {@code videoDownloadMagnetic}。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BtmuluMagnetResourceProviderLiveTest {

    /**
     * 通过数
     */
    private static int pass = 0;

    /**
     * 失败数
     */
    private static int fail = 0;

    /**
     * 40 位 infohash
     */
    private static final Pattern HASH = Pattern.compile("^[0-9a-fA-F]{40}$");

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        Map<String, ResourceProvider> providers = ServiceProvider.of(ResourceProvider.class).list();
        System.out.println("      registered=" + providers.keySet());
        check("SPI 已注册 BTMULU", providers.containsKey("BTMULU"));

        BtmuluMagnetResourceProvider provider = new BtmuluMagnetResourceProvider();
        ReturnPageResult<VideoInfoResult> first = provider.searchResource(new VideoSearch("火影"));
        check("关键词[火影]检索成功", first.isSuccess());
        List<VideoInfoResult> list = items(first.getData());
        check("关键词[火影]有结果", !list.isEmpty());
        if (list.isEmpty()) {
            summary();
            return;
        }

        list.stream().limit(3).forEach(it -> System.out.println("        " + it.getVideoName()
                + " | " + it.getVideoSize() + " | " + it.getVideoRemark()
                + " | " + it.getVideoUrl()));

        check("每条都是 magnet:?xt=urn:btih: 链接",
                list.stream().allMatch(it -> it.getVideoUrl() != null
                        && it.getVideoUrl().startsWith("magnet:?xt=urn:btih:")));
        check("infohash 为 40 位十六进制",
                list.stream().allMatch(it -> it.getVideoOriginId() != null
                        && HASH.matcher(it.getVideoOriginId()).matches()));
        check("平台标记为 btmulu",
                list.stream().allMatch(it -> "btmulu".equals(it.getVideoPlatform())));
        check("标题非空", list.stream().allMatch(it -> !it.getVideoName().isBlank()));
        check("大小非空", list.stream().allMatch(it -> it.getVideoSize() != null
                && !it.getVideoSize().isBlank()));
        check("下载项携带磁力", list.stream().allMatch(BtmuluMagnetResourceProviderLiveTest::hasMagnetDownload));
        check("磁力链接含 dn 标题",
                list.stream().allMatch(it -> it.getVideoUrl().contains("&dn=")));

        VideoSearch second = new VideoSearch("火影");
        second.setPage(2);
        List<VideoInfoResult> page2 = items(provider.searchResource(second).getData());
        check("第 2 页有结果", !page2.isEmpty());
        check("分页生效（第 2 页与第 1 页不重复）",
                !page2.isEmpty() && !page2.get(0).getVideoOriginId().equals(list.get(0).getVideoOriginId()));

        check("空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());

        summary();
    }

    /**
     * 提取结果列表。
     *
     * @param pageResult 分页结果
     * @return 条目列表
     */
    private static List<VideoInfoResult> items(PageResult<VideoInfoResult> pageResult) {
        return pageResult == null || pageResult.getData() == null
                ? List.of() : pageResult.getData();
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
                && download.getVideoDownloadMagnetic().equals(item.getVideoUrl());
    }

    /**
     * 输出汇总并在失败时退出非零。
     */
    private static void summary() {
        System.out.println("[BtmuluMagnetResourceProviderLiveTest] pass=" + pass + " fail=" + fail);
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
