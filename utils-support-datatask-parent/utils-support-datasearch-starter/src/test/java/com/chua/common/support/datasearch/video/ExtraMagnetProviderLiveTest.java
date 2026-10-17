package com.chua.common.support.datasearch.video;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.ApibayResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.LimetorrentsResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.MikanResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.ZhongzisoResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.RutorResourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 新增磁力源联调测试（apibay / mikan / limetorrents）。
 *
 * <p>apibay、mikan 可直连；limetorrents 通常需要本地代理
 * （默认 {@code 127.0.0.1:7897}，可用 {@code -Dvideo.search.proxy=host:port}
 * 或环境变量 {@code VIDEO_SEARCH_PROXY} 覆盖）。</p>
 *
 * <p>回归点：</p>
 * <ol>
 *   <li>三个源均能从列表/接口一次性拿到 infohash 并拼出磁力；</li>
 *   <li>mikan 的 infohash 取自 {@code /Home/Episode/{hash}}（已验证等于种子 infohash）；</li>
 *   <li>limetorrents 的 infohash 取自 {@code itorrents.net/torrent/{hash}.torrent}，
 *       且标题不能保留列表页的 {@code ..} 截断；</li>
 *   <li>apibay 的 {@code No results returned} 占位项需被过滤；</li>
 *   <li>磁力需同时落到 {@code videoUrl} 与下载列表的 {@code videoDownloadMagnetic}。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ExtraMagnetProviderLiveTest {

    /**
     * 通过数
     */
    private static int pass = 0;

    /**
     * 失败数
     */
    private static int fail = 0;

    /**
     * 40 位十六进制 infohash
     */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");

    /**
     * 非影视标记（音乐/游戏/软件/书籍）
     */
    private static final Pattern NON_VIDEO = Pattern.compile(
            "\\b(MP3|FLAC|APE|ALAC|WAV|FB2|EPUB|MOBI|PDF|RePack|Portable|VPN)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        configureProxy();

        Map<String, ResourceProvider> providers = ServiceProvider.of(ResourceProvider.class).list();
        check("SPI 已注册 APIBAY", providers.containsKey("APIBAY"));
        check("SPI 已注册 MIKAN", providers.containsKey("MIKAN"));
        check("SPI 已注册 LIMETORRENTS", providers.containsKey("LIMETORRENTS"));
        check("SPI 已注册 ZHONGZISO", providers.containsKey("ZHONGZISO"));
        check("SPI 已注册 RUTOR", providers.containsKey("RUTOR"));

        verifyApibay();
        verifyMikan();
        verifyLimetorrents();
        verifyZhongziso();
        verifyRutor();

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
     * 校验 apibay（The Pirate Bay JSON 接口，不支持翻页）。
     */
    private static void verifyApibay() {
        ResourceProvider provider = new ApibayResourceProvider();
        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch("one piece"));
        check("apibay 检索成功", response.isSuccess());
        List<VideoInfoResult> list = items(response);
        check("apibay 有结果", !list.isEmpty());
        if (list.isEmpty()) {
            return;
        }
        print("apibay", list);
        check("apibay 均为磁力链接", list.stream().allMatch(ExtraMagnetProviderLiveTest::isMagnet));
        check("apibay originId 为 40 位 infohash", list.stream()
                .allMatch(it -> it.getVideoOriginId() != null && HASH.matcher(it.getVideoOriginId()).matches()));
        check("apibay 平台标记正确", list.stream().allMatch(it -> "apibay".equals(it.getVideoPlatform())));
        check("apibay 下载项携带磁力", list.stream().allMatch(ExtraMagnetProviderLiveTest::hasMagnetDownload));
        check("apibay 大小非空", list.stream().allMatch(it -> it.getVideoSize() != null
                && !it.getVideoSize().isBlank()));
        check("apibay 已过滤空结果占位", list.stream().noneMatch(it ->
                "No results returned".equalsIgnoreCase(it.getVideoName())));
        check("apibay 空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());

        VideoSearch second = new VideoSearch("one piece");
        second.setPage(2);
        check("apibay 第 2 页为空页（接口不分页）", items(provider.searchResource(second)).isEmpty());
    }

    /**
     * 校验 mikan（蜜柑计划 RSS，infohash 在 /Home/Episode/{hash} 中）。
     */
    private static void verifyMikan() {
        ResourceProvider provider = new MikanResourceProvider();
        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch("海贼王"));
        check("mikan 检索成功", response.isSuccess());
        List<VideoInfoResult> list = items(response);
        check("mikan 有结果", !list.isEmpty());
        if (list.isEmpty()) {
            return;
        }
        print("mikan", list);
        check("mikan 均为磁力链接", list.stream().allMatch(ExtraMagnetProviderLiveTest::isMagnet));
        check("mikan originId 为 40 位 infohash", list.stream()
                .allMatch(it -> it.getVideoOriginId() != null && HASH.matcher(it.getVideoOriginId()).matches()));
        check("mikan 磁力包含该 infohash", list.stream()
                .allMatch(it -> it.getVideoUrl().toLowerCase().contains(it.getVideoOriginId())));
        check("mikan 平台标记正确", list.stream().allMatch(it -> "mikan".equals(it.getVideoPlatform())));
        check("mikan 下载项携带磁力", list.stream().allMatch(ExtraMagnetProviderLiveTest::hasMagnetDownload));
        check("mikan 分类为 anime", list.stream().allMatch(it -> "anime".equals(it.getVideoCategory())));
        check("mikan 大小非空", list.stream().allMatch(it -> it.getVideoSize() != null
                && !it.getVideoSize().isBlank()));
        check("mikan 空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());
    }

    /**
     * 校验 limetorrents（列表页 itorrents 链接直接带 infohash）。
     */
    private static void verifyLimetorrents() {
        ResourceProvider provider = new LimetorrentsResourceProvider();
        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch("one"));
        check("limetorrents 检索成功", response.isSuccess());
        List<VideoInfoResult> list = items(response);
        check("limetorrents 有结果", !list.isEmpty());
        if (list.isEmpty()) {
            return;
        }
        print("limetorrents", list);
        check("limetorrents 均为磁力链接", list.stream().allMatch(ExtraMagnetProviderLiveTest::isMagnet));
        check("limetorrents originId 为 40 位 infohash", list.stream()
                .allMatch(it -> it.getVideoOriginId() != null && HASH.matcher(it.getVideoOriginId()).matches()));
        check("limetorrents 平台标记正确",
                list.stream().allMatch(it -> "limetorrents".equals(it.getVideoPlatform())));
        check("limetorrents 下载项携带磁力", list.stream().allMatch(ExtraMagnetProviderLiveTest::hasMagnetDownload));
        check("limetorrents 标题未被 .. 截断", list.stream().noneMatch(it ->
                it.getVideoName() != null && it.getVideoName().endsWith("..")));
        check("limetorrents 大小非空", list.stream().allMatch(it -> it.getVideoSize() != null
                && !it.getVideoSize().isBlank()));

        VideoSearch second = new VideoSearch("one");
        second.setPage(2);
        List<VideoInfoResult> page2 = items(provider.searchResource(second));
        check("limetorrents 第 2 页有结果", !page2.isEmpty());
        check("limetorrents 分页生效（第 2 页首条不同）", !page2.isEmpty()
                && !page2.get(0).getVideoOriginId().equals(list.get(0).getVideoOriginId()));

        check("limetorrents 空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());
    }

    /**
     * 校验 zhongziso（中文种子搜，列表页 tbody 内直接带磁力）。
     */
    private static void verifyZhongziso() {
        ResourceProvider provider = new ZhongzisoResourceProvider();
        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch("one"));
        check("zhongziso 检索成功", response.isSuccess());
        List<VideoInfoResult> list = items(response);
        check("zhongziso 有结果", !list.isEmpty());
        if (list.isEmpty()) {
            return;
        }
        print("zhongziso", list);
        check("zhongziso 均为磁力链接", list.stream().allMatch(ExtraMagnetProviderLiveTest::isMagnet));
        check("zhongziso originId 为 40 位 infohash", list.stream()
                .allMatch(it -> it.getVideoOriginId() != null && HASH.matcher(it.getVideoOriginId()).matches()));
        check("zhongziso 平台标记正确",
                list.stream().allMatch(it -> "zhongziso".equals(it.getVideoPlatform())));
        check("zhongziso 下载项携带磁力", list.stream().allMatch(ExtraMagnetProviderLiveTest::hasMagnetDownload));
        check("zhongziso 标题非空", list.stream().allMatch(it -> it.getVideoName() != null
                && !it.getVideoName().isBlank()));
        check("zhongziso 标题未附带 New! 标记", list.stream().noneMatch(it ->
                it.getVideoName() != null && it.getVideoName().endsWith("New!")));
        check("zhongziso 标题保留高亮关键词", list.stream().allMatch(it ->
                it.getVideoName() != null && it.getVideoName().toLowerCase().contains("one")));
        check("zhongziso 大小非空", list.stream().allMatch(it -> it.getVideoSize() != null
                && !it.getVideoSize().isBlank()));

        VideoSearch second = new VideoSearch("one");
        second.setPage(2);
        List<VideoInfoResult> page2 = items(provider.searchResource(second));
        check("zhongziso 第 2 页有结果", !page2.isEmpty());
        check("zhongziso 分页生效（第 2 页首条不同）", !page2.isEmpty()
                && !page2.get(0).getVideoOriginId().equals(list.get(0).getVideoOriginId()));

        check("zhongziso 空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());
    }

    /**
     * 校验 rutor（俄语综合站，站点页码从 0 开始，磁力 dn 需用真实标题覆盖）。
     */
    private static void verifyRutor() {
        ResourceProvider provider = new RutorResourceProvider();
        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch("matrix"));
        check("rutor 检索成功", response.isSuccess());
        List<VideoInfoResult> list = items(response);
        check("rutor 有结果", !list.isEmpty());
        if (list.isEmpty()) {
            return;
        }
        print("rutor", list);
        check("rutor 均为磁力链接", list.stream().allMatch(ExtraMagnetProviderLiveTest::isMagnet));
        check("rutor originId 为 40 位 infohash", list.stream()
                .allMatch(it -> it.getVideoOriginId() != null && HASH.matcher(it.getVideoOriginId()).matches()));
        check("rutor 平台标记正确", list.stream().allMatch(it -> "rutor".equals(it.getVideoPlatform())));
        check("rutor 下载项携带磁力", list.stream().allMatch(ExtraMagnetProviderLiveTest::hasMagnetDownload));
        check("rutor dn 已替换为真实标题", list.stream()
                .noneMatch(it -> it.getVideoUrl().contains("dn=rutor.info")));
        check("rutor 标题非空", list.stream().allMatch(it -> it.getVideoName() != null
                && !it.getVideoName().isBlank()));
        check("rutor 大小非空", list.stream().allMatch(it -> it.getVideoSize() != null
                && !it.getVideoSize().isBlank()));
        check("rutor 已过滤非影视条目", list.stream().noneMatch(it -> it.getVideoName() != null
                && NON_VIDEO.matcher(it.getVideoName()).find()));

        VideoSearch second = new VideoSearch("matrix");
        second.setPage(2);
        List<VideoInfoResult> page2 = items(provider.searchResource(second));
        check("rutor 第 2 页有结果", !page2.isEmpty());
        check("rutor 分页生效（第 2 页首条不同）", !page2.isEmpty()
                && !page2.get(0).getVideoOriginId().equals(list.get(0).getVideoOriginId()));

        check("rutor 空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());
    }

    /**
     * 打印前两条结果。
     *
     * @param name 平台名
     * @param list 结果列表
     */
    private static void print(String name, List<VideoInfoResult> list) {
        list.stream().limit(2).forEach(it -> System.out.println("        [" + name + "] "
                + it.getVideoName() + " | " + it.getVideoSize() + " | " + it.getVideoUrl()));
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
     * 是否为磁力链接。
     *
     * @param item 视频条目
     * @return 是磁力返回 true
     */
    private static boolean isMagnet(VideoInfoResult item) {
        return item.getVideoUrl() != null && item.getVideoUrl().startsWith("magnet:?xt=urn:btih:");
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
        System.out.println("[ExtraMagnetProviderLiveTest] pass=" + pass + " fail=" + fail);
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
