package com.chua.common.support.rule.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则集定义。
 *
 * <p>对应一个规则文件的内容，或多个规则文件合并后的结果。</p>
 *
 * <h3>文件形态</h3>
 * <pre>{@code
 * // 形态一：规则集对象
 * {
 *   "version": "1.0",
 *   "globals": { "maxAmount": 100000 },
 *   "rules": [ { ... }, { ... } ]
 * }
 *
 * // 形态二：JSONL，每行一条规则（便于大文件与行级 diff）
 * { "name": "r1", "when": {...}, "then": [...] }
 * { "name": "r2", "when": {...}, "then": [...] }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleSetSpec {

    /**
     * 规则集版本
     */
    private final String version;

    /**
     * 全局变量
     */
    private final Map<String, Object> globals;

    /**
     * 规则列表
     */
    private final List<RuleSpec> rules;

    /**
     * 来源标识（文件路径或 {@code inline}），便于诊断
     */
    private final String origin;

    /**
     * 创建规则集定义。
     *
     * @param version 版本
     * @param globals 全局变量
     * @param rules   规则列表
     * @param origin  来源标识
     */
    public RuleSetSpec(String version, Map<String, Object> globals, List<RuleSpec> rules, String origin) {
        this.version = version;
        this.globals = globals == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(globals));
        this.rules = rules == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(rules));
        this.origin = origin == null ? "inline" : origin;
    }

    /**
     * 获取规则集版本。
     *
     * @return 版本，未指定返回 null
     */
    public String version() {
        return version;
    }

    /**
     * 获取全局变量。
     *
     * @return 全局变量，只读
     */
    public Map<String, Object> globals() {
        return globals;
    }

    /**
     * 获取规则列表。
     *
     * @return 规则列表，只读
     */
    public List<RuleSpec> rules() {
        return rules;
    }

    /**
     * 获取来源标识。
     *
     * @return 来源标识
     */
    public String origin() {
        return origin;
    }

    @Override
    public String toString() {
        return "RuleSetSpec[origin=" + origin + ", version=" + version
                + ", rules=" + rules.size() + ", globals=" + globals.size() + "]";
    }
}
