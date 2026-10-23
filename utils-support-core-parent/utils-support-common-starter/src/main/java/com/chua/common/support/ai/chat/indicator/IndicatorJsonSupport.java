package com.chua.common.support.ai.chat.indicator;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@link com.chua.common.support.ai.chat.ChatClientIndicator} 各实现共用的 JSON 取值工具。
 *
 * <p>各服务商的模型目录字段名差异很大（{@code pricing.input}、
 * {@code pricing.prompt}、{@code cost.input}、{@code promptTextTokens} 等），
 * 但归一化后的指标键必须保持一致（snake_case，价格统一为 USD / 百万 Token）。
 * 本类只做「取值 + 单位换算」，不包含任何服务商特有的结构判断。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class IndicatorJsonSupport {

    /**
     * 每百万 Token 的换算系数
     */
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    /**
     * 判定「按 Token 计价」的阈值：小于该值的单价几乎不可能按百万 Token 报价
     */
    private static final BigDecimal PER_TOKEN_THRESHOLD = new BigDecimal("0.0001");

    private IndicatorJsonSupport() {
    }

    /**
     * 读取文本字段。
     *
     * @param node 节点
     * @param key  字段名
     * @return 文本；缺失或空串时返回 null
     */
    static String text(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * 读取节点自身的文本值。
     *
     * @param node 节点
     * @return 文本；缺失或空串时返回 null
     */
    static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * 读取数值字段。
     *
     * @param node 节点
     * @param key  字段名
     * @return 数值；缺失或非数值时返回 null
     */
    static BigDecimal decimal(JsonNode node, String key) {
        return node == null ? null : decimal(node.get(key));
    }

    /**
     * 读取数值节点。
     *
     * @param node 节点
     * @return 数值；缺失或非数值时返回 null
     */
    static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.asText(null);
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
     * 读取 Long 值。
     *
     * @param node 节点
     * @param key  字段名
     * @return 数值；缺失或非数值时返回 null
     */
    static Long longValue(JsonNode node, String key) {
        BigDecimal value = decimal(node, key);
        return value == null ? null : value.longValue();
    }

    /**
     * 按「每 Token」报价换算为 USD / 百万 Token。
     *
     * <p>负值（如 OpenRouter 用 {@code -1} 表示价格不定）按未知处理。</p>
     *
     * @param node 价格节点
     * @return 每百万 Token 价格；缺失、非法或负值时返回 null
     */
    static BigDecimal perMillion(JsonNode node) {
        BigDecimal value = decimal(node);
        if (value == null || value.signum() < 0) {
            return null;
        }
        return value.multiply(MILLION).stripTrailingZeros();
    }

    /**
     * 兼容「每 Token」与「每百万 Token」两种口径的启发式换算。
     *
     * <p>部分目录的 {@code pricing.input} 直接给每百万 Token 价格（如 2.5），
     * 另一些（Vercel、OpenRouter）给每 Token 价格（如 {@code 0.000002}）。
     * 小于 {@link #PER_TOKEN_THRESHOLD} 的非零值只可能按 Token 报价，
     * 其余按百万口径原样保留。</p>
     *
     * @param node 价格节点
     * @return 每百万 Token 价格；缺失或非法时返回 null
     */
    static BigDecimal perMillionHeuristic(JsonNode node) {
        BigDecimal value = decimal(node);
        if (value == null || value.signum() < 0) {
            return null;
        }
        if (value.signum() > 0 && value.compareTo(PER_TOKEN_THRESHOLD) < 0) {
            return value.multiply(MILLION).stripTrailingZeros();
        }
        return value.stripTrailingZeros();
    }

    /**
     * 读取按次计费的价格（图片 / 检索），负数视为未知，0 保留。
     *
     * @param node 价格节点
     * @return 单次价格；缺失或非法时返回 null
     */
    static BigDecimal perUnit(JsonNode node) {
        BigDecimal value = decimal(node);
        if (value == null || value.signum() < 0) {
            return null;
        }
        return value.stripTrailingZeros();
    }

    /**
     * 字符串数组是否包含指定值。
     *
     * @param array 数组节点
     * @param value 目标值
     * @return 包含返回 true
     */
    static boolean containsText(JsonNode array, String value) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 任意 JSON 节点转 Java 原生值（Map / List / 基本类型）。
     *
     * @param node 节点
     * @return 原生值；null 节点返回 null
     */
    static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                values.add(toJava(item));
            }
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new HashMap<>(node.size());
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                values.put(field.getKey(), toJava(field.getValue()));
            }
            return values;
        }
        return node.toString();
    }

    /**
     * 对象数组转 {@code List<Map<String, Object>>}，非对象元素跳过。
     *
     * @param node 节点
     * @return 列表；非数组时返回空列表
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objectList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            values.add((Map<String, Object>) toJava(item));
        }
        return values;
    }

    /**
     * 字符串数组转 {@code List<String>}。
     *
     * @param node 节点
     * @return 列表；非数组时返回空列表
     */
    static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String text = text(item);
            if (text != null) {
                values.add(text);
            }
        }
        return values;
    }

    /**
     * 值非空时写入指标 Map。
     *
     * @param target 目标
     * @param key    指标键
     * @param value  值
     */
    static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 指标 Map 中是否已有该键。
     *
     * @param target 目标
     * @param key    键
     * @return 已存在返回 true
     */
    static boolean has(Map<String, Object> target, String key) {
        return target.containsKey(key) && target.get(key) != null;
    }
}
