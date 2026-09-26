package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Vercel AI Gateway 模型能力提供者。
 *
 * <p>数据来自 Vercel AI Gateway 模型浏览页（SSR 内嵌 Next.js flight 数据，普通 HTTP 即可获取，
 * 无需登录 / API Key）：{@code https://vercel.com/ai-gateway/models}。该页不下发公开的
 * model-list JSON API，模型清单以转义后的 RSC 负载（{@code \"models\":[{...}]}）内嵌在 HTML 中，
 * 因此这里先从 HTML 里截出该数组再用 Jackson 解析。</p>
 *
 * <p><b>本源只取「模型能力」，不参与计费</b>：页面上的价格是 Vercel 网关成交价（含网关加价），
 * 与厂商官网原价口径不同，因此这里不写入任何价格字段（input/output/cache/image/webSearch
 * 一律留空）。留空后本源的记录不会进入 {@code sys_ai_pricing} 定价表
 * （{@code syncOfficialPricing} 会跳过无价记录），也不会在多源合并时填充价格缺口，
 * 只在 {@code DataSearchModelPricingProvider} 的合并索引里补齐能力与实测维度。</p>
 *
 * <p>实际产出维度：</p>
 * <ul>
 *   <li>能力：由 {@code tags} 判定 —— {@code reasoning} 深度思考、{@code tool-use} 工具调用、
 *       {@code vision} 图片输入、{@code web-search} 联网、{@code structured-output} 结构化输出</li>
 *   <li>上下文：{@code contextSize}</li>
 *   <li>实测：{@code metrics.throughput.averageTokensPerSecond} 输出速度、
 *       {@code metrics.latency.averageTimeToFirstTokenMs} 首 Token 延迟</li>
 * </ul>
 *
 * <p>页面数据里缺失值用哨兵字符串 {@code "$undefined"} 表示，解析时统一按空处理。
 * 与 Artificial Analysis（智能 / 基准分 / 官方牌价）、OpenRouter（能力 / 价格）互补。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("vercel")
public class VercelModelMetricsProvider extends AbstractModelMetricsProvider {

    /**
     * Vercel AI Gateway 模型浏览页（模型清单内嵌于 RSC 负载）
     */
    private static final String MODELS_URL = "https://vercel.com/ai-gateway/models";

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /**
     * 缺失值哨兵：页面用字符串 "$undefined" 表示该字段未下发
     */
    private static final String UNDEFINED = "$undefined";

    /**
     * RSC 负载前缀：每段 flight 数据以 self.__next_f.push([序号,"..."]) 形式内联
     */
    private static final String FLIGHT_PUSH = "self.__next_f.push(";

    /**
     * 反转义后的模型数组锚点
     */
    private static final String MODELS_ANCHOR = "\"models\":[";

    /**
     * 需要跳过的模型类型（评测器 / 内部工具，不是可计费对话模型）
     */
    private static final List<String> SKIP_TYPES = List.of("evaluation", "tool");

    /**
     * 从 Vercel AI Gateway 页面拉取全部模型指标。
     *
     * @return 模型指标列表；页面不可达或结构变化时回退 classpath 内置数据
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        String html = fetchUrl(MODELS_URL);
        if (html == null || html.isEmpty()) {
            return readClasspathPricing();
        }
        try {
            List<ModelDefinition> parsed = parseFlightModels(html);
            return parsed.isEmpty() ? readClasspathPricing() : parsed;
        } catch (Exception e) {
            log.warn("[vercel] 解析失败: {}", e.getMessage());
            return readClasspathPricing();
        }
    }

    /**
     * 从 HTML 的 RSC 负载中截出 models 数组并映射为模型指标列表。
     *
     * @param html 页面原始 HTML
     * @return 模型指标列表
     * @throws Exception 数组截取或 JSON 解析失败时抛出
     */
    private List<ModelDefinition> parseFlightModels(String html) throws Exception {
        String flight = decodeFlightPayload(html);
        int key = flight.indexOf(MODELS_ANCHOR);
        if (key < 0) {
            return new ArrayList<>();
        }
        int start = flight.indexOf('[', key);
        int end = findArrayEnd(flight, start);
        if (end < 0) {
            return new ArrayList<>();
        }
        JsonNode root = MAPPER.readTree(flight.substring(start, end + 1));
        if (!root.isArray()) {
            return new ArrayList<>();
        }
        List<ModelDefinition> result = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            ModelDefinition md = toModelDefinition(item);
            if (md != null) {
                result.add(md);
            }
        }
        return result;
    }

    /**
     * 解码 RSC flight 负载。
     *
     * <p>每段数据以 {@code self.__next_f.push([序号,"..."])} 内联，其中第二参数是标准的
     * JSON 字符串字面量（内含 \" 等转义）。这里逐段用 JSON 解析器读取该字符串字面量拿到
     * 还原后的文本再拼接；不能对整页做字符串替换式反转义——页内存在双重转义（\\\\\\"），
     * 直接替换会把字符串内容里的引号提前闭合，导致 JSON 解析中断。</p>
     *
     * @param html 页面原始 HTML
     * @return 拼接还原后的 flight 文本
     * @throws Exception 字符串字面量解析失败时抛出
     */
    private String decodeFlightPayload(String html) throws Exception {
        StringBuilder builder = new StringBuilder(html.length());
        int pos = 0;
        while (true) {
            int call = html.indexOf(FLIGHT_PUSH, pos);
            if (call < 0) {
                break;
            }
            int cursor = call + FLIGHT_PUSH.length();
            // 跳过形如 [1, 的参数前缀
            while (cursor < html.length() && (html.charAt(cursor) == '['
                    || html.charAt(cursor) == ' '
                    || html.charAt(cursor) == '\n'
                    || html.charAt(cursor) == '\r'
                    || html.charAt(cursor) == '\t'
                    || Character.isDigit(html.charAt(cursor))
                    || html.charAt(cursor) == ',')) {
                cursor++;
            }
            if (cursor >= html.length() || html.charAt(cursor) != '"') {
                pos = call + FLIGHT_PUSH.length();
                continue;
            }
            try {
                String decoded = MAPPER.readValue(html.substring(cursor), String.class);
                builder.append(decoded);
                int next = skipStringLiteral(html, cursor);
                pos = next < 0 ? call + FLIGHT_PUSH.length() : next;
            } catch (Exception e) {
                pos = call + FLIGHT_PUSH.length();
            }
        }
        return builder.toString();
    }

    /**
     * 跳过 JSON 字符串字面量（含转义），返回其结束后的下标。
     *
     * @param text  文本
     * @param start 起始引号下标
     * @return 字面量之后的下标；未找到结束引号时返回 -1
     */
    private int skipStringLiteral(String text, int start) {
        boolean escape = false;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 定位数组的结束下标（跳过字符串字面量内的括号）。
     *
     * @param text  待扫描文本
     * @param start 起始 '[' 下标
     * @return 结束 ']' 下标；未找到返回 -1
     */
    private int findArrayEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 将单个 Vercel 模型节点映射为 {@link ModelDefinition}。
     *
     * @param item 模型节点
     * @return 模型定义；类型被跳过或关键字段缺失时返回 空
     */
    private ModelDefinition toModelDefinition(JsonNode item) {
        String type = textOf(item, "type");
        if (type != null && SKIP_TYPES.contains(type)) {
            return null;
        }
        String slug = textOf(item, "slug");
        if (slug == null || slug.isEmpty() || slug.length() > 128) {
            return null;
        }
        String displayName = textOf(item, "displayName");
        String organization = textOf(item, "creatorOrganization");
        if (organization == null) {
            organization = firstOf(item, "providers");
        }

        JsonNode tags = item.path("tags");
        boolean reasoning = hasTag(tags, "reasoning");
        boolean functionCalling = hasTag(tags, "tool-use");
        boolean imageInput = hasTag(tags, "vision");
        boolean webSearch = hasTag(tags, "web-search");
        boolean jsonMode = hasTag(tags, "structured-output");

        List<String> capabilities = new ArrayList<>(6);
        capabilities.add("chat");
        if (reasoning) {
            capabilities.add("reasoning");
        }
        if (webSearch) {
            capabilities.add("web_search");
        }
        if (imageInput) {
            capabilities.add("image_input");
        }
        if (functionCalling) {
            capabilities.add("function_calling");
        }
        if (jsonMode) {
            capabilities.add("structured_output");
        }

        BigDecimal context = numberOf(item, "contextSize");

        JsonNode metrics = item.path("metrics");
        BigDecimal speed = decimalOf(metrics.path("throughput").path("averageTokensPerSecond"));
        BigDecimal ttftMs = decimalOf(metrics.path("latency").path("averageTimeToFirstTokenMs"));
        BigDecimal ttftSeconds = ttftMs == null ? null
                : ttftMs.divide(BigDecimal.valueOf(1000), 3, java.math.RoundingMode.HALF_UP);

        ModelDefinition.ModelDefinitionBuilder builder = ModelDefinition.builder()
                .id(slug)
                .name(displayName != null ? displayName : slug)
                .provider(organization != null ? organization : "vercel")
                .description("Vercel AI Gateway")
                // 只产出能力与实测维度：价格一律留空（网关成交价不参与计费）
                .contextWindowTokens(context != null && context.signum() > 0
                        ? context.longValue() : null)
                .outputSpeedTokensPerSecond(speed)
                .latencyFirstTokenSeconds(ttftSeconds)
                .reasoning(reasoning ? Boolean.TRUE : null)
                .webSearch(webSearch ? Boolean.TRUE : null)
                .imageInput(imageInput ? Boolean.TRUE : null)
                .functionCalling(functionCalling ? Boolean.TRUE : null)
                .jsonMode(jsonMode ? Boolean.TRUE : null)
                .capabilities(capabilities)
                .currency("USD");
        return builder.build();
    }

    /**
     * 读取数值字段为 BigDecimal。
     *
     * @param node 模型节点
     * @param key  字段名
     * @return 数值；缺失 / 非法时返回 空
     */
    private BigDecimal numberOf(JsonNode node, String key) {
        String text = textOf(node, key);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将 JSON 数值节点转为 BigDecimal。
     *
     * @param node 数值节点
     * @return 数值；缺失 / 哨兵 / 非数值时返回 空
     */
    private BigDecimal decimalOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isTextual()
                && UNDEFINED.equals(node.asText())) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.asText(null);
        if (text == null || text.isEmpty() || UNDEFINED.equals(text)) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取文本字段，哨兵值按空处理。
     *
     * @param node 模型节点
     * @param key  字段名
     * @return 文本；缺失 / 哨兵 / 空串时返回 空
     */
    private String textOf(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        if (text == null || text.isEmpty() || UNDEFINED.equals(text)) {
            return null;
        }
        return text;
    }

    /**
     * 读取字符串数组的首个非空元素。
     *
     * @param node 模型节点
     * @param key  字段名
     * @return 首个元素；数组缺失或为空时返回 空
     */
    private String firstOf(JsonNode node, String key) {
        JsonNode array = node.path(key);
        if (!array.isArray()) {
            return null;
        }
        for (JsonNode item : array) {
            String text = item.asText(null);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    /**
     * 判断能力标签数组是否包含指定标签。
     *
     * @param tags 标签数组节点
     * @param tag  目标标签
     * @return 包含返回 true
     */
    private boolean hasTag(JsonNode tags, String tag) {
        if (tags == null || !tags.isArray()) {
            return false;
        }
        for (JsonNode item : tags) {
            if (tag.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
