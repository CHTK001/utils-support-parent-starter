// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.chat.indicator;

import com.chua.common.support.ai.chat.ChatClientIndicator;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.utils.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIHOT（aihot.news）大模型排行榜的 {@link ChatClientIndicator} 实现。
 *
 * <p>数据源是 AIHOT 公开的 Markdown 榜单 {@code https://aihot.news/leaderboard.md}
 * （无需 Key），每行一个模型：</p>
 *
 * <pre>{@code
 * | 名次 | 模型 | 厂商 | AIHOT 评分 | 上线日期 | 官方 API 价格（每百万 Token，输入 / 输出） |
 * | --- | --- | --- | --- | --- | --- |
 * | 1 | [Claude Fable 5.2](https://aihot.news/leaderboard/claude-fable-5-2) | Anthropic | 79.4 | 2026-11-09 | ¥53.42 / ¥267.11（$8 / $40） |
 * }</pre>
 *
 * <p>字段映射（→ 标准 snake_case 指标键）：</p>
 * <ul>
 *   <li>AIHOT 评分 → {@code intelligence_index}（AIHOT 定义：50 分为参照平均水平，
 *       每 15 分约一个标准差，不是正确率）；</li>
 *   <li>上线日期 → {@code release_date}；</li>
 *   <li>价格列中的美元部分（{@code （$8 / $40）}）→ {@code price_input_per_musd} /
 *       {@code price_output_per_musd}（USD / 百万 Token，与其它指标源同口径）；
 *       仅有人民币报价或「厂商没有提供付费 API」时不写价格键（缺键不落库，
 *       不把缺失当 0）；免费档 {@code （$0 / $0）} 显式写 0；</li>
 *   <li>另附 {@code aihot_rank}（名次）、{@code model_provider_name}（厂商）、
 *       {@code aihot_url}（榜单页链接）等 AIHOT 特有键。</li>
 * </ul>
 *
 * <p>抓取结果带 30 分钟 TTL；抓取或解析失败时沿用上一次快照（与
 * {@link ModelsDevChatClientIndicator} 同一容错口径）。</p>
 *
 * @author CH
 * @since 4.0.0.70
 */
public final class AiHotChatClientIndicator implements ChatClientIndicator {

    /**
     * AIHOT 排行榜 Markdown 端点（无需 Key）
     */
    public static final String DEFAULT_URL = "https://aihot.news/leaderboard.md";

    /**
     * 来源标识
     */
    public static final String SOURCE = "aihot";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    /**
     * 抓取缓存有效期：30 分钟
     */
    private static final long CACHE_TTL_MILLIS = 30L * 60L * 1000L;

    /**
     * 价格列中的美元对：{@code $8 / $40}（输入 / 输出，USD / 百万 Token）
     */
    private static final Pattern USD_PAIR =
            Pattern.compile("\\$\\s*([0-9]+(?:\\.[0-9]+)?)\\s*/\\s*\\$\\s*([0-9]+(?:\\.[0-9]+)?)");

    /**
     * Markdown 链接：{@code [文本](地址)}
     */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    private final ChatClientSetting setting;
    private final String sourceUrl;
    private volatile Snapshot snapshot;

    public AiHotChatClientIndicator(ChatClientSetting setting) {
        this.setting = setting;
        String url = setting != null ? setting.getMetricsUrl() : null;
        this.sourceUrl = StringUtils.isNotBlank(url) ? url : DEFAULT_URL;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String sourceUrl() {
        return sourceUrl;
    }

    @Override
    public Long capturedAt() {
        Snapshot current = snapshot;
        return current == null ? null : current.capturedAt;
    }

    @Override
    public Map<String, Map<String, Object>> modelMetrics() {
        Snapshot current = ensureSnapshot();
        return current == null ? Map.of() : current.modelMetrics;
    }

    @Override
    public BigDecimal inputUnitPrice(String modelId) {
        return readPrice(ensureSnapshot(), modelId, "price_input_per_musd");
    }

    @Override
    public BigDecimal outputUnitPrice(String modelId) {
        return readPrice(ensureSnapshot(), modelId, "price_output_per_musd");
    }

    /**
     * ensure Snapshot
     * @return 返回值
     */
    private Snapshot ensureSnapshot() {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && now - current.capturedAt < CACHE_TTL_MILLIS) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            now = System.currentTimeMillis();
            if (current != null && now - current.capturedAt < CACHE_TTL_MILLIS) {
                return current;
            }
            Snapshot fetched = fetch();
            if (fetched != null) {
                snapshot = fetched;
                return fetched;
            }
            return current;
        }
    }

    /**
     * 获取
     * @return 返回值
     */
    private Snapshot fetch() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Accept", "text/markdown, text/plain, */*")
                    .GET();
            if (setting != null && setting.getExtraHeaders() != null) {
                setting.getExtraHeaders().forEach(builder::header);
            }
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return parse(response.body());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 解析 Markdown 榜单：只取数据行（以 {@code |} 开头），跳过表头与分隔行。
     *
     * @param body 响应体
     * @return 快照；解析不出任何模型时返回 null（沿用旧快照）
     */
    private Snapshot parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Map<String, Map<String, Object>> metricsByModel = new LinkedHashMap<>(128);
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.startsWith("|")) {
                continue;
            }
            String[] cells = splitCells(trimmed);
            if (cells.length < 6) {
                continue;
            }
            // 只收「名次」列为纯数字的数据行：天然跳过表头行与 --- 分隔行；
            // 文末「有成绩、暂不排名」的四列表已被上面的 length<6 挡掉
            if (!cells[0].matches("[0-9]+")) {
                continue;
            }
            Map<String, Object> metrics = parseRow(cells);
            if (!metrics.isEmpty()) {
                String modelName = String.valueOf(metrics.get("model_name"));
                metricsByModel.putIfAbsent(modelName, metrics);
            }
        }
        if (metricsByModel.isEmpty()) {
            return null;
        }
        Snapshot result = new Snapshot();
        result.capturedAt = System.currentTimeMillis();
        result.modelMetrics = metricsByModel;
        return result;
    }

    /**
     * 单行 → 指标 Map（列序：名次 | 模型 | 厂商 | AIHOT 评分 | 上线日期 | 价格）。
     *
     * @param cells 已去空白且去掉首尾竖线的单元格
     * @return 指标 Map；模型名为空时返回空 Map
     */
    private Map<String, Object> parseRow(String[] cells) {
        String modelName = cells[1];
        String modelUrl = null;
        Matcher link = MD_LINK.matcher(cells[1]);
        if (link.find()) {
            modelName = link.group(1).trim();
            modelUrl = link.group(2).trim();
        }
        Map<String, Object> metrics = new LinkedHashMap<>(16);
        if (modelName.isEmpty()) {
            return metrics;
        }
        Integer rank = parseIntSafely(cells[0]);
        if (rank != null) {
            metrics.put("aihot_rank", rank);
        }
        metrics.put("model_name", modelName);
        if (StringUtils.isNotBlank(cells[2]) && !"-".equals(cells[2])) {
            metrics.put("model_provider_name", cells[2]);
        }
        BigDecimal score = parseDecimalSafely(cells[3]);
        if (score != null) {
            metrics.put("intelligence_index", score);
        }
        if (StringUtils.isNotBlank(cells[4]) && !"-".equals(cells[4])) {
            metrics.put("release_date", cells[4]);
        }
        if (modelUrl != null) {
            metrics.put("aihot_url", modelUrl);
        }
        String priceText = cells[5];
        Matcher usd = USD_PAIR.matcher(priceText);
        if (usd.find()) {
            metrics.put("price_input_per_musd", new BigDecimal(usd.group(1)));
            metrics.put("price_output_per_musd", new BigDecimal(usd.group(2)));
        }
        return metrics;
    }

    /**
     * 拆分表格行：去掉首尾竖线后按 {@code |} 切分并去空白。
     *
     * @param line 表格行（已 trim）
     * @return 单元格数组
     */
    private static String[] splitCells(String line) {
        String body = line.startsWith("|") ? line.substring(1) : line;
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * 解析整数（名次），失败返回 null。
     *
     * @param text 文本
     * @return 整数或 null
     */
    private static Integer parseIntSafely(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 解析小数（评分），失败返回 null。
     *
     * @param text 文本
     * @return BigDecimal 或 null
     */
    private static BigDecimal parseDecimalSafely(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * read Price
     * @param key 字符串
     * @param modelId 字符串
     * @param snapshot 参数
     * @return 返回值
     */
    private static BigDecimal readPrice(Snapshot snapshot, String modelId, String key) {
        if (snapshot == null || modelId == null) {
            return null;
        }
        Map<String, Object> metrics = snapshot.modelMetrics.get(modelId);
        if (metrics == null) {
            return null;
        }
        Object value = metrics.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    /**
     * 一次抓取的结构化快照。
     */
    private static final class Snapshot {
        private Long capturedAt;
        private Map<String, Map<String, Object>> modelMetrics = Map.of();
    }
}
