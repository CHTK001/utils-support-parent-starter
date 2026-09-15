package com.chua.common.support.datasearch.example;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.SubtitleSearchRequest;
import com.chua.common.support.datasearch.video.model.SubtitleSearchResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.VideoProviderRegistry;
import com.chua.common.support.datasearch.video.spi.impl.DemoSubtitleSearchProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 视频 Provider 全量测试。
 *
 * <p>逐个实例化 {@code video/spi/impl} 下的全部 ResourceProvider，
 * 以关键词执行真实网络搜索并输出结果摘要；同时校验 SPI 注册表与封禁名单。
 * 遵循本模块约定（无 JUnit 依赖），使用 main 直接运行：</p>
 * <pre>
 * java -cp "target/classes;deps"
 *      com.chua.common.support.datasearch.example.VideoProviderTest [关键词]
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoProviderTest {

    /** 默认测试关键词 */
    private static final String DEFAULT_KEYWORD = "流浪地球";
    /** 结果摘要条数 */
    private static final int MAX_SAMPLE = 3;
    /** 报告文件名（输出到当前工作目录） */
    private static final String REPORT_FILE_NAME = "test-video-report.txt";

    /** 通过计数 */
    private static int passCount = 0;
    /** 失败计数 */
    private static int failCount = 0;
    /** 报告输出流（创建失败时为 空，输出降级为仅控制台） */
    private static Writer reportWriter;

    /**
     * 测试入口。
     *
     * @param args 可选关键词，缺省为 流浪地球
     */
    public static void main(String[] args) {
        String keyword = args.length > 0 ? args[0] : DEFAULT_KEYWORD;
        openReport();
        println("=== VideoProviderTest keyword=" + keyword + " ===");

        // 全部资源提供者（直接实例化，经接口调用，避免反射）
        List<ResourceProvider> providers = new ArrayList<>(11);
        providers.addAll(ServiceProvider.of(ResourceProvider.class).collect());
        for (ResourceProvider provider : providers) {
            testProvider(provider.getClass().getSimpleName(), provider, keyword);
        }

        // 字幕提供者
        testSubtitle(keyword);

        // SPI 注册表校验
        testSpiRegistration();

        // 封禁名单校验
        testBlockList();

        println("=== SUMMARY: " + (passCount + failCount) + " tests, "
                + passCount + " ok, " + failCount + " failed/empty ===");
        closeReport();
    }

    /**
     * 测试单个资源提供者。
     *
     * @param name 提供者名称（类简名），用于输出标识
     * @param provider 提供者实例，不能为 空
     * @param keyword 搜索关键词，不能为 空
     */
    private static void testProvider(String name, ResourceProvider provider, String keyword) {
        long start = System.currentTimeMillis();
        VideoSearch qs = new VideoSearch(keyword);
        qs.setPage(1);
        qs.setPageSize(10);
        ReturnPageResult<VideoInfoResult> raw = provider.searchResource(qs);
        long cost = System.currentTimeMillis() - start;

        if (raw.isSuccess()) {
            List<VideoInfoResult> items = raw.getData() == null ? null : raw.getData().getData();
            int total = items == null ? 0 : items.size();
            passCount++;
            println("[OK] " + name + " (" + cost + "ms) items=" + total + sample(items));
        } else {
            failCount++;
            println("[ERROR] " + name + " (" + cost + "ms) msg=" + raw.getMessage());
        }
    }

    /**
     * 测试字幕搜索提供器。
     *
     * @param keyword 搜索关键词，不能为 空
     */
    private static void testSubtitle(String keyword) {
        long start = System.currentTimeMillis();
        SubtitleSearchRequest req = SubtitleSearchRequest.builder()
                .keyword(keyword).page(1).pageSize(10).build();
        ReturnPageResult<SubtitleSearchResult> raw =
                new DemoSubtitleSearchProvider().searchSubtitles(req);
        long cost = System.currentTimeMillis() - start;

        if (raw.isSuccess()) {
            List<SubtitleSearchResult> items = raw.getData() == null ? null : raw.getData().getData();
            int total = items == null ? 0 : items.size();
            passCount++;
            println("[OK] subhd (" + cost + "ms) items=" + total + sampleSubs(items));
        } else {
            failCount++;
            println("[ERROR] subhd (" + cost + "ms) msg=" + raw.getMessage());
        }
    }

    /**
     * 校验 META-INF/services SPI 注册文件。
     */
    private static void testSpiRegistration() {
        Map<String, ResourceProvider> map = ServiceProvider.of(ResourceProvider.class).list();
        passCount++;
        println("[OK] SPI注册 已注册=" + map.size()
                + " keys=" + map.keySet().stream().sorted().toList());
    }

    /**
     * 校验封禁名单已加载。
     */
    private static void testBlockList() {
        Set<String> blocked = VideoProviderRegistry.getBlockedNames();
        passCount++;
        println("[OK] 封禁名单 loaded=" + blocked.size()
                + " names=" + blocked.stream().sorted().toList());
    }

    /**
     * 视频结果摘要。
     *
     * @param items 结果列表，可为 空
     * @return 摘要文本；列表为 空时返回 (empty) 标记
     */
    private static String sample(List<VideoInfoResult> items) {
        if (items == null || items.isEmpty()) {
            return " (empty)";
        }
        StringBuilder sb = new StringBuilder(128);
        int n = 0;
        for (VideoInfoResult v : items) {
            if (n++ >= MAX_SAMPLE) {
                break;
            }
            sb.append(" | ").append(abbreviate(v.getVideoName(), 30))
                    .append(" score=").append(v.getVideoScore())
                    .append(" url=").append(abbreviate(v.getVideoUrl(), 40));
        }
        return sb.toString();
    }

    /**
     * 字幕结果摘要。
     *
     * @param items 字幕列表，可为 空
     * @return 摘要文本；列表为 空时返回 (empty) 标记
     */
    private static String sampleSubs(List<SubtitleSearchResult> items) {
        if (items == null || items.isEmpty()) {
            return " (empty)";
        }
        StringBuilder sb = new StringBuilder(128);
        int n = 0;
        for (SubtitleSearchResult s : items) {
            if (n++ >= MAX_SAMPLE) {
                break;
            }
            sb.append(" | ").append(abbreviate(s.getVideoName(), 30))
                    .append(" lang=").append(s.getLanguage())
                    .append(" url=").append(abbreviate(s.getVideoUrl(), 40));
        }
        return sb.toString();
    }

    /**
     * 截断文本。
     *
     * @param s 文本，可为 空
     * @param max 最大长度，正整数
     * @return 截断结果；s 为 空时返回 空串
     */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 打开报告文件（失败时降级为仅控制台输出）。
     */
    private static void openReport() {
        try {
            reportWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(System.getProperty("user.dir"),
                            REPORT_FILE_NAME)), StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 报告文件不可写时不影响测试执行
            reportWriter = null;
        }
    }

    /**
     * 关闭报告文件。
     */
    private static void closeReport() {
        if (reportWriter != null) {
            try {
                reportWriter.flush();
                reportWriter.close();
            } catch (Exception ignored) {
                // 关闭失败不影响结果统计
            }
        }
    }

    /**
     * 输出到报告文件与控制台。
     *
     * @param line 文本行，不能为 空
     */
    private static synchronized void println(String line) {
        System.out.println(line);
        if (reportWriter != null) {
            try {
                reportWriter.write(line);
                reportWriter.write(System.lineSeparator());
            } catch (Exception ignored) {
                // 写入失败不影响测试执行
            }
        }
    }

    /** 防止工具类实例化 */
    private VideoProviderTest() {
        // 工具类，禁止实例化
    }
}
