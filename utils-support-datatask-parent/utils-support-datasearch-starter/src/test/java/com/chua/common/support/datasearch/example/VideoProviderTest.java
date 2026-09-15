package com.chua.common.support.datasearch.example;

import com.chua.common.support.datasearch.video.model.SubtitleSearchRequest;
import com.chua.common.support.datasearch.video.model.SubtitleSearchResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.impl.BilibiliResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.Douban2ResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.DoubanResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.DuanJuWangResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.IKanTvResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.MacCmsResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.MuouResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.QuarkResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.QuarktvResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.WanouResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.WuJiResourceProvider;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 视频 Provider 全量测试：逐个实例化 {@code video/spi/impl} 下的全部 ResourceProvider，
 * 以关键词"流浪地球"执行真实搜索并输出结果摘要。
 *
 * <p>遵循本模块约定（无 JUnit 依赖），使用 {@code main} 直接运行：</p>
 * <pre>
 * java -cp "target/classes;deps" com.chua.common.support.datasearch.example.VideoProviderTest [关键词]
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoProviderTest {

    /** 默认测试关键词 */
    private static final String DEFAULT_KEYWORD = "流浪地球";
    /** 结果摘要行数 */
    private static final int MAX_SAMPLE = 3;

    /** 测试结果统计 */
    private static int passCount = 0;
    /** 测试结果统计 */
    private static int failCount = 0;
    /** 报告输出流 */
    private static Writer out;

    /**
     * 测试入口。
     *
     * @param args 可选关键词，缺省为 流浪地球
     */
    public static void main(String[] args) {
        String keyword = args.length > 0 ? args[0] : DEFAULT_KEYWORD;
        try {
            out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(System.getProperty("user.dir"),
                            "test-video-report.txt")), StandardCharsets.UTF_8));
        } catch (Exception e) {
            out = null;
        }
        println("=== VideoProviderTest keyword=" + keyword + " ===");

        Map<String, Class<?>> providers = new HashMap<>();
        providers.put("bilibili", BilibiliResourceProvider.class);
        providers.put("douban", DoubanResourceProvider.class);
        providers.put("douban2", Douban2ResourceProvider.class);
        providers.put("duanjuw", DuanJuWangResourceProvider.class);
        providers.put("ikantv", IKanTvResourceProvider.class);
        providers.put("maccms", MacCmsResourceProvider.class);
        providers.put("muou", MuouResourceProvider.class);
        providers.put("quark", QuarkResourceProvider.class);
        providers.put("quarktv", QuarktvResourceProvider.class);
        providers.put("wanou", WanouResourceProvider.class);
        providers.put("wuji", WuJiResourceProvider.class);

        for (Map.Entry<String, Class<?>> e : providers.entrySet()) {
            test(e.getKey(), e.getValue(), keyword);
        }

        testSubtitle(keyword);

        // SPI 注册表校验
        testSpiRegistration();

        // 封禁名单校验
        testBlockList();

        println("=== SUMMARY: " + (passCount + failCount) + " tests, "
                + passCount + " ok, " + failCount + " failed/empty ===");
        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 测试单个资源提供者。
     *
     * @param name 提供者名称
     * @param cls 提供者类
     * @param keyword 关键词
     */
    private static void test(String name, Class<?> cls, String keyword) {
        long start = System.currentTimeMillis();
        try {
            Object instance = cls.getConstructor().newInstance();
            Method search = cls.getMethod("searchResource", VideoSearch.class);
            VideoSearch qs = new VideoSearch(keyword);
            qs.setPage(1);
            qs.setPageSize(10);
            @SuppressWarnings("unchecked")
            Object raw = search.invoke(instance, qs);

            boolean success = invokeBoolean(raw, "isSuccess");
            String message = (String) invoke(raw, "getMessage");
            long cost = System.currentTimeMillis() - start;

            if (success) {
                java.util.List<?> items = extractData(raw);
                int total = items == null ? 0 : items.size();
                passCount++;
                println("[OK] " + name + " (" + cost + "ms) items=" + total
                        + sample(items));
            } else {
                failCount++;
                println("[ERROR] " + name + " (" + cost + "ms) msg=" + message);
            }
        } catch (Exception ex) {
            failCount++;
            println("[ERROR] " + name + " EXCEPTION: " + ex);
        }
    }

    /**
     * 测试字幕搜索提供器。
     *
     * @param keyword 关键词
     */
    private static void testSubtitle(String keyword) {
        long start = System.currentTimeMillis();
        try {
            Class<?> cls = Class.forName(
                    "com.chua.common.support.datasearch.video.spi.impl.DemoSubtitleSearchProvider");
            Object instance = cls.getConstructor().newInstance();
            Method search = cls.getMethod("searchSubtitles", SubtitleSearchRequest.class);
            SubtitleSearchRequest req = new SubtitleSearchRequest();
            req.setKeyword(keyword);
            req.setPage(1);
            req.setPageSize(10);
            Object raw = search.invoke(instance, req);

            boolean success = invokeBoolean(raw, "isSuccess");
            String message = (String) invoke(raw, "getMessage");
            long cost = System.currentTimeMillis() - start;

            if (success) {
                java.util.List<?> items = extractData(raw);
                int total = items == null ? 0 : items.size();
                passCount++;
                println("[OK] subhd (" + cost + "ms) items=" + total
                        + sampleSubs(items));
            } else {
                failCount++;
                println("[ERROR] subhd (" + cost + "ms) msg=" + message);
            }
        } catch (Exception ex) {
            failCount++;
            println("[ERROR] subhd EXCEPTION: " + ex);
        }
    }

    /**
     * 校验 META-INF/services SPI 注册文件。
     */
    private static void testSpiRegistration() {
        try {
            Map<String, com.chua.common.support.datasearch.video.spi.ResourceProvider> map =
                    com.chua.common.support.spi.ServiceProvider.of(
                            com.chua.common.support.datasearch.video.spi.ResourceProvider.class).list();
            passCount++;
            println("[OK] SPI注册 已注册=" + map.size()
                    + " keys=" + map.keySet().stream().sorted().toList());
        } catch (Exception ex) {
            failCount++;
            println("[ERROR] SPI注册 EXCEPTION: " + ex);
        }
    }

    /**
     * 校验封禁名单已加载。
     */
    private static void testBlockList() {
        try {
            java.util.Set<String> blocked =
                    com.chua.common.support.datasearch.video.spi.VideoProviderRegistry.getBlockedNames();
            passCount++;
            println("[OK] 封禁名单 loaded=" + blocked.size() + " names="
                    + blocked.stream().sorted().toList());
        } catch (Exception ex) {
            failCount++;
            println("[ERROR] 封禁名单 EXCEPTION: " + ex);
        }
    }

    /**
     * 从 ReturnPageResult 中提取数据列表。
     *
     * @param raw 原始结果
     * @return 数据列表，无则 空
     */
    private static java.util.List<?> extractData(Object raw) {
        try {
            Object data = invoke(raw, "getData");
            if (data == null) {
                return null;
            }
            return (java.util.List<?>) invoke(data, "getData");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 视频结果摘要。
     *
     * @param items 结果列表
     * @return 摘要文本
     */
    private static String sample(java.util.List<?> items) {
        if (items == null || items.isEmpty()) {
            return " (empty)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Object item : items) {
            if (n++ >= MAX_SAMPLE) {
                break;
            }
            try {
                VideoInfoResult v = (VideoInfoResult) item;
                sb.append(" | ").append(abbreviate(v.getVideoName(), 30))
                        .append(" score=").append(v.getVideoScore())
                        .append(" url=").append(abbreviate(v.getVideoUrl(), 40));
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    /**
     * 字幕结果摘要。
     *
     * @param items 字幕列表
     * @return 摘要文本
     */
    private static String sampleSubs(java.util.List<?> items) {
        if (items == null || items.isEmpty()) {
            return " (empty)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Object item : items) {
            if (n++ >= MAX_SAMPLE) {
                break;
            }
            try {
                SubtitleSearchResult s = (SubtitleSearchResult) item;
                sb.append(" | ").append(abbreviate(s.getVideoName(), 30))
                        .append(" lang=").append(s.getLanguage())
                        .append(" url=").append(abbreviate(s.getVideoUrl(), 40));
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    /**
     * 截断文本。
     *
     * @param s 文本
     * @param max 最大长度
     * @return 截断结果
     */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 反射调用无参方法。
     *
     * @param obj 对象
     * @param method 方法名
     * @return 调用结果
     * @throws Exception 反射异常
     */
    private static Object invoke(Object obj, String method) throws Exception {
        Method m = obj.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(obj);
    }

    /**
     * 反射调用布尔方法。
     *
     * @param obj 对象
     * @param method 方法名
     * @return 布尔结果
     * @throws Exception 反射异常
     */
    private static boolean invokeBoolean(Object obj, String method) throws Exception {
        Object v = invoke(obj, method);
        return Boolean.TRUE.equals(v);
    }

    /**
     * 输出到报告文件与控制台。
     *
     * @param line 文本行
     */
    private static synchronized void println(String line) {
        System.out.println(line);
        if (out != null) {
            try {
                out.write(line);
                out.write(System.lineSeparator());
            } catch (Exception ignored) {
            }
        }
    }

    /** 防止工具类实例化 */
    private VideoProviderTest() {}
}
