package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 定价提供者抽象基类。
*
* <p>{@link #getPricing()} 只读本地文件，没有就返回空列表。本地文件需要通过 {@link #syncFromOnline()} 同步填入。</p>
* <p>{@link #syncFromOnline()} 调用子类 {@link #fetchOnlinePricing()} 获取数据并写入本地文件。</p>
*
* <p>兜底机制：若子类需要，可通过 {@link #readClasspathPricing()} 读取 classpath 内置 JSON 作为兜底。</p>
*
* @author CH
* @since 4.0.0.42
 */
public abstract class AbstractModelMetricsProvider implements ModelMetricsProvider {

    /**
    * 本地缓存键前缀
     */
    protected static final String PRICING_KEY_PREFIX = "pricing/";

    /**
    * 本地缓存键后缀
     */
    protected static final String PRICING_KEY_SUFFIX = ".json";

    /**
    * 类路径 资源根路径
     */
    private static final String CLASSPATH_ROOT = "pricing/";

    /**
    * 模型 标识 最大长度，超出视为无效行
     */
    private static final int MAX_MODEL_ID_LENGTH = 128;

    /**
    * 默认浏览器 用户-智能体
     */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36";

    /** 日志 */
    protected static final Logger log = LoggerFactory.getLogger(AbstractModelMetricsProvider.class);

    /** 配置保存orloader */
    private ConfigSaveOrLoader configSaveOrLoader;

    /** 创建 抽象模型指标提供者 实例 */
    protected AbstractModelMetricsProvider() {
    }

    /**
    * 创建 抽象模型指标提供者 实例
    * @param configSaveOrLoader 配置保存或加载
     */
    protected AbstractModelMetricsProvider(ConfigSaveOrLoader configSaveOrLoader) {
        this.configSaveOrLoader = configSaveOrLoader;
    }

    @Override
    /** 名称 */
    public String name() {
        Spi spi = this.getClass().getAnnotation(Spi.class);
        if (spi != null && spi.value().length > 0) {
            return spi.value()[0];
        }
        return this.getClass().getSimpleName().toLowerCase();
    }

    @Override
    /** 获取Pricing */
    public List<ModelDefinition> getMetrics() {
        if (configSaveOrLoader == null) {
            return Collections.emptyList();
        }
        String key = PRICING_KEY_PREFIX + name() + PRICING_KEY_SUFFIX;
        try {
            java.util.Optional<byte[]> opt = configSaveOrLoader.loadBytes(key);
            if (opt.isPresent()) {
                String json = new String(opt.get(), StandardCharsets.UTF_8);
                List<ModelDefinition> parsed = Json.fromJson(json,
                        new com.fasterxml.jackson.core.type.TypeReference<List<ModelDefinition>>() {
                        });
                if (CollectionUtils.isNotEmpty(parsed)) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.debug("[{}] 读取本地定价缓存失败: {}", name(), e.getMessage());
        }
        return Collections.emptyList();
    }

    @Override
    /** 同步从online */
    public void syncFromOnline() {
        List<ModelDefinition> pricing = fetchOnlinePricing();
        if (pricing == null || pricing.isEmpty()) {
            return;
        }
        if (configSaveOrLoader != null) {
            String key = PRICING_KEY_PREFIX + name() + PRICING_KEY_SUFFIX;
            String json = Json.toJson(pricing);
            configSaveOrLoader.saveBytes(key, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
    * 从线上 API 拉取定价数据。
    *
    * <p>默认行为：读取 classpath 内置 JSON 文件作为兜底数据。
    * 若厂商有公开定价 API，子类可覆写此方法直接调用线上接口。</p>
    *
    * @return 模型定价列表
     */
    public List<ModelDefinition> fetchOnlinePricing() {
        return readClasspathPricing();
    }

    /**
    * 从 类路径 内置 JSON 文件读取定价列表（兜底）。
    *
    * @return classpath 中的定价列表
     */
    protected List<ModelDefinition> readClasspathPricing() {
        String path = CLASSPATH_ROOT + name() + PRICING_KEY_SUFFIX;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                return Collections.emptyList();
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Json.fromJson(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ModelDefinition>>() {
                    });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
    * 从指定定价页面的 HTML 表格抓取模型定价。
    *
    * <p>按表头语义自动识别列：模型名称列（模型/产品/名称/model 等）、输入单价列（输入/input）、
    * 输出单价列（输出/输出）。多列匹配时优先选择"未命中缓存"的输入单价列。
    * 价格合并在同一单元格内的布局（如"输入：0.5元输出：2元"）按标签提取；
    * 单元格货币符号与目标币种矛盾的行（如同页混排的海外 $ 报价）自动过滤。
    * 无法识别表头的表格跳过；页面不可达或无有效数据时回退到 {@link #readClasspathPricing()}。</p>
    *
    * @param url 定价页面地址
    * @param currency 币种（如 CNY/USD）
    * @return 模型定价列表
     */
    protected List<ModelDefinition> scrapeTablePricing(String url, String currency) {
        String html = fetchUrl(url);
        Map<String, ModelDefinition> result = new LinkedHashMap<>();
        if (html != null && !html.isEmpty()) {
            collectPagePricing(html, currency, result);
        }
        if (!result.isEmpty()) {
            return new ArrayList<>(result.values());
        }
        return readClasspathPricing();
    }

    /**
    * 解析整页 HTML 中的全部定价表格并按模型 标识 去重收集。
    *
    * @param html 页面 HTML
    * @param currency 币种
    * @param result 收集结果
     */
    private void collectPagePricing(String html, String currency, Map<String, ModelDefinition> result) {
        try {
            Document doc = Jsoup.parse(html);
            for (Element table : doc.select("table")) {
                collectTablePricing(table, currency, result);
            }
        } catch (Exception e) {
            log.debug("[{}] 解析定价页面失败: {}", name(), e.getMessage());
        }
    }

    /**
    * 收集单个表格中的模型定价数据。
    *
    * @param table 表格元素
    * @param currency 币种
    * @param result 收集结果，按模型 标识 去重
     */
    private void collectTablePricing(Element table, String currency, Map<String, ModelDefinition> result) {
        Element headerRow = findHeaderRow(table);
        if (headerRow == null) {
            return;
        }
        int[] cols = detectColumns(headerCells(headerRow));
        if (cols == null) {
            return;
        }
        for (Element row : table.select("tr")) {
            if (row == headerRow) {
                continue;
            }
            ModelDefinition definition = parseTableRow(row, cols, currency);
            if (definition != null) {
                result.putIfAbsent(definition.getId(), definition);
            }
        }
    }

    /**
    * 查找表格的表头行。
    *
    * <p>优先取 {@code thead} 内首行，其次取含 {@code th} 的首行；
    * 均不存在时（部分文档站用纯 {@code td} 渲染表头）回退到首行，
    * 是否有效由 {@link #detectColumns(List)} 的语义识别兜底校验。</p>
    *
    * @param table 表格元素
    * @return 表头行元素，表格无行时返回 空
     */
    private Element findHeaderRow(Element table) {
        Elements theadRows = table.select("thead > tr");
        if (!theadRows.isEmpty()) {
            return theadRows.first();
        }
        Elements rows = table.select("tr");
        for (Element row : rows) {
            if (!row.select("th").isEmpty()) {
                return row;
            }
        }
        return rows.isEmpty() ? null : rows.first();
    }

    /**
    * 获取表头行的单元格文本（转小写）。
    *
    * @param headerRow 表头行元素
    * @return 表头文本列表
     */
    private List<String> headerCells(Element headerRow) {
        Elements cells = headerRow.select("th");
        if (cells.isEmpty()) {
            cells = headerRow.select("td");
        }
        List<String> headers = new ArrayList<>(cells.size());
        for (Element cell : cells) {
            headers.add(cell.text().trim().toLowerCase());
        }
        return headers;
    }

    /**
    * 按表头语义识别模型名称、输入、输出单价所在列。
    *
    * @param headers 表头文本列表
    * @return 列索引数组 [模型col, 输入col, 输出col]，输出col 可为 -1；
    * 无法识别模型名称列时返回 空
     */
    private int[] detectColumns(List<String> headers) {
        int modelCol = -1;
        int inputCol = -1;
        int outputCol = -1;
        int fallbackInputCol = -1;
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            boolean cached = header.contains("缓存") || header.contains("cache") || header.contains("storage");
            boolean hit = header.contains("命中") || header.contains("hit");
            if ((header.contains("输入") || header.contains("input")) && !header.contains("输出") && !header.contains("output")) {
                if (header.contains("未命中") || header.contains("miss")) {
                    inputCol = i;
                } else if (!cached && !hit && fallbackInputCol < 0) {
                    fallbackInputCol = i;
                }
            }
            if ((header.contains("输出") || header.contains("output")) && outputCol < 0) {
                outputCol = i;
            }
            if (modelCol < 0 && (header.contains("模型") || header.contains("产品") || header.contains("名称")
                    || header.contains("型号") || header.contains("系列") || header.contains("model")
                    || header.contains("series"))) {
                modelCol = i;
            }
        }
        if (inputCol < 0) {
            inputCol = fallbackInputCol;
        }
        if (modelCol < 0) {
            return null;
        }
        return new int[]{modelCol, inputCol, outputCol};
    }

    /**
    * 解析定价表格中的单行数据。
    *
    * @param row 表格行元素
    * @param cols 列索引数组 [模型col, 输入col, 输出col]
    * @param currency 币种
    * @return 模型定义，行无效时返回 空
     */
    private ModelDefinition parseTableRow(Element row, int[] cols, String currency) {
        Elements cells = row.select("td");
        int modelCol = cols[0];
        if (cells.isEmpty() || cells.size() <= modelCol) {
            return null;
        }
        String model = cells.get(modelCol).text().trim();
        if (model.isEmpty() || model.length() > MAX_MODEL_ID_LENGTH) {
            return null;
        }
        int inputCol = cols[1];
        int outputCol = cols[2];
        BigDecimal inputPrice;
        BigDecimal outputPrice;
        if (inputCol >= 0 && cells.size() > inputCol) {
            String inputText = cells.get(inputCol).text();
            String outputText = (outputCol >= 0 && cells.size() > outputCol) ? cells.get(outputCol).text() : null;
            // 币种一致性校验：单元格符号与目标币种矛盾时跳过该行（如 CNY 页面混入的 $ 报价）
            if (isContradictingCurrency(inputText, currency) || isContradictingCurrency(outputText, currency)) {
                return null;
            }
            inputPrice = parsePrice(inputText);
            outputPrice = parsePrice(outputText);
        } else {
            // 无独立输入/输出列的布局（价格合并在单元格内）：按"输入/输出"标签提取
            StringBuilder merged = new StringBuilder();
            for (int i = modelCol + 1; i < cells.size(); i++) {
                merged.append(' ').append(cells.get(i).text());
            }
            String text = merged.toString();
            inputPrice = extractLabelledPrice(text, "(?:输入|prompt)");
            outputPrice = extractLabelledPrice(text, "(?:输出|completion)");
        }
        if (inputPrice == null) {
            return null;
        }
        return ModelDefinition.builder()
                .id(model)
                .name(model)
                .provider(name())
                .capabilities(List.of("chat"))
                .inputUnitPrice(inputPrice)
                .outputUnitPrice(outputPrice)
                .currency(currency)
                .build();
    }

    /**
    * 判断单元格文本携带的货币符号是否与目标币种矛盾。
    *
    * <p>用于过滤同页混排的多币种报价（如中文页附带的 $ 海外价）。</p>
    *
    * @param text 单元格原始文本
    * @param currency 目标币种（如 CNY/USD）
    * @return true 表示符号与目标币种矛盾，应跳过该行
     */
    private boolean isContradictingCurrency(String text, String currency) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean dollar = Pattern.compile("\\$\\s*[0-9]").matcher(text).find();
        boolean yuan = text.contains("￥") || text.contains("\u00A5") || text.contains("元");
        if ("CNY".equalsIgnoreCase(currency)) {
            return dollar;
        }
        if ("USD".equalsIgnoreCase(currency)) {
            return yuan;
        }
        return false;
    }

    /**
    * 解析单元格文本中的单价数值。
    *
    * <p>兼容"0.5元"、"$3 / MTok"、"￥3.00"等带单位/符号的写法，
    * 提取首个数值；免费标记返回 0。</p>
    *
    * @param text 单元格文本
    * @return 单价数值，无法解析时返回 空；免费标记返回 0
     */
    private BigDecimal parsePrice(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace("元", "").replace("$", "").replace("￥", "")
                .replace("\u00A5", "").replace(",", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if ("free".equalsIgnoreCase(cleaned) || cleaned.contains("免费")) {
            return BigDecimal.ZERO;
        }
        Matcher matcher = Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(cleaned);
        try {
            return matcher.find() ? new BigDecimal(matcher.group()) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
    * 从合并单元格文本中按标签提取价格数值。
    *
    * @param text 合并单元格文本
    * @param label 价格标签正则（如"输入"）
    * @return 价格数值，未匹配时返回 空
     */
    private BigDecimal extractLabelledPrice(String text, String label) {
        Matcher matcher = Pattern.compile(label + "[^0-9.$]{0,8}([$￥]?)([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        return matcher.find() ? new BigDecimal(matcher.group(2)) : null;
    }

    // ======================== HTTP 工具方法 ========================

    /**
    * 获取 HTTP 页面内容。
    *
    * <p>携带浏览器 User-Agent 以规避部分站点的默认客户端拦截。</p>
    *
    * @param url 页面地址
    * @return HTML 字符串，请求失败返回 空
     */
    protected String fetchUrl(String url) {
        try {
            return HttpClientFactory.of(url)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .get().getBodyString();
        } catch (Exception e) {
            log.debug("[{}] 请求页面失败: url={}, msg={}", name(), url, e.getMessage());
            return null;
        }
    }

    /**
    * 将 JSON 字符串解析为 模型definition 列表。
    *
    * @param json JSON 数组字符串
    * @return 模型定价列表
     */
    protected List<ModelDefinition> parseJsonPricing(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<ModelDefinition> parsed = Json.fromJson(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ModelDefinition>>() {
                    });
            if (CollectionUtils.isNotEmpty(parsed)) {
                return parsed;
            }
        } catch (Exception e) {
            log.debug("[{}] 解析 JSON 定价失败: {}", name(), e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
    * 设置配置加载器。
    *
    * @param configSaveOrLoader 配置加载器
     */
    public void setConfigSaveOrLoader(ConfigSaveOrLoader configSaveOrLoader) {
        this.configSaveOrLoader = configSaveOrLoader;
    }
}
