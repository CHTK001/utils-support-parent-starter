package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Artificial Analysis 模型指标提供者。
 *
 * <p>数据源为 artificialanalysis.ai 的厂商排行榜（SSR 表格），
 * 每行包含：厂商、模型、智能指数、每百万成本（USD）、输出速度（Token/秒）、
 * 首块延迟（秒）及厂商图标。</p>
 *
 * <p>列位置由第二级表头的关键字动态识别，页面改版时具备一定自适应性；
 * 解析失败时回退到 classpath 内置 JSON（如有）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("artificialanalysis")
public class ArtificialAnalysisModelMetricsProvider extends AbstractModelMetricsProvider {

    /** Leaderboard_url */
    private static final String LEADERBOARD_URL = "https://artificialanalysis.ai/zh/leaderboards/providers";

    /**
     * 数值提取
     */
    private static final Pattern NUM = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");

    /**
     * 从排行榜抓取全量模型指标。
     *
     * @return 模型指标列表，页面不可达或无有效表格时回退内置 JSON
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        return scrapeTablePricing(LEADERBOARD_URL, "USD");
    }

    /**
     * 排行榜为两级表头（分组 + 实际列名），且列语义与通用价格解析不同，
     * 故覆盖基类解析：定位主表格，按第二级表头识别列后逐行转换。
     *
     * @param url 页面地址
     * @param currency 币种
     * @return 模型指标列表
     */
    @Override
    protected List<ModelDefinition> scrapeTablePricing(String url, String currency) {
        String html = fetchUrl(url);
        if (html == null || html.isEmpty()) {
            return readClasspathPricing();
        }
        Document doc = Jsoup.parse(html, url);
        List<ModelDefinition> result = new ArrayList<>();
        for (Element table : doc.select("table")) {
            var rows = table.select("tr");
            if (rows.size() < 3) { continue; }
            int[] cols = detectColumns(rows.get(1));
            if (cols == null) { continue; }
            for (int i = 2; i < rows.size(); i++) {
                ModelDefinition md = parseRow(rows.get(i), cols, doc.baseUri(), currency);
                if (md != null) {
                    result.add(md);
                }
            }
            if (!result.isEmpty()) { break; }
        }
        return result.isEmpty() ? readClasspathPricing() : result;
    }

    private int[] detectColumns(Element headerRow) {
        var cells = headerRow.select("th,td");
        int provider = -1, model = -1, intel = -1, price = -1, speed = -1, latency = -1;
        for (int i = 0; i < cells.size(); i++) {
            String h = cells.get(i).text();
            if (provider < 0 && (h.contains("API") || h.contains("提供商"))) { provider = i; }
            else if (model < 0 && h.contains("模型")) { model = i; }
            else if (intel < 0 && h.contains("Intelligence Index")) { intel = i; }
            else if (price < 0 && h.contains("美元")) { price = i; }
            else if (speed < 0 && h.contains("Token/秒")) { speed = i; }
            else if (latency < 0 && h.contains("首个") && h.contains("秒")) { latency = i; }
            else if (latency < 0 && h.contains("分块") && h.contains("秒")) { latency = i; }
        }
        if (model < 0 || intel < 0) { return null; }
        return new int[]{provider, model, intel, price, speed, latency};
    }

    /**
     * 解析单行为模型指标。
     *
     * @param row 表格行
     * @param cols 列索引
     * @param baseUri 页面基准地址（用于图标绝对路径）
     * @param currency 币种
     * @return 模型定义，行无效时返回 null
     */
    private ModelDefinition parseRow(Element row, int[] cols, String baseUri, String currency) {
        var cells = row.select("td");
        int last = Math.max(Math.max(cols[0], cols[1]), Math.max(Math.max(cols[2], cols[3]), Math.max(cols[4] < 0 ? 0 : cols[4], cols[5] < 0 ? 0 : cols[5])));
        if (cells.isEmpty() || cells.size() <= last) { return null; }
        Element providerCell = cells.get(cols[0]);
        String providerName = providerCell.text().trim();
        Element iconImg = providerCell.selectFirst("img[src*=logos]");
        String modelName = cells.get(cols[1]).text().trim();
        BigDecimal intelligence = firstNumber(cells.get(cols[2]).text());
        BigDecimal price = cols[3] >= 0 && cells.size() > cols[3] ? firstNumber(cells.get(cols[3]).text()) : null;
        BigDecimal speed = cols[4] >= 0 && cells.size() > cols[4] ? firstNumber(cells.get(cols[4]).text()) : null;
        BigDecimal latency = cols[5] >= 0 && cells.size() > cols[5] ? firstNumber(cells.get(cols[5]).text()) : null;
        if (modelName.isEmpty() || intelligence == null) { return null; }
        String id = (providerName.isEmpty() ? "unknown" : providerName) + "/" + modelName;
        return ModelDefinition.builder()
                .id(id)
                .name(modelName)
                .provider(providerName.isEmpty() ? name() : providerName)
                .capabilities(List.of("chat"))
                .description("Artificial Analysis 排行榜")
                .intelligenceIndex(intelligence)
                .outputSpeedTokensPerSecond(speed)
                .latencyFirstTokenSeconds(latency)
                .iconUrl(iconImg == null ? null : iconImg.absUrl("src"))
                .inputUnitPrice(price)
                .currency(currency)
                .build();
    }

    /**
     * 提取文本中的首个数值。
     *
     * @param text 单元格文本
     * @return 数值，无数字返回 null
     */
    private BigDecimal firstNumber(String text) {
        Matcher m = NUM.matcher(text);
        return m.find() ? new BigDecimal(m.group()) : null;
    }
}
