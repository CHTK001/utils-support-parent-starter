package com.chua.common.support.rule.file;

import com.chua.common.support.rule.RuleException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则文件中单条动作的定义。
 *
 * <p>对应规则文件 {@code then} 数组的一个元素：</p>
 * <pre>{@code
 * { "action": "record", "key": "reason", "value": "AMOUNT_EXCEEDED" }
 * }</pre>
 *
 * <p>{@code action} 之后的键值对统一收纳在 {@link #args()} 中，
 * 由 {@link RuleActionRegistry} 中注册的工厂按名消费。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleActionSpec {

    /**
     * 动作名
     */
    private final String name;

    /**
     * 动作参数
     */
    private final Map<String, Object> args;

    /**
     * 创建动作定义。
     *
     * @param name  动作名
     * @param args  动作参数
     */
    public RuleActionSpec(String name, Map<String, Object> args) {
        this.name = name;
        this.args = args == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /**
     * 获取动作名。
     *
     * @return 动作名
     */
    public String name() {
        return name;
    }

    /**
     * 获取全部参数。
     *
     * @return 参数快照，只读
     */
    public Map<String, Object> args() {
        return args;
    }

    /**
     * 获取必填参数。
     *
     * @param key 参数键
     * @return 参数值
     * @throws RuleException 参数缺失时抛出
     */
    public Object require(String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new RuleException("动作[" + name + "]缺少必填参数：" + key);
        }
        return value;
    }

    /**
     * 获取必填字符串参数。
     *
     * @param key 参数键
     * @return 参数值
     * @throws RuleException 参数缺失或非字符串时抛出
     */
    public String requireText(String key) {
        Object value = require(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new RuleException("动作[" + name + "]参数[" + key + "]必须是非空字符串");
        }
        return text;
    }

    /**
     * 获取可选参数。
     *
     * @param key 参数键
     * @return 参数值，缺失返回 null
     */
    public Object optional(String key) {
        return args.get(key);
    }

    /**
     * 获取可选参数。
     *
     * @param key          参数键
     * @param defaultValue 缺省值
     * @return 参数值，缺失返回缺省值
     */
    public Object optional(String key, Object defaultValue) {
        Object value = args.get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 获取可选字符串参数。
     *
     * @param key          参数键
     * @param defaultValue 缺省值
     * @return 参数值，缺失返回缺省值
     */
    public String optionalText(String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            throw new RuleException("动作[" + name + "]参数[" + key + "]必须是非空字符串");
        }
        return text;
    }

    @Override
    public String toString() {
        return "RuleActionSpec[" + name + (args.isEmpty() ? "" : " " + args) + "]";
    }
}
