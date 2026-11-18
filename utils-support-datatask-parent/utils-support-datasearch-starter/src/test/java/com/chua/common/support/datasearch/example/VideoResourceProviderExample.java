// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.example;

import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.SubtitleSearchProvider;
import com.chua.common.support.datasearch.video.spi.VideoProviderRegistry;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 视频 SPI 离线注册表校验示例。
 *
 * <p>与 {@link MusicSourceProviderExample} 同口径：只读 SPI 注册表，不调用
 * {@link ResourceProvider#searchResource}、{@link SubtitleSearchProvider#searchSubtitles}
 * 等真实网络请求，因此可放进 exec 冒烟且不依赖外网。</p>
 *
 * <p>守住三类回归：</p>
 * <ul>
 *   <li>实现数量与 SPI 注册文件一致（实现类被删/漏登记会立刻暴露）；</li>
 *   <li>注册名统一大写且逐个能反查到实现（见 {@link VideoProviderRegistry} 对 {@code @Spi} 名称大写的说明）；</li>
 *   <li>{@link VideoProviderRegistry} 封禁名单大小写不敏感（该类的显式承诺，写错会让封禁判断失效）。</li>
 * </ul>
 *
 * <p>运行方式（模块目录下，exec 示例）：</p>
 * <pre>{@code
 * mvn -o test-compile exec:java@run-video-spi-test
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.45
 */
public final class VideoResourceProviderExample {

    /**
     * {@code ResourceProvider} 的 SPI 注册实现数
     */
    private static final int EXPECTED_RESOURCE_PROVIDERS = 21;

    /**
     * {@code SubtitleSearchProvider} 的 SPI 注册实现数
     */
    private static final int EXPECTED_SUBTITLE_PROVIDERS = 1;

    /**
     * 字幕提供者的注册名（{@code DemoSubtitleSearchProvider} 上的 {@code @Spi} 值）
     */
    private static final String SUBTITLE_PROVIDER_NAME = "SUBHD";

    /**
     * 抽查的资源提供者注册名
     */
    private static final String SAMPLE_RESOURCE_PROVIDER = "BILIBILI";

    /**
     * 封禁名单大小写校验用的临时提供者名（用完即解封）
     */
    private static final String PROBE_PROVIDER = "video-spi-check";

    /**
     * 断言失败计数
     */
    private static int failures;

    private VideoResourceProviderExample() {
    }

    /**
     * 示例入口：校验视频 SPI 注册表，失败则抛出异常。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        ServiceProvider<ResourceProvider> resourceSpi = ServiceProvider.of(ResourceProvider.class);
        Set<String> resourceNames = resourceSpi.getExtensions();
        List<ResourceProvider> resources = resourceSpi.collect();
        System.out.println("resource providers=" + resources.size() + " names=" + resourceNames);

        check("资源提供者实现数=" + EXPECTED_RESOURCE_PROVIDERS,
                resources.size() == EXPECTED_RESOURCE_PROVIDERS);
        check("资源注册名与实现数一致", resourceNames.size() == resources.size());
        check("资源注册名统一大写", resourceNames.stream()
                .allMatch(name -> name.equals(name.toUpperCase(Locale.ROOT))));
        check("包含抽查注册名 " + SAMPLE_RESOURCE_PROVIDER,
                resourceNames.contains(SAMPLE_RESOURCE_PROVIDER));
        for (String name : resourceNames) {
            check("资源注册名可反查 " + name, resourceSpi.getExtension(name) != null);
        }

        ServiceProvider<SubtitleSearchProvider> subtitleSpi =
                ServiceProvider.of(SubtitleSearchProvider.class);
        Set<String> subtitleNames = subtitleSpi.getExtensions();
        List<SubtitleSearchProvider> subtitles = subtitleSpi.collect();
        System.out.println("subtitle providers=" + subtitles.size() + " names=" + subtitleNames);

        check("字幕提供者实现数=" + EXPECTED_SUBTITLE_PROVIDERS,
                subtitles.size() == EXPECTED_SUBTITLE_PROVIDERS);
        check("字幕注册名包含 " + SUBTITLE_PROVIDER_NAME,
                subtitleNames.contains(SUBTITLE_PROVIDER_NAME));
        check("字幕注册名可反查（小写入参）",
                subtitleSpi.getExtension(SUBTITLE_PROVIDER_NAME.toLowerCase(Locale.ROOT)) != null);

        assertRegistryCaseInsensitive();

        if (failures > 0) {
            throw new IllegalStateException("视频 SPI 存在 " + failures + " 处不符合预期");
        }
        System.out.println("VIDEO-SPI-FIXTURE=PASS");
    }

    /**
     * 校验封禁名单对提供者名大小写不敏感，并在结束时恢复现场。
     */
    private static void assertRegistryCaseInsensitive() {
        VideoProviderRegistry.block(PROBE_PROVIDER, VideoProviderRegistry.BlockReason.TIMEOUT);
        try {
            check("封禁名单命中原始名", VideoProviderRegistry.isBlocked(PROBE_PROVIDER));
            check("封禁名单命中大写名",
                    VideoProviderRegistry.isBlocked(PROBE_PROVIDER.toUpperCase(Locale.ROOT)));
            check("封禁原因可读",
                    VideoProviderRegistry.getBlockReason(PROBE_PROVIDER)
                            == VideoProviderRegistry.BlockReason.TIMEOUT);
        } finally {
            VideoProviderRegistry.unblock(PROBE_PROVIDER);
        }
        check("解封后不再命中", !VideoProviderRegistry.isBlocked(PROBE_PROVIDER));
    }

    /**
     * 记录一条断言结果，失败时累计次数。
     *
     * @param name 断言描述
     * @param ok   是否通过
     */
    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
    }
}
