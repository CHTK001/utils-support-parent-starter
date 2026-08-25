package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Artificial Analysis 统一模型指标提供者。
 *
 * <p>单一数据源覆盖绝大部分主流模型的多维指标：</p>
 * <ul>
 *   <li>价格：输入/输出分项价（USD / 百万 Token，标准档）</li>
 *   <li>智能：Artificial Analysis Intelligence Index</li>
 *   <li>速度：输出速度中位数（Token/秒）</li>
 *   <li>延迟：首 Token 中位耗时（秒）</li>
 *   <li>图标：厂商 logo 地址</li>
 * </ul>
 *
 * <p>数据来源为页面内嵌的 Next.js flight 数据（SSR），普通 HTTP 即可获取，
 * 无需登录。解析失败时返回空列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("artificialanalysis")
public class ArtificialAnalysisModelMetricsProvider extends AbstractModelMetricsProvider {

    /** Leaderboard_url */
    private static final String LEADERBOARD_URL = "https://artificialanalysis.ai/zh/leaderboards/providers";

    /** 图标地址前缀（相对路径补全用） */
    private static final String LOGO_BASE = "https://artificialanalysis.ai";

    /** 记录锚点：转义形态的 \"model\":{\"slug\":\"xxx\" */
    private static final Pattern ANCHOR =
            Pattern.compile("\\\\\"model\\\\\":\\{\\\\\"slug\\\\\":\\\\\\\"");

    /** 目录条目：{\"slug\":\"x\",\"name\":\"y\",...,\"creator\":{\"name\":\"z\",\"logo\":\"/img/logos/x.svg\"}} */
    private static final Pattern CATALOG_ENTRY = Pattern.compile(
            "\\{\"slug\":\"([^\"]+)\",\"name\":\"([^\"]*)\"([^\\[]*?)"
                    + "\"creator\":\\{\"id\":\"[^\"]*\",\"name\":\"([^\"]*)\",\"logo\":\"([^\"]*)\"");

    /**
     * 从排行榜抓取全量模型指标与价格。
     *
     * @return 模型指标列表，页面不可达或结构变化时返回空列表
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        String html = fetchUrl(LEADERBOARD_URL);
        if (html == null || html.isEmpty()) {
            return readClasspathPricing();
        }
        List<ModelDefinition> parsed = parseFlightData(html);
        return parsed.isEmpty() ? readClasspathPricing() : parsed;
    }

    /**
     * 解析 flight 数据中的模型目录与逐模型指标记录。
     *
     * @param html 页面原始 HTML（含转义）
     * @return 按 slug 去重后的模型指标列表
     */
    private List<ModelDefinition> parseFlightData(String html) {
        // 目录：slug -> [displayName, creatorName, logo]
        Map<String, String[]> catalog = new LinkedHashMap<>();
        Matcher cat = CATALOG_ENTRY.matcher(html.replace("\\\"", "\""));
        while (cat.find()) {
            catalog.putIfAbsent(cat.group(1), new String[]{cat.group(2), cat.group(4), cat.group(5)});
        }

        // 指标记录锚点扫描（转义形态）
        Map<String, ModelDefinition> dedup = new LinkedHashMap<>();
        Matcher am = ANCHOR.matcher(html);
        List<int[]> anchors = new ArrayList<>();
        while (am.find()) { anchors.add(new int[]{am.start(), am.end()}); }
        for (int k = 0; k < anchors.size(); k++) {
            int from = anchors.get(k)[0];
            int to = k + 1 < anchors.size() ? anchors.get(k + 1)[0] : Math.min(html.length(), from + 6000);
            String win = html.substring(from, to);

            String slug = group1(win, "slug\\\\\\\":\\\\\\\"([^\\\\]+)");
            if (slug == null || slug.isEmpty() || dedup.containsKey(slug)) { continue; }

            BigDecimal intelligence = num(win, "intelligenceIndex\\\\\\\":");
            BigDecimal in = num(win, "price1mInputTokens\\\\\\\":");
            BigDecimal out = num(win, "price1mOutputTokens\\\\\\\":");
            BigDecimal speed = num(win, "medianOutputTokensPerSecond\\\\\\\":");
            BigDecimal ttft = num(win, "medianTimeToFirstTokenSeconds\\\\\\\":");
            if (in == null && out == null && intelligence == null) { continue; }

            String[] catInfo = catalog.get(slug);
            String displayName = catInfo != null ? catInfo[0] : slug;
            String creatorName = catInfo != null ? catInfo[1] : slug;
            String logoPath = catInfo != null ? catInfo[2] : null;
            String iconUrl = logoPath == null ? null
                    : (logoPath.startsWith("http") ? logoPath : LOGO_BASE + logoPath);

            dedup.put(slug, ModelDefinition.builder()
                    .id(slug)
                    .name(displayName)
                    .provider(creatorName)
                    .capabilities(List.of("chat"))
                    .description("Artificial Analysis")
                    .inputUnitPrice(in)
                    .outputUnitPrice(out)
                    .currency("USD")
                    .intelligenceIndex(intelligence)
                    .outputSpeedTokensPerSecond(speed)
                    .latencyFirstTokenSeconds(ttft)
                    .iconUrl(iconUrl)
                    .build());
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * 提取窗口内的数值字段。
     *
     * @param window 窗口文本
     * @param key 字段键的转义正则片段
     * @return 数值，缺失或为 null 字面量时返回 null
     */
    private BigDecimal num(String window, String key) {
        Matcher m = Pattern.compile(key + "(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)").matcher(window);
        if (!m.find()) { return null; }
        try {
            return new BigDecimal(m.group(1)).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 提取窗口内的第一个捕获组。
     *
     * @param s 窗口文本
     * @param regex 正则
     * @return 第一个捕获组，未匹配返回 null
     */
    private String group1(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
