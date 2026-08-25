package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.jsoup.Jsoup;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek 系列模型定价提供者。
 *
 * <p>包含 V3、R1、V4 等模型定价。</p>
 *
 * <p>官方文档将价格以"缓存命中/未命中 × 高峰/低谷"网格排版在非表格结构中，
 * 此处按文本模式提取各模型的 CACHE MISS 输入价与输出价；
 * 解析失败时回退到 classpath 内置 JSON。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("deepseek")
public class DeepSeekModelMetricsProvider extends AbstractModelMetricsProvider {

    /** Pricing_url */
    private static final String PRICING_URL = "https://api-docs.deepseek.com/quick_start/pricing/";

    /**
     * 模型列表模式：MODEL 与 BASE URL 之间的连字符模型名序列
     */
    private static final Pattern MODELS_PATTERN =
            Pattern.compile("\\bMODEL\\s+((?:deepseek-[a-z0-9-]+\\s*)+)BASE URL", Pattern.CASE_INSENSITIVE);

    /**
     * 缓存命中的低谷价三元组
     */
    private static final Pattern HIT_PATTERN =
            Pattern.compile("1M INPUT TOKENS \\(CACHE HIT\\) OFF-PEAK ((?:\\$[0-9.]+\\s*){2}\\$[0-9.]+)");

    /**
     * 缓存未命中的低谷价三元组（取作输入基准价）
     */
    private static final Pattern MISS_PATTERN =
            Pattern.compile("1M INPUT TOKENS \\(CACHE MISS\\) OFF-PEAK ((?:\\$[0-9.]+\\s*){2}\\$[0-9.]+)");

    /**
     * 输出的低谷价三元组
     */
    private static final Pattern OUT_PATTERN =
            Pattern.compile("1M OUTPUT TOKENS OFF-PEAK ((?:\\$[0-9.]+\\s*){2}\\$[0-9.]+)");

    /**
     * 单个美元数值
     */
    private static final Pattern VALUE_PATTERN = Pattern.compile("\\$([0-9]+(?:\\.[0-9]+)?)");

    /**
     * 从官方文档页提取分时定价数据。
     *
     * @return 模型定价列表，页面不可达或结构变化时回退内置 JSON
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        String html = fetchUrl(PRICING_URL);
        if (html == null || html.isEmpty()) {
            return readClasspathPricing();
        }
        List<ModelDefinition> parsed = parseDeepSeekPage(html);
        return parsed.isEmpty() ? readClasspathPricing() : parsed;
    }

    /**
     * 缓存命中价格区块起始标记
     */
    private static final String HIT_MARK = "1M INPUT TOKENS (CACHE HIT)";

    /**
     * 缓存未命中价格区块起始标记
     */
    private static final String MISS_MARK = "1M INPUT TOKENS (CACHE MISS)";

    /**
     * 输出价格区块起始标记
     */
    private static final String OUT_MARK = "1M OUTPUT TOKENS";

    /**
     * 解析 DeepSeek 文档页的模型与分时价格网格。
     *
     * <p>页面布局：每个计费区块先列全部模型的低谷价、再列高峰价，
     * 即数值序列为 [低谷·模型1..N, 高峰·模型1..N]。此处取各模型低谷价作为基准价。</p>
     *
     * @param html 页面 HTML
     * @return 模型定价列表，结构不匹配时返回空列表
     */
    private List<ModelDefinition> parseDeepSeekPage(String html) {
        String text = Jsoup.parse(html).body().text().replaceAll("\\s+", " ");
        List<String> models = extractModels(text);
        if (models.isEmpty()) {
            return new ArrayList<>();
        }
        int n = models.size();
        List<BigDecimal> missValues = sectionValues(text, MISS_MARK, OUT_MARK, n);
        List<BigDecimal> outValues = sectionValues(text, OUT_MARK, null, n);
        if (missValues == null || outValues == null) {
            return new ArrayList<>();
        }
        List<ModelDefinition> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String model = models.get(i);
            BigDecimal input = missValues.get(i);
            BigDecimal output = outValues.get(i);
            result.add(ModelDefinition.builder()
                    .id(model)
                    .name(model)
                    .provider(name())
                    .capabilities(List.of("chat"))
                    .description("低谷基准价（USD/1M tokens）；高峰输入 $" + missValues.get(n + i)
                            + "、输出 $" + outValues.get(n + i))
                    .inputUnitPrice(input)
                    .outputUnitPrice(output)
                    .currency("USD")
                    .build());
        }
        return result;
    }

    /**
     * 提取 MODEL 行的模型名序列。
     *
     * @param text 页面正文
     * @return 模型名列表
     */
    private List<String> extractModels(String text) {
        List<String> models = new ArrayList<>();
        Matcher matcher = MODELS_PATTERN.matcher(text);
        if (matcher.find()) {
            for (String token : matcher.group(1).trim().split("\\s+")) {
                if (token.matches("deepseek-[a-z0-9-]+")) {
                    models.add(token);
                }
            }
        }
        return models;
    }

    /**
     * 提取指定计费区块的数值序列。
     *
     * <p>区块内数值应为 [低谷·模型1..N, 高峰·模型1..N]，共 2N 个；
     * 返回按 [低谷1..N, 高峰1..N] 排序的完整序列。</p>
     *
     * @param text 页面正文
     * @param startMark 区块起始标记
     * @param endMark 区块结束标记（null 表示到文末）
     * @param n 模型数量
     * @return 数值序列，结构不符返回 null
     */
    private List<BigDecimal> sectionValues(String text, String startMark, String endMark, int n) {
        int start = text.indexOf(startMark);
        if (start < 0) {
            return null;
        }
        int from = start + startMark.length();
        int end = endMark == null ? Math.min(text.length(), from + 600) : text.indexOf(endMark, from);
        if (end < 0 || end <= from) {
            end = Math.min(text.length(), from + 600);
        }
        Matcher values = VALUE_PATTERN.matcher(text.substring(from, end));
        List<BigDecimal> nums = new ArrayList<>();
        while (values.find()) {
            nums.add(new BigDecimal(values.group(1)));
        }
        if (nums.size() != 2 * n) {
            return null;
        }
        return nums;
    }
}
