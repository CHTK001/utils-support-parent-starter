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

    /** 模型排行榜页(含活跃参数量 活跃参数) */
    private static final String MODELS_URL = "https://artificialanalysis.ai/models";

    /** 图标地址前缀（相对路径补全用） */
    private static final String LOGO_BASE = "https://artificialanalysis.ai";

    /** 记录锚点：转义形态的 \"模型\":{\"slug\":\"xxx\" */
    private static final Pattern ANCHOR =
            Pattern.compile("\\\\\"model\\\\\":\\{\\\\\"slug\\\\\":\\\\\\\"");

    /**
    * 目录条目：{"slug":"x","名称":"y",...,"creator":{"标识":"...","名称":"z","logo":"/img/logos/x.SVG"}}
    */
    private static final Pattern CATALOG_ENTRY = Pattern.compile(
            "\\{\"slug\":\"([^\"]+)\",\"name\":\"([^\"]*)\"([^\\[]*?)"
                    + "\"creator\":\\{\"id\":\"[^\"]*\",\"name\":\"([^\"]*)\",\"logo\":\"([^\"]*)\"");

    /** 图片输入能力推断：slug 含 镜像/clip/vit 等关键词 */
    private static final Pattern IMAGE_INPUT_PATTERN = Pattern.compile(
            "image|clip|vit|vision|multimodal", Pattern.CASE_INSENSITIVE);

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
        if (parsed.isEmpty()) {
            return readClasspathPricing();
        }
        // 活跃参数量与思考等级仅模型排行榜页提供，按 slug 补充合并
        String modelsHtml = fetchUrl(MODELS_URL);
        if (modelsHtml != null && !modelsHtml.isEmpty()) {
            Map<String, BigDecimal> params = parseActiveParams(modelsHtml);
            Map<String, String> efforts = parseReasoningEfforts(modelsHtml);
            for (ModelDefinition md : parsed) {
                BigDecimal p = params.get(md.getId());
                if (p != null) {
                    md.setActiveParams(p);
                }
                String eff = efforts.get(md.getId());
                if (eff != null) {
                    md.setReasoningEffort(eff);
                }
            }
        }
        return parsed;
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
            BigDecimal cacheHit = num(win, "cacheHitPrice\\\\\\\":");
            BigDecimal cacheWrite = num(win, "cacheWritePrice\\\\\\\":");
            BigDecimal context = num(win, "contextWindowTokens\\\\\\\":");
            BigDecimal speed = num(win, "medianOutputTokensPerSecond\\\\\\\":");
            BigDecimal ttft = num(win, "medianTimeToFirstTokenSeconds\\\\\\\":");
            BigDecimal e2e = num(win, "medianEndToEndResponseTimeSeconds\\\\\\\":");
            Boolean reasoning = null;
            String r = group1(win, "reasoningModel\\\\\\\":(true|false)");
            if (r != null) {
                reasoning = Boolean.parseBoolean(r);
            }
            Boolean deprecated = null;
            String dp = group1(win, "deprecated\\\\\\\":(true|false)");
            if (dp != null) {
                deprecated = Boolean.parseBoolean(dp);
            }
            Boolean functionCalling = null;
            String fc = group1(win, "functionCalling\\\\\\\":(true|false)");
            if (fc != null) {
                functionCalling = Boolean.parseBoolean(fc);
            }
            if (in == null && out == null && intelligence == null) { continue; }

            // 联网查询/图片识别无显式字段，按模型名规律推断（可被人工配置覆盖）
            boolean webSearch = slug.contains("search") || slug.contains("online");
            boolean imageInput = IMAGE_INPUT_PATTERN.matcher(slug).find();

            String[] catInfo = catalog.get(slug);
            String displayName = catInfo != null ? catInfo[0] : slug;
            String creatorName = catInfo != null ? catInfo[1] : slug;
            // 模型记录内的 creator.logo(每个模型记录都有,无 id 字段),catalog 作兜底
            String logoPath = group1(win, "logo\\\\\\\":\\\\\\\"([^\\\\]+)");
            if (logoPath == null && catInfo != null) {
                logoPath = catInfo[2];
            }
            // 模型记录内为纯文件名(deepseek_small.svg),catalog 内为 /img/logos/x.svg,统一补全
            String iconUrl = logoPath == null ? null
                    : (logoPath.startsWith("http") ? logoPath
                    : (logoPath.startsWith("/") ? LOGO_BASE + logoPath : LOGO_BASE + "/img/logos/" + logoPath));

 // 能力标签：基础 对话 + 推断出的多维能力
            List<String> capabilities = new ArrayList<>(4);
            capabilities.add("chat");
            if (Boolean.TRUE.equals(reasoning)) { capabilities.add("reasoning"); }
            if (webSearch) { capabilities.add("web_search"); }
            if (imageInput) { capabilities.add("image_input"); }
            if (Boolean.TRUE.equals(functionCalling)) { capabilities.add("function_calling"); }

            dedup.put(slug, ModelDefinition.builder()
                    .id(slug)
                    .name(displayName)
                    .provider(creatorName)
                    .capabilities(capabilities)
                    .description("Artificial Analysis")
                    .inputUnitPrice(in)
                    .outputUnitPrice(out)
                    .cacheHitPrice(cacheHit)
                    .cacheWritePrice(cacheWrite)
                    .contextWindowTokens(context == null ? null : context.longValue())
                    .reasoning(reasoning)
                    .webSearch(webSearch)
                    .imageInput(imageInput)
                    .functionCalling(functionCalling)
                    .currency("USD")
                    .intelligenceIndex(intelligence)
                    .outputSpeedTokensPerSecond(speed)
                    .latencyFirstTokenSeconds(ttft)
                    .endToEndResponseTimeSeconds(e2e)
                    .deprecated(deprecated)
                    .iconUrl(iconUrl)
                    .build());
        }
        return new ArrayList<>(dedup.values());
    }

    /**
    * 从模型排行榜页解析活跃参数量（slug -> 十亿）。
    *
    * <p>providers 页不含参数量字段；models 页将活跃参数量放在结构化数据集
    * {@code {"label":"...","activeParams":104,"passiveParams":2696,"detailsUrl":"/models/kimi-k3"}}
    * 中（仅覆盖部分主流模型），slug 从 detailsurl 提取。</p>
    *
    * @param html 模型页原始 HTML（含转义）
    * @return slug -> 活跃参数(十亿)
    */
    private Map<String, BigDecimal> parseActiveParams(String html) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        String esc = html.replace("\\\"", "\"");
        Matcher m = Pattern.compile(
                "\\{\"label\":\"[^\"]*\",\"activeParams\":(-?[0-9]+(?:\\.[0-9]+)?),\"passiveParams\":[0-9]+,\"detailsUrl\":\"/models/([^\"]+)\"")
                .matcher(esc);
        while (m.find()) {
            result.putIfAbsent(m.group(2), new BigDecimal(m.group(1)));
        }
        return result;
    }

    /**
    * 从模型排行榜页解析思考等级（slug -> effort）。
    *
    * <p>models 页每个推理模型记录带 {@code "effort":{"slug":"max","label":"max","level":60}},
    * 提取其档位 slug（最大 / high / medium / low）；非推理模型无此字段，不收录。</p>
    *
    * @param html 模型页原始 HTML（含转义）
    * @return slug -> 思考等级
    */
    private Map<String, String> parseReasoningEfforts(String html) {
        Map<String, String> result = new LinkedHashMap<>();
        String esc = html.replace("\\\"", "\"");
        Matcher m = Pattern.compile(
                "\"slug\":\"([^\"]+)\",\"name\":\"[^\"]*\"[^}]*?\"effort\":\\{\"slug\":\"([^\"]+)\"")
                .matcher(esc);
        while (m.find()) {
            result.putIfAbsent(m.group(1), m.group(2));
        }
        return result;
    }

    /**
    * 提取窗口内的数值字段。
    *
    * @param window 窗口文本
    * @param key 字段键的转义正则片段
    * @return 数值，缺失或为 空 字面量时返回 空
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
    * @return 第一个捕获组，未匹配返回 空
    */
    private String group1(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
