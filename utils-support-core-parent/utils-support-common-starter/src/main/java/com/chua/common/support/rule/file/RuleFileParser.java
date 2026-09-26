package com.chua.common.support.rule.file;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.rule.RuleException;
import com.chua.common.support.rule.decision.DecisionTable;
import com.chua.common.support.rule.decision.DecisionTableSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则文件解析器。
 *
 * <p>把规则文件文本解析为 {@link RuleSetSpec}。支持两种形态：</p>
 * <ul>
 *   <li><b>规则集对象</b> — 含 {@code version} / {@code globals} / {@code rules} 的 JSON 对象</li>
 *   <li><b>JSONL</b> — 每行一个规则对象，便于大文件与行级 diff</li>
 * </ul>
 *
 * <p>复用模块内既有的 {@link Json} 门面（SPI 自动装配
 * {@code JacksonJsonProvider}），本类<b>不引入任何新依赖</b>。</p>
 *
 * <h3>严格解析</h3>
 * <p>解析失败一律抛 {@link RuleException}，并在消息中带上
 * 行号与规则名——热更新场景下错误信息就是运维的唯一线索，
 * 因此不做"尽力而为"的静默跳过：一条规则写错就整体拒绝装载，
 * 避免半生效的规则集造成难以排查的行为。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RuleSetSpec spec = RuleFileParser.parseText(json, "orders.json");
 * RuleSetSpec lines = RuleFileParser.parseText(jsonl, "orders.jsonl");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleFileParser {

    /**
     * 工具类禁止实例化
     */
    private RuleFileParser() {
    }

    /**
     * 解析规则文件文本。
     *
     * <p>自动识别三种形态：</p>
     * <ul>
     *   <li><b>规则集对象</b> — 单个 JSON 对象，含 {@code version}/{@code globals}/{@code rules}</li>
     *   <li><b>规则数组</b> — 单个 JSON 数组，元素为规则对象</li>
     *   <li><b>JSONL</b> — 多行，每行一个规则对象</li>
     * </ul>
     *
     * <p>判别采用<b>结构探测</b>而非行数统计：先按单个 JSON 值解析，
     * 成功则据其类型与顶层键判定；失败（存在多个并列根对象）再走 JSONL。
     * 早期版本用「统计以 &#123; 开头的行数」判定，
     * 会被 pretty-print 后的嵌套对象误判成 JSONL，故改为此方案。</p>
     *
     * @param text   文本内容
     * @param origin 来源标识，用于诊断
     * @return 规则集定义
     * @throws RuleException 解析失败时抛出
     */
    public static RuleSetSpec parseText(String text, String origin) {
        if (text == null || text.isBlank()) {
            throw new RuleException("规则文件内容为空：" + origin);
        }
        String stripped = text.stripLeading();
        if (stripped.startsWith("[")) {
            return parseRuleArray(stripped, origin);
        }
        if (!stripped.startsWith("{")) {
            return parseJsonLines(text, origin);
        }
        if (looksLikeJsonLines(text)) {
            return parseJsonLines(text, origin);
        }
        Map<String, Object> root = asObject(parse(text), origin);
        if (root.containsKey("rules") || root.containsKey("globals") || root.containsKey("version")) {
            return parseRuleSet(root, origin);
        }
        // 单个规则对象，等价于只含一条规则的规则集
        return new RuleSetSpec(null, null, List.of(readRule(root, origin)), origin);
    }

    /**
     * 判断文本是否呈 JSONL 形态。
     *
     * <p>判据：<b>至少两行</b>非空内容，且<b>每一行</b>都能独立解析为 JSON 对象。
     * 这两点缺一不可：</p>
     * <ul>
     *   <li>只有「每行是对象」而没有行数下限，会把<b>单行</b>的规则集/单规则误判为 JSONL</li>
     *   <li>只有行数下限而没有「每行是对象」，会把 pretty-print 的规则集误判为 JSONL
     *       （其内部行如 {@code "rules": [} 并非完整对象）</li>
     * </ul>
     *
     * <p>注意：不能改用「整体能否解析为单个 JSON」来判别——
     * 底层 provider 对多个并列根对象是宽容的，只消费第一段就返回，
     * 会被误判成「单规则对象」。</p>
     *
     * @param text 文本
     * @return 是返回 true
     */
    private static boolean looksLikeJsonLines(String text) {
        String[] lines = text.split("\\R");
        int meaningful = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue;
            }
            meaningful++;
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return false;
            }
            try {
                Json.getJsonObject(trimmed);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return meaningful >= 2;
    }

    /**
     * 解析规则数组形态。
     *
     * @param text   文本内容
     * @param origin 来源标识
     * @return 规则集定义
     */
    private static RuleSetSpec parseRuleArray(String text, String origin) {
        List<Object> array;
        try {
            array = asArray(Json.getJsonArray(text), origin);
        } catch (RuleException e) {
            throw new RuleException("规则数组解析失败：" + origin, e);
        }
        return new RuleSetSpec(null, null, readRules(array, origin), origin);
    }

    /**
     * 解析规则集对象。
     *
     * @param root   已解析的根对象
     * @param origin 来源标识
     * @return 规则集定义
     */
    private static RuleSetSpec parseRuleSet(Map<String, Object> root, String origin) {
        String version = root.get("version") == null ? null : String.valueOf(root.get("version"));
        Map<String, Object> globals = asObject(root.get("globals"), origin);
        List<RuleSpec> rules = readRules(asArray(root.get("rules"), origin), origin);
        return new RuleSetSpec(version, globals, rules, origin);
    }
    /**
     * 解析 JSONL 形态（每行一个规则对象）。
     *
     * @param text   文本内容
     * @param origin 来源标识
     * @return 规则集定义
     */
    private static RuleSetSpec parseJsonLines(String text, String origin) {
        List<RuleSpec> rules = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            rules.add(readRule(asObject(parse(line), origin + "#L" + (i + 1)),
                    origin + "#L" + (i + 1)));
        }
        if (rules.isEmpty()) {
            throw new RuleException("规则文件没有任何规则：" + origin);
        }
        return new RuleSetSpec(null, null, rules, origin);
    }

    /**
     * 调用 JSON 门面解析为对象。
     *
     * <p>使用 {@code getJsonObject} 而非 {@code parse}：前者返回
     * {@code JsonObject}（本身即 {@code Map}），其嵌套值是
     * {@code Map}/{@code List}；后者返回包装节点，需要额外解包。</p>
     *
     * @param text 文本
     * @return 解析结果
     */
    private static Object parse(String text) {
        try {
            return Json.getJsonObject(text);
        } catch (RuntimeException e) {
            throw new RuleException("JSON 解析失败", e);
        }
    }

    /**
     * 将任意值视为 JSON 对象。
     *
     * <p>底层 provider 解析出的嵌套对象是 {@link Map} 而非
     * {@code JsonObject}，因此统一在此归一为 {@code Map} 处理，
     * 避免依赖门面包装类型的具体实现。</p>
     *
     * @param value  原始值
     * @param origin 来源标识
     * @return 对象视图，非对象返回 null
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String origin) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new RuleException("期望 JSON 对象，实际为 "
                + value.getClass().getSimpleName() + "：" + origin);
    }

    /**
     * 将任意值视为 JSON 数组。
     *
     * @param value  原始值
     * @param origin 来源标识
     * @return 列表视图，非数组返回 null
     */
    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value, String origin) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new RuleException("期望 JSON 数组，实际为 "
                + value.getClass().getSimpleName() + "：" + origin);
    }

    /**
     * 读取规则数组。
     *
     * @param value  规则数组
     * @param origin 来源标识
     * @return 规则列表
     */
    private static List<RuleSpec> readRules(List<Object> array, String origin) {
        if (array == null || array.isEmpty()) {
            throw new RuleException("规则文件缺少 rules 段或为空：" + origin);
        }
        List<RuleSpec> rules = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            rules.add(readRule(asObject(array.get(i), origin),
                    origin + "#rules[" + i + "]"));
        }
        return rules;
    }

    /**
     * 读取单条规则。
     *
     * @param node   规则节点
     * @param origin 来源标识
     * @return 规则定义
     */
    private static RuleSpec readRule(Map<String, Object> node, String origin) {
        Object nameValue = node.get("name");
        if (nameValue == null || String.valueOf(nameValue).isBlank()) {
            throw new RuleException("规则缺少 name：" + origin);
        }
        String name = String.valueOf(nameValue).trim();

        // when 有两种写法，降低学习成本：
        //   1) 对象节点树（可组合 all/any/not/compare/exists/expr）
        //   2) 直接一个表达式字符串，例如
        //      "when": "o.amount > 100 and o.vip is not null and o.level =~ 'GOLD.*'"
        // 字符串形式内部会展开成 all[definitions..., expr]。
        //
        // 表达式里的绑定名有两种来源，任选其一：
        //   a) 与 when 同级写 "facts": [{"type":"order","binding":"o"}]（显式，推荐）
        //   b) 绑定名本身就是已注册的类型别名（简写）：
        //      registry.register("o", OrderFact.class) 之后可直接写 o.amount
        Object whenRaw = node.get("when");
        RuleConditionSpec when;
        if (whenRaw instanceof String expression && !expression.isBlank()) {
            when = RuleConditionSpec.expression(expression.trim());
            when = attachDefinitions(when, node.get("facts"), name);
        } else {
            Map<String, Object> whenObject = asObject(whenRaw, name);
            if (whenObject == null) {
                throw new RuleException("规则[" + name + "] 缺少 when 段；"
                        + "when 可以是条件节点对象，也可以直接是表达式字符串");
            }
            when = readWhen(whenObject, name);
            when = attachDefinitions(when, node.get("facts"), name);
        }
        List<RuleActionSpec> then = readThen(node.get("then"), name);

        return new RuleSpec(
                name,
                node.get("description") == null ? null : String.valueOf(node.get("description")),
                intValue(node.get("salience"), 0),
                node.get("agendaGroup") == null ? null : String.valueOf(node.get("agendaGroup")),
                node.get("activationGroup") == null ? null : String.valueOf(node.get("activationGroup")),
                boolValue(node.get("noLoop"), true),
                boolValue(node.get("lockOnActive"), false),
                boolValue(node.get("enabled"), true),
                when,
                then);
    }

    /**
     * 读取 when 段。
     *
     * @param node when 对象
     * @param name 规则名
     * @return 条件树
     */
    private static RuleConditionSpec readWhen(Map<String, Object> node, String name) {
        RuleConditionSpec fromWhen = readWhenBody(node, name);
        return attachDefinitions(fromWhen, node.get("facts"), name);
    }

    /**
     * 把与 when 同级的 {@code facts} 声明拼到条件树前面。
     *
     * <p>当 {@code when} 写成表达式字符串时，规则里没有 {@code when.facts}
     * 可写，绑定名只能来自同级的 {@code facts}；这里负责补上，
     * 使条件树变成 {@code all[definitions..., 原有节点...]}。
     * 已有定义时不做任何改动。</p>
     *
     * @param condition 条件树
     * @param factsRaw  同级 facts 原始值，可为 null
     * @param name      规则名
     * @return 拼接后的条件树
     */
    private static RuleConditionSpec attachDefinitions(RuleConditionSpec condition,
            Object factsRaw, String name) {
        List<Object> factsArray = asArray(factsRaw, name);
        if (factsArray == null || factsArray.isEmpty()) {
            return condition;
        }
        if (condition.kind() == RuleConditionSpec.Kind.ALL) {
            boolean alreadyDefined = condition.children().stream()
                    .anyMatch(child -> child.kind() == RuleConditionSpec.Kind.DEFINITION);
            if (alreadyDefined) {
                return condition;
            }
        }
        List<RuleConditionSpec> merged = new ArrayList<>(
                factsArray.size() + 1);
        merged.addAll(readDefinitions(factsArray, name));
        merged.add(condition);
        return RuleConditionSpec.all(merged);
    }

    /**
     * 读取 facts 数组为定义节点列表。
     *
     * @param factsArray facts 数组
     * @param name       规则名
     * @return 定义节点列表
     */
    private static List<RuleConditionSpec> readDefinitions(List<Object> factsArray, String name) {
        List<RuleConditionSpec> facts = new ArrayList<>(factsArray.size());
        for (int i = 0; i < factsArray.size(); i++) {
            Map<String, Object> factNode = asObject(factsArray.get(i), name);
            Object typeValue = factNode.get("type");
            if (typeValue == null) {
                throw new RuleException("规则[" + name + "] facts[" + i + "] 缺少 type");
            }
            Map<String, Object> whereObject = asObject(factNode.get("where"), name);
            RuleConditionSpec where = whereObject == null ? null : readNode(whereObject, name);
            Object binding = factNode.get("binding");
            facts.add(RuleConditionSpec.definition(
                    String.valueOf(typeValue),
                    binding == null ? "fact" : String.valueOf(binding),
                    where));
        }
        return facts;
    }

    /**
     * 读取决策表定义（未编译）。
     *
     * <p>规则文件里的 {@code decisions} 段：</p>
     * <pre>{@code
     * "decisions": {
     *   "riskLevel": {
     *     "kind": "table",
     *     "hitPolicy": "FIRST",
     *     "default": "LOW",
     *     "rows": [
     *       { "priority": 10, "when": "o.amount > 500000", "then": "HIGH" },
     *       { "priority": 20, "when": "o.vip", "then": "MEDIUM" }
     *     ]
     *   }
     * }
     * }</pre>
     *
     * @param text   规则文本
     * @param origin 来源标识
     * @return 决策表 id 到定义
     */
    public static Map<String, DecisionTableSpec> parseDecisions(String text, String origin) {
        Map<String, Object> root = asObject(parse(text), origin);
        Map<String, Object> decisions = asObject(root.get("decisions"), origin);
        if (decisions == null || decisions.isEmpty()) {
            return Map.of();
        }
        Map<String, DecisionTableSpec> tables = new LinkedHashMap<>();
        decisions.forEach((id, raw) -> {
            Map<String, Object> node = asObject(raw, origin);
            if (node == null) {
                throw new RuleException("决策表[" + id + "] 定义不是对象：" + origin);
            }
            List<Object> rowsRaw = asArray(node.get("rows"), origin);
            if (rowsRaw == null || rowsRaw.isEmpty()) {
                throw new RuleException("决策表[" + id + "] 缺少 rows：" + origin);
            }
            List<DecisionTableSpec.Row> rows = new ArrayList<>(rowsRaw.size());
            int order = 0;
            for (Object rowRaw : rowsRaw) {
                Map<String, Object> row = asObject(rowRaw, origin);
                if (row == null) {
                    throw new RuleException("决策表[" + id + "] 的候选行不是对象：" + origin);
                }
                if (row.get("when") == null) {
                    throw new RuleException("决策表[" + id + "] 的候选行缺少 when：" + origin);
                }
                if (row.get("then") == null) {
                    throw new RuleException("决策表[" + id + "] 的候选行缺少 then：" + origin);
                }
                // 未显式给优先级时按书写顺序，避免所有行优先级相同
                int priority = row.get("priority") == null
                        ? order : intValue(row.get("priority"), order);
                rows.add(new DecisionTableSpec.Row(priority,
                        String.valueOf(row.get("when")).trim(), row.get("then")));
                order++;
            }
            DecisionTable.HitPolicy policy;
            try {
                policy = DecisionTable.HitPolicy.valueOf(
                        String.valueOf(node.getOrDefault("hitPolicy", "FIRST"))
                                .trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new RuleException("决策表[" + id + "] 的 hitPolicy 非法："
                        + node.get("hitPolicy") + "，可选 FIRST / UNIQUE");
            }
            tables.put(id, new DecisionTableSpec(id,
                    node.get("kind") == null ? null : String.valueOf(node.get("kind")),
                    rows, node.get("default"), policy));
        });
        return tables;
    }

    /**
     * 读取决策节点。
     *
     * <p>支持两种写法：</p>
     * <pre>{@code
     * { "decision": "riskLevel", "is": "HIGH" }
     * { "decision": "riskLevel", "in": ["HIGH", "CRITICAL"] }
     * }</pre>
     *
     * @param node 节点
     * @param name 规则名
     * @return 决策节点
     */
    private static RuleConditionSpec readDecision(Map<String, Object> node, String name) {
        String decisionId = String.valueOf(node.get("decision"));
        if (node.containsKey("in")) {
            List<Object> expected = asArray(node.get("in"), name);
            if (expected == null || expected.isEmpty()) {
                throw new RuleException("规则[" + name + "] decision.in 不能为空");
            }
            return RuleConditionSpec.decision(decisionId, "IN", List.copyOf(expected));
        }
        if (node.containsKey("is")) {
            return RuleConditionSpec.decision(decisionId, node.get("is"));
        }
        throw new RuleException("规则[" + name + "] decision 节点必须给出 is 或 in："
                + decisionId);
    }

    /**
     * 读取 when 节点本体（不含同级 facts）。
     *
     * @param node when 对象
     * @param name 规则名
     * @return 条件树
     */
    private static RuleConditionSpec readWhenBody(Map<String, Object> node, String name) {
        List<Object> factsArray = asArray(node.get("facts"), name);
        if (factsArray == null || factsArray.isEmpty()) {
            // 没有 facts 时，若 when 本身就是条件节点（决策/表达式/组合/比较），
            // 直接按节点读；决策节点尤其常见——决策行自带绑定，
            // 不需要外层再声明事实
            if (!node.isEmpty() && !node.keySet().stream().allMatch("facts"::equals)) {
                return readNode(node, name);
            }
            throw new RuleException("规则[" + name + "] when 段缺少 facts 声明；"
                    + "若只想写表达式，可把 when 直接写成字符串，"
                    + "并用同级的 \"facts\": [{\"type\":\"order\",\"binding\":\"o\"}] 声明绑定；"
                    + "若只想用决策表，可写 \"when\": {\"decision\":\"riskLevel\",\"is\":\"HIGH\"}");
        }
        List<RuleConditionSpec> facts = new ArrayList<>(factsArray.size() + 1);
        facts.addAll(readDefinitions(factsArray, name));
        Map<String, Object> whereObject = asObject(node.get("where"), name);
        if (whereObject != null) {
            facts.add(readNode(whereObject, name));
        }
        if (node.containsKey("expr")) {
            Object expr = node.get("expr");
            if (expr == null || String.valueOf(expr).isBlank()) {
                throw new RuleException("规则[" + name + "] when.expr 不能为空");
            }
            facts.add(RuleConditionSpec.expression(String.valueOf(expr)));
        }
        return RuleConditionSpec.all(facts);
    }

    /**
     * 递归读取条件节点。
     *
     * @param node 节点对象
     * @param name 规则名
     * @return 条件树节点
     */
    private static RuleConditionSpec readNode(Map<String, Object> node, String name) {
        if (node.containsKey("all")) {
            return RuleConditionSpec.all(readNodes(node.get("all"), name));
        }
        if (node.containsKey("any")) {
            return RuleConditionSpec.any(readNodes(node.get("any"), name));
        }
        if (node.containsKey("not")) {
            Map<String, Object> child = asObject(node.get("not"), name);
            if (child == null) {
                throw new RuleException("规则[" + name + "] not 段必须是对象");
            }
            return RuleConditionSpec.not(readNode(child, name));
        }
        if (node.containsKey("expr")) {
            return RuleConditionSpec.expression(String.valueOf(node.get("expr")));
        }
        if (node.containsKey("exists")) {
            return RuleConditionSpec.exists(String.valueOf(node.get("exists")));
        }
        if (node.containsKey("global")) {
            return RuleConditionSpec.globalCompare(
                    String.valueOf(node.get("global")),
                    node.get("op") == null ? null : String.valueOf(node.get("op")),
                    node.get("value"));
        }
        if (node.containsKey("decision")) {
            return readDecision(node, name);
        }
        if (node.containsKey("path")) {
            Object binding = node.get("binding");
            return RuleConditionSpec.compare(
                    binding == null ? null : String.valueOf(binding),
                    String.valueOf(node.get("path")),
                    node.get("op") == null ? null : String.valueOf(node.get("op")),
                    node.get("value"));
        }
        throw new RuleException("规则[" + name + "] 条件节点无法识别：" + node.keySet());
    }

    /**
     * 读取条件节点数组。
     *
     * @param value 节点数组
     * @param name  规则名
     * @return 节点列表
     */
    private static List<RuleConditionSpec> readNodes(Object value, String name) {
        List<Object> array = asArray(value, name);
        if (array == null || array.isEmpty()) {
            throw new RuleException("规则[" + name + "] 条件数组为空");
        }
        List<RuleConditionSpec> nodes = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            nodes.add(readNode(asObject(array.get(i), name), name));
        }
        return nodes;
    }

    /**
     * 读取 then 段。
     *
     * @param value 动作数组
     * @param name  规则名
     * @return 动作列表
     */
    private static List<RuleActionSpec> readThen(Object value, String name) {
        List<Object> array = asArray(value, name);
        if (array == null || array.isEmpty()) {
            throw new RuleException("规则[" + name + "] 缺少 then 段或为空");
        }
        List<RuleActionSpec> actions = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Map<String, Object> node = asObject(array.get(i), name);
            Object actionName = node.get("action");
            if (actionName == null || String.valueOf(actionName).isBlank()) {
                throw new RuleException("规则[" + name + "] then[" + i + "] 缺少 action");
            }
            Map<String, Object> args = new LinkedHashMap<>(node);
            args.remove("action");
            actions.add(new RuleActionSpec(String.valueOf(actionName), args));
        }
        return actions;
    }

    /**
     * 读取整数值。
     *
     * @param value        原始值
     * @param defaultValue 缺省值
     * @return 整数值
     */
    private static int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new RuleException("无法解析为整数：" + value, e);
        }
    }

    /**
     * 读取布尔值。
     *
     * @param value        原始值
     * @param defaultValue 缺省值
     * @return 布尔值
     */
    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new RuleException("无法解析为布尔值：" + value);
    }
}
