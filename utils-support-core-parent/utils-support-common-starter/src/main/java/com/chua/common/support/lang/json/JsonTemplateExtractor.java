package com.chua.common.support.lang.json;

import com.chua.common.support.lang.template.TemplateExtractResult;
import com.chua.common.support.lang.template.TemplateVar;
import com.chua.common.support.lang.template.TemplateExtractor;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullUnmarked;

/**
 * JSON 模板提取器：用一份“模板 JSON”从另一份输入 JSON 中抽取变量。
 *
 * <p>模板中值为 {@code {变量名}} 的占位符标记了待抽取的槽位。提取器依据占位符在模板中的
 * <b>键路径</b>到输入 JSON 中定位对应的值，因此与字段<b>顺序无关</b>；并采用 JSON5 宽松解析
 * 容忍注释、单引号、空白等格式差异。</p>
 *
 * <p>模板占位符支持两种形态：</p>
 * <ul>
 *     <li>整值：{@code "xx": "{name}"} —— 提取整个值（保留原始 JSON 类型：字符串/数字/布尔/对象/数组）；</li>
 *     <li>内嵌：{@code "greeting": "hello {name}!"} —— 正则截取变量部分，其余字面量仅作定位锚点（部分提取）。</li>
 * </ul>
 *
 * <p>本类是 {@link TemplateExtractor} 契约的 JSON 实现，通过 SPI 机制发现与加载
 * （标记 {@code @SpiDefault}），详见 {@link ServiceProvider}。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * String template = "{\"xx\":\"{name}\",\"yy\":\"{age}\"}";
 * String input    = "{\"yy\":25,\"xx\":\"Alice\"}";   // 顺序故意打乱
 *
 * // 1. 通过 SPI 入口（推荐，解耦具体实现）
 * Map<String, Object> r1 = JsonTemplateExtractor.getInstance().extract(template, input);
 *
 * // 2. 流式构建器做更细粒度控制（数组策略、类型容忍等）
 * Map<String, Object> r2 = JsonTemplateExtractor.create()
 *         .template(template)
 *         .extract(input)
 *         .toMap();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see TemplateExtractor
 * @see TemplateExtractResult
 * @see TemplateVar
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@Spi("json")
@SpiDefault
public class JsonTemplateExtractor implements TemplateExtractor {

    /**
     * 默认占位符起始符号，对应模板中的 “{”。
     */
    private static final String DEFAULT_PREFIX = "{";

    /**
     * 默认占位符结束符号，对应模板中的 “}”。
     */
    private static final String DEFAULT_SUFFIX = "}";

    /**
     * 变量名规则：仅由字母、数字、下划线组成，至少一个字符（允许以数字或下划线开头）。
     */
    private static final String VAR_NAME = "[A-Za-z0-9_]+";

    /**
     * 数组匹配策略。
     */
    public enum ArrayMatchStrategy {
        /**
         * 按索引逐元素匹配：模板数组第 i 个元素仅与输入数组第 i 个元素对应（默认）。
         */
        ELEMENT_WISE,
        /**
         * 单元素模板匹配全部：当模板数组仅含一个元素时，将其作为模式依次套用到输入数组的每一个元素。
         */
        SINGLE_MATCHES_ALL
    }

    /**
     * 占位符前缀符号。
     */
    private final String prefix;

    /**
     * 占位符后缀符号。
     */
    private final String suffix;

    /**
     * 是否采用宽松（JSON5）解析模式，默认开启以容忍格式差异。
     */
    private final boolean lenient;

    /**
     * 数组匹配策略，默认为按索引逐元素匹配。
     */
    private final ArrayMatchStrategy arrayStrategy;

    /**
     * 是否容忍类型差异：开启后，内嵌占位符对应的输入即便为非文本类型（数字/布尔/null），
     * 也会尝试以其文本形态（{@code asText()}）进行匹配或兜底取值，而非直接判为缺失。
     */
    private final boolean tolerantType;

    /**
     * 解析后的模板树，供多次提取复用。
     */
    private JsonNode templateRoot;

    // ==================== 构造与构建 ====================

    /**
     * 默认构造，使用默认占位符 {@code {}} 与宽松解析。
     * 保留公开构造以便 SPI 实例化与直接 new。
     */
    public JsonTemplateExtractor() {
        this(DEFAULT_PREFIX, DEFAULT_SUFFIX, true, ArrayMatchStrategy.ELEMENT_WISE, false);
    }

    private JsonTemplateExtractor(String prefix, String suffix, boolean lenient,
                                  ArrayMatchStrategy arrayStrategy, boolean tolerantType) {
        this.prefix = prefix;
        this.suffix = suffix;
        this.lenient = lenient;
        this.arrayStrategy = arrayStrategy;
        this.tolerantType = tolerantType;
    }

    /**
     * 创建一个使用默认占位符 {@code {}} 与宽松解析的模板提取器。
     *
     * @return JsonTemplateExtractor 实例
     */
    public static JsonTemplateExtractor create() {
        return new JsonTemplateExtractor();
    }

    /**
     * 获取默认的 JSON 模板提取器 SPI 实现实例。
     *
     * <p>通过 {@link ServiceProvider} SPI 机制自动发现并加载默认实现（标记 {@code @SpiDefault} 的实现类）。</p>
     *
     * @return JsonTemplateExtractor 实例
     */
    public static JsonTemplateExtractor getInstance() {
        ServiceProvider<JsonTemplateExtractor> provider = ServiceProvider.of(JsonTemplateExtractor.class);
        JsonTemplateExtractor instance = provider.getDefault();
        if (instance == null) {
            instance = provider.getExtension("json");
        }
        if (instance == null) {
            throw new IllegalStateException("未找到 JsonTemplateExtractor SPI 实现，请确认 common-starter 依赖已引入");
        }
        return instance;
    }

    /**
     * 设置占位符前缀符号，返回新实例便于链式调用。
     *
     * @param prefix 占位符前缀，非 null
     * @return 新的 JsonTemplateExtractor 实例
     */
    public JsonTemplateExtractor prefix(String prefix) {
        return new JsonTemplateExtractor(prefix, this.suffix, this.lenient, this.arrayStrategy, this.tolerantType);
    }

    /**
     * 设置占位符后缀符号，返回新实例便于链式调用。
     *
     * @param suffix 占位符后缀，非 null
     * @return 新的 JsonTemplateExtractor 实例
     */
    public JsonTemplateExtractor suffix(String suffix) {
        return new JsonTemplateExtractor(this.prefix, suffix, this.lenient, this.arrayStrategy, this.tolerantType);
    }

    /**
     * 设置是否采用宽松（JSON5）解析。关闭后将要求输入为严格标准 JSON。
     *
     * @param lenient 是否宽松解析
     * @return 新的 JsonTemplateExtractor 实例
     */
    public JsonTemplateExtractor lenient(boolean lenient) {
        return new JsonTemplateExtractor(this.prefix, this.suffix, lenient, this.arrayStrategy, this.tolerantType);
    }

    /**
     * 设置数组匹配策略，返回新实例便于链式调用。
     *
     * <p>当选择 {@link ArrayMatchStrategy#SINGLE_MATCHES_ALL} 时，若模板数组仅含一个元素，
     * 会将其作为模式依次套用到输入数组的每一个元素，从而一次性抽取出全部元素中的同名变量。</p>
     *
     * @param arrayStrategy 数组匹配策略，非 null
     * @return 新的 JsonTemplateExtractor 实例
     */
    public JsonTemplateExtractor arrayStrategy(ArrayMatchStrategy arrayStrategy) {
        return new JsonTemplateExtractor(this.prefix, this.suffix, this.lenient, arrayStrategy, this.tolerantType);
    }

    /**
     * 设置是否容忍类型差异，返回新实例便于链式调用。
     *
     * <p>开启后，内嵌占位符对应的输入即便为非文本类型（数字/布尔/null），
     * 也会尝试以其文本形态（{@code asText()}）进行匹配；若仍无法按锚点命中，则兜底取整体文本作为变量值，
     * 而非直接判为缺失。</p>
     *
     * @param tolerantType 是否容忍类型差异
     * @return 新的 JsonTemplateExtractor 实例
     */
    public JsonTemplateExtractor tolerantType(boolean tolerantType) {
        return new JsonTemplateExtractor(this.prefix, this.suffix, this.lenient, this.arrayStrategy, tolerantType);
    }

    /**
     * 载入模板 JSON 文本并预解析为模板树。
     *
     * @param templateJson 模板 JSON 字符串，其中值可包含 {@code {变量名}} 占位符
     * @return 当前 JsonTemplateExtractor 实例
     */
    public JsonTemplateExtractor template(String templateJson) {
        this.templateRoot = readTree(templateJson, "模板");
        return this;
    }

    // ==================== 提取入口 ====================

    /**
     * 将输入 JSON 文本按模板抽取为结果对象。
     *
     * @param inputJson 输入 JSON 文本，字段顺序/格式可与模板不同
     * @return 提取结果，含已提取变量与缺失变量列表
     */
    public TemplateExtractResult extract(String inputJson) {
        if (templateRoot == null) {
            throw new IllegalStateException("尚未调用 template(...) 设置模板");
        }
        JsonNode inputRoot = readTree(inputJson, "输入");

        Map<String, Object> extracted = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<TemplateVar> vars = new ArrayList<>();

        // 遍历模板树，收集所有占位符规则并按路径在输入中定位
        List<Object> rootPath = new ArrayList<>();
        collectAndExtract(templateRoot, inputRoot, rootPath, extracted, missing, vars);

        if (missing.isEmpty()) {
            return TemplateExtractResult.success(extracted, vars);
        }
        return TemplateExtractResult.partial(extracted, missing, vars);
    }

    /**
     * 将输入 JSON 文本按模板抽取为有序的变量列表。
     *
     * <p>每个 {@link TemplateVar} 携带变量名、值及在输入 JSON 中的路径，
     * 顺序与模板中占位符的出现顺序一致，适合需要顺序或溯源的消费场景。</p>
     *
     * @param inputJson 输入 JSON 文本，字段顺序/格式可与模板不同
     * @return 已成功提取的变量有序列表（缺失变量不会出现在列表中）
     */
    public List<TemplateVar> extractVars(String inputJson) {
        return extract(inputJson).vars();
    }

    // ==================== TemplateExtractor 接口实现 ====================

    /**
     * {@inheritDoc}
     *
     * <p>等价于 {@code create().template(templateJson).extract(inputJson).toMap()}。</p>
     */
    @Override
    public Map<String, Object> extract(String templateJson, String inputJson) {
        return template(templateJson).extract(inputJson).toMap();
    }

    /**
     * {@inheritDoc}
     *
     * <p>等价于 {@code create().template(templateJson).extract(inputJson)}。</p>
     */
    @Override
    public TemplateExtractResult extractResult(String templateJson, String inputJson) {
        return template(templateJson).extract(inputJson);
    }

    /**
     * {@inheritDoc}
     *
     * <p>等价于 {@code create().template(templateJson).extractVars(inputJson)}。</p>
     */
    @Override
    public List<TemplateVar> extractVars(String templateJson, String inputJson) {
        return template(templateJson).extract(inputJson).vars();
    }

    /**
     * {@inheritDoc}
     *
     * <p>等价于 {@code extractResult(templateJson, inputJson).isSuccess()}。</p>
     */
    @Override
    public boolean matches(String templateJson, String inputJson) {
        return extractResult(templateJson, inputJson).isSuccess();
    }

    /**
     * 将输入 JSON 文本按模板抽取，并直接转换为目标 Java 类型。
     *
     * @param templateJson 模板 JSON 字符串
     * @param inputJson    输入 JSON 文本
     * @param type         目标类型
     * @param <T>          泛型类型
     * @return 转换后的目标对象
     */
    public <T> T extractAs(String templateJson, String inputJson, Class<T> type) {
        return template(templateJson).extractAs(inputJson, type);
    }

    /**
     * 将输入 JSON 文本按模板抽取，并直接转换为目标 Java 类型。
     *
     * @param inputJson 输入 JSON 文本
     * @param type      目标类型
     * @param <T>       泛型类型
     * @return 转换后的目标对象
     */
    public <T> T extractAs(String inputJson, Class<T> type) {
        try {
            return mapper().convertValue(extract(inputJson).toMap(), type);
        } catch (Exception e) {
            throw new IllegalArgumentException("提取结果转换为 " + type.getName() + " 失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 将文本解析为 Jackson 树节点，宽松模式下借助 JSON5 解析器容忍格式差异。
     *
     * @param text 待解析文本
     * @param role 文本角色描述，用于异常信息（模板/输入）
     * @return 解析后的 JsonNode
     */
    private JsonNode readTree(String text, String role) {
        try {
            return mapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(role + " JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用于解析的 ObjectMapper：宽松模式复用 JSON5 解析器，严格模式使用默认解析器。
     *
     * @return ObjectMapper 实例
     */
    private ObjectMapper mapper() {
        if (lenient) {
            return Json.getMapper();
        }
        return new ObjectMapper();
    }

    /**
     * 递归遍历模板树，遇到文本占位符时按其键路径到输入树中定位并提取值。
     *
     * @param tpl       当前模板节点
     * @param in        对应的输入节点（可能为 null 表示路径未匹配）
     * @param path      从根到当前节点的键路径
     * @param extracted 已提取变量收集容器
     * @param missing   缺失变量收集容器
     */
    private void collectAndExtract(JsonNode tpl, JsonNode in, List<Object> path,
                                   Map<String, Object> extracted, List<String> missing,
                                   List<TemplateVar> vars) {
        if (tpl == null) {
            return;
        }

        if (tpl.isObject()) {
            // 结构不对齐（输入非对象）时，子树内的占位符一律视为缺失
            if (in != null && !in.isObject()) {
                in = null;
            }
            Iterator<String> fields = tpl.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                List<Object> childPath = new ArrayList<>(path);
                childPath.add(field);
                JsonNode childIn = (in == null) ? null : in.get(field);
                collectAndExtract(tpl.get(field), childIn, childPath, extracted, missing, vars);
            }
            return;
        }

        if (tpl.isArray()) {
            if (in != null && !in.isArray()) {
                in = null;
            }
            // 单元素模板匹配全部：模板数组仅一个元素时，将其作为模式套用到输入数组每个元素
            if (arrayStrategy == ArrayMatchStrategy.SINGLE_MATCHES_ALL
                    && tpl.size() == 1 && in != null) {
                JsonNode tplElem = tpl.get(0);
                for (int i = 0; i < in.size(); i++) {
                    List<Object> childPath = new ArrayList<>(path);
                    childPath.add(i);
                    collectAndExtract(tplElem, in.get(i), childPath, extracted, missing, vars);
                }
                return;
            }
            for (int i = 0; i < tpl.size(); i++) {
                List<Object> childPath = new ArrayList<>(path);
                childPath.add(i);
                JsonNode childIn = (in == null || i >= in.size()) ? null : in.get(i);
                collectAndExtract(tpl.get(i), childIn, childPath, extracted, missing, vars);
            }
            return;
        }

        if (tpl.isTextual()) {
            handleText(tpl.asText(), in, path, extracted, missing, vars);
        }
        // 数字/布尔/null 等不含占位符的模板节点直接忽略
    }

    /**
     * 处理单个文本模板节点：识别占位符并尝试从输入对应节点中提取值。
     *
     * @param text      模板文本值
     * @param in        输入中对应位置的节点（可能为 null）
     * @param path      当前模板节点在模板树中的键路径（同时也是输入中的定位路径）
     * @param extracted 已提取变量收集容器（变量名到值）
     * @param missing   缺失变量收集容器
     * @param vars      已成功提取的变量有序列表（携带路径信息）
     */
    private void handleText(String text, JsonNode in, List<Object> path,
                            Map<String, Object> extracted, List<String> missing,
                            List<TemplateVar> vars) {
        List<String> varNames = findPlaceholders(text);
        if (varNames.isEmpty()) {
            // 字面量文本，不参与提取
            return;
        }

        if (in == null) {
            missing.addAll(varNames);
            return;
        }

        String pathText = pathToString(path);

        if (in.isTextual()) {
            // 统一用正则表达式从文本中截取（整值占位符也会匹配整串）
            List<String> ordered = new ArrayList<>();
            Pattern pattern = buildPattern(text, ordered);
            Matcher matcher = pattern.matcher(in.asText());
            if (matcher.matches()) {
                for (int i = 0; i < ordered.size(); i++) {
                    String value = matcher.group(i + 1);
                    String name = ordered.get(i);
                    String finalValue = value == null ? "" : value;
                    extracted.put(name, finalValue);
                    vars.add(new TemplateVar(name, finalValue, pathText));
                }
            } else {
                missing.addAll(varNames);
            }
            return;
        }

        // 输入为非文本（数字/布尔/对象/数组/null）
        if (isWholePlaceholder(text)) {
            for (String name : varNames) {
                Object value = convertValue(in);
                extracted.put(name, value);
                vars.add(new TemplateVar(name, value, pathText));
            }
            return;
        }

        // 内嵌占位符需要文本输入才能截取；开启类型容忍时尝试将非文本输入转为文本处理
        if (tolerantType) {
            String asText = in.asText();
            List<String> ordered = new ArrayList<>();
            Pattern pattern = buildPattern(text, ordered);
            Matcher matcher = pattern.matcher(asText);
            if (matcher.matches() && ordered.size() == varNames.size()) {
                for (int i = 0; i < ordered.size(); i++) {
                    String name = ordered.get(i);
                    String value = matcher.group(i + 1);
                    String finalValue = value == null ? "" : value;
                    extracted.put(name, finalValue);
                    vars.add(new TemplateVar(name, finalValue, pathText));
                }
            } else {
                // 锚点匹配失败时兜底：直接以输入文本形态作为各变量的值
                for (String name : varNames) {
                    extracted.put(name, asText);
                    vars.add(new TemplateVar(name, asText, pathText));
                }
            }
            return;
        }

        missing.addAll(varNames);
    }

    /**
     * 将键路径列表转换为可读的字符串表示，如 {@code user.address[0].city}。
     *
     * @param path 从根到节点的键路径（对象键为字符串，数组下标为整数）
     * @return 路径字符串，根路径返回空串
     */
    private String pathToString(List<Object> path) {
        StringBuilder sb = new StringBuilder();
        for (Object segment : path) {
            if (segment instanceof Integer) {
                sb.append("[").append(segment).append("]");
            } else {
                if (sb.length() > 0) {
                    sb.append(".");
                }
                sb.append(segment);
            }
        }
        return sb.toString();
    }

    /**
     * 将 Jackson 节点转换为保留原始类型的 Java 对象。
     *
     * @param node 待转换节点
     * @return 对应的 Java 对象（字符串/数字/布尔/Map/List/null）
     */
    private Object convertValue(JsonNode node) {
        try {
            return mapper().convertValue(node, Object.class);
        } catch (Exception e) {
            return node.asText();
        }
    }

    /**
     * 在模板文本中查找所有占位符的变量名（按出现顺序，去重）。
     *
     * @param text 模板文本
     * @return 变量名列表，可能为空
     */
    private List<String> findPlaceholders(String text) {
        List<String> vars = new ArrayList<>();
        Matcher matcher = placeholderFinder().matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!vars.contains(name)) {
                vars.add(name);
            }
        }
        return vars;
    }

    /**
     * 判断模板文本是否为“整值占位符”（即整个文本恰好是一个 {@code {变量名}}）。
     *
     * @param text 模板文本
     * @return 是整值占位符返回 true，否则返回 false
     */
    private boolean isWholePlaceholder(String text) {
        String trimmed = text.trim();
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(prefix) + "(" + VAR_NAME + ")" + Pattern.quote(suffix) + "$");
        return pattern.matcher(trimmed).matches();
    }

    /**
     * 将模板文本编译为正则表达式，字面量部分做转义，占位符替换为捕获组 {@code (.*?)}。
     * 捕获组顺序记录在 ordered 列表中，与占位符变量名一一对应。
     *
     * @param text    模板文本
     * @param ordered 输出参数，接收捕获组对应的变量名（按出现顺序）
     * @return 编译后的正则 Pattern（开启 DOTALL 以支持多行文本）
     */
    private Pattern buildPattern(String text, List<String> ordered) {
        StringBuilder regex = new StringBuilder("^");
        Matcher matcher = placeholderFinder().matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                regex.append(Pattern.quote(text.substring(last, matcher.start())));
            }
            regex.append("(.*?)");
            ordered.add(matcher.group(1));
            last = matcher.end();
        }
        if (last < text.length()) {
            regex.append(Pattern.quote(text.substring(last)));
        }
        regex.append("$");
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * 构造匹配占位符的正则（前缀/后缀做字面量转义）。
     *
     * @return 占位符查找正则
     */
    private Pattern placeholderFinder() {
        return Pattern.compile(Pattern.quote(prefix) + "(" + VAR_NAME + ")" + Pattern.quote(suffix));
    }
}
