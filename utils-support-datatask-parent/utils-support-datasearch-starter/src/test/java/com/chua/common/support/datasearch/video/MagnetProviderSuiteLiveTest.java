package com.chua.common.support.datasearch.video;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.DmhyResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.NyaaResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.TorrentkittyResourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 需要代理的磁力源联调测试（dmhy / nyaa / torrentkitty）。
 *
 * <p>运行前需具备本地代理（默认 {@code 127.0.0.1:7897}），
 * 可通过 {@code -Dvideo.search.proxy=host:port} 或环境变量
 * {@code VIDEO_SEARCH_PROXY} 覆盖。</p>
 *
 * <p>回归点：</p>
 * <ol>
 *   <li>三个源均需能从搜索结果页直接解析出磁力；</li>
 *   <li>dmhy 的站点磁力 {@code dn} 为空，补标题时不能吃掉 {@code dn} 与 {@code tr}
 *       之间的 {@code &}（否则会得到 {@code dn=标题tr=http...} 的坏链接）；</li>
 *   <li>磁力需同时落到 {@code videoUrl} 与下载列表的 {@code videoDownloadMagnetic}；</li>
 *   <li>页码需真正生效（第 2 页与第 1 页首条不同）。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MagnetProviderSuiteLiveTest {

    /**
     * 通过数
     */
    private static int pass = 0;

    /**
     * 失败数
     */
    private static int fail = 0;

    /**
     * 合法磁力（含 dn 与 tracker 分隔符）
     */
    private static final Pattern DMHY_MAGNET = Pattern.compile(".*&dn=[^&]+&tr=.*");

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        configureProxy();

        Map<String, ResourceProvider> providers = ServiceProvider.of(ResourceProvider.class).list();
        check("SPI 已注册 DMHY", providers.containsKey("DMHY"));
        check("SPI 已注册 NYAA", providers.containsKey("NYAA"));
        check("SPI 已注册 TORRENTKITTY", providers.containsKey("TORRENTKITTY"));

        verify("dmhy", new DmhyResourceProvider(), "火影");
        verify("nyaa", new NyaaResourceProvider(), "one piece");
        verify("torrentkitty", new TorrentkittyResourceProvider(), "one");

        summary();
    }

    /**
     * 配置代理：优先系统属性/环境变量，否则回退到本机 7897。
     */
    private static void configureProxy() {
        if (System.getProperty("video.search.proxy") == null
                && System.getenv("VIDEO_SEARCH_PROXY") == null) {
            System.setProperty("video.search.proxy", "127.0.0.1:7897");
        }
        System.out.println("      proxy=" + System.getProperty("video.search.proxy",
                System.getenv("VIDEO_SEARCH_PROXY")));
    }

    /**
     * 校验单个提供者。
     *
     * @param name     平台名
     * @param provider 提供者
     * @param keyword  关键词
     */
    private static void verify(String name, ResourceProvider provider, String keyword) {
        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch(keyword));
        check(name + " 检索成功", response.isSuccess());
        List<VideoInfoResult> list = items(response);
        check(name + " 有结果", !list.isEmpty());
        if (list.isEmpty()) {
            return;
        }

        list.stream().limit(2).forEach(it -> System.out.println("        [" + name + "] " + it.getVideoName()
                + " | " + it.getVideoSize() + " | " + it.getVideoUrl()));
        check(name + " 均为磁力链接", list.stream().allMatch(it -> it.getVideoUrl() != null
                && it.getVideoUrl().startsWith("magnet:?xt=urn:btih:")));
        check(name + " originId 非空", list.stream().allMatch(it -> it.getVideoOriginId() != null
                && !it.getVideoOriginId().isBlank()));
        check(name + " 平台标记正确", list.stream().allMatch(it -> name.equals(it.getVideoPlatform())));
        check(name + " 下载项携带磁力", list.stream().allMatch(it -> hasMagnetDownload(it)));
        check(name + " 标题非空", list.stream().allMatch(it -> it.getVideoName() != null
                && !it.getVideoName().isBlank()));
        if ("dmhy".equals(name)) {
            check("dmhy 磁力保留 dn 与 tracker 分隔符",
                    list.stream().allMatch(it -> DMHY_MAGNET.matcher(it.getVideoUrl()).matches()));
        }

        VideoSearch second = new VideoSearch(keyword);
        second.setPage(2);
        List<VideoInfoResult> page2 = items(provider.searchResource(second));
        check(name + " 第 2 页有结果", !page2.isEmpty());
        check(name + " 分页生效（第 2 页首条不同）", !page2.isEmpty()
                && !page2.get(0).getVideoOriginId().equals(list.get(0).getVideoOriginId()));

        check(name + " 空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());
    }

    /**
     * 提取结果列表。
     *
     * @param response 响应
     * @return 条目列表
     */
    private static List<VideoInfoResult> items(ReturnPageResult<VideoInfoResult> response) {
        return response.getData() == null || response.getData().getData() == null
                ? List.of() : response.getData().getData();
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
        System.out.println("[MagnetProviderSuiteLiveTest] pass=" + pass + " fail=" + fail);
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
