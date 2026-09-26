package com.chua.common.support.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则上下文。
 *
 * <p>规则求值（LHS）与动作执行（RHS）期间共享的数据视图。
 * 每次规则激活（一个事实绑定元组）对应一个上下文实例。</p>
 *
 * <h3>数据来源</h3>
 * <ul>
 *   <li><b>绑定（binding）</b> — {@link Pattern} 匹配到的事实，按绑定名存取</li>
 *   <li><b>全局变量（global）</b> — 会话创建时注入，跨规则共享且不参与匹配</li>
 *   <li><b>结果（result）</b> — 本轮推理产出的业务结果</li>
 * </ul>
 *
 * <h3>线程约束</h3>
 * <p>上下文实例绑定单个 {@link RuleSession}，
 * 而 {@code RuleSession} 非线程安全，因此上下文也不可跨线程共享。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleContext {

    /**
     * 默认列表键
     */
    public static final String DEFAULT_LIST_KEY = "__list__";

    /**
     * 所属会话
     */
    private final RuleSession session;

    /**
     * 当前激活的规则
     */
    private final Rule rule;

    /**
     * 事实绑定表，保持插入顺序
     */
    private final Map<String, Object> bindings = new LinkedHashMap<>();

    /**
     * 本轮结果条目
     */
    private final Map<String, Object> entries = new LinkedHashMap<>();

    /**
     * 列表结果，延迟创建
     */
    private final Map<String, List<Object>> lists = new LinkedHashMap<>();

    /**
     * 主结果值
     */
    private Object result;

    /**
     * 是否已请求停止本轮推理
     */
    private boolean halted;

    /**
     * 创建规则上下文。
     *
     * @param session 所属会话
     * @param rule    当前规则
     */
    RuleContext(RuleSession session, Rule rule) {
        this.session = session;
        this.rule = rule;
    }

    /**
     * 获取当前规则。
     *
     * @return 当前规则
     */
    public Rule rule() {
        return rule;
    }

    /**
     * 获取当前规则名。
     *
     * @return 当前规则名
     */
    public String ruleName() {
        return rule == null ? null : rule.name();
    }

    /**
     * 绑定事实。
     *
     * @param binding 绑定名
     * @param fact    事实对象
     */
    void bind(String binding, Object fact) {
        if (binding != null) {
            bindings.put(binding, fact);
        }
    }

    /**
     * 清除全部绑定。
     */
    void clearBindings() {
        bindings.clear();
    }

    /**
     * 获取绑定值。
     *
     * @param binding 绑定名
     * @return 绑定值，不存在返回 null
     */
    public Object get(String binding) {
        return binding == null ? null : bindings.get(binding);
    }

    /**
     * 获取绑定值并做类型转换。
     *
     * @param binding  绑定名
     * @param type     期望类型
     * @param <T>      目标类型
     * @return 绑定值，类型不符返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String binding, Class<T> type) {
        Object value = get(binding);
        if (value == null || type == null) {
            return null;
        }
        return type.isInstance(value) ? (T) value : null;
    }

    /**
     * 获取全部绑定。
     *
     * @return 绑定快照，只读
     */
    public Map<String, Object> bindings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /**
     * 获取全局变量。
     *
     * @param key 变量名
     * @return 变量值，不存在返回 null
     */
    public Object global(String key) {
        return session == null ? null : session.global(key);
    }

    /**
     * 获取工作内存中指定类型的事实。
     *
     * @param type 事实类型
     * @return 事实列表，只读
     */
    public List<Object> factsOf(Class<?> type) {
        return session == null ? List.of() : session.factsOf(type);
    }

    /**
     * 插入事实到工作内存。
     *
     * @param fact 待插入事实
     */
    public void insert(Fact fact) {
        if (session != null) {
            session.insert(fact);
        }
    }

    /**
     * 撤销已绑定事实。
     *
     * @param binding 绑定名
     */
    public void retract(String binding) {
        if (session != null) {
            session.retract(get(binding));
        }
    }

    /**
     * 修改已绑定事实的属性。
     *
     * <p>属性写入统一走
     * {@link com.chua.common.support.reflection.ReflectUtils#setField(Object, String, Object)}。</p>
     *
     * @param binding  绑定名
     * @param property 属性名
     * @param value    新值
     * @return 修改成功返回 true
     */
    public boolean setProperty(String binding, String property, Object value) {
        Object target = get(binding);
        if (target == null) {
            return false;
        }
        boolean applied = com.chua.common.support.reflection.ReflectUtils.setField(target, property, value);
        if (applied && session != null) {
            session.touch(target);
        }
        return applied;
    }

    /**
     * 标记事实已变更。
     *
     * <p>在 RHS 中直接修改事实字段后调用，
     * 可让依赖该事实的规则在下一轮重新激活（Drools refraction 语义）。
     * {@link #setProperty(String, String, Object)} 已自动调用本方法，
     * 直接操作字段时需手工调用。</p>
     *
     * @param fact 已变更事实
     * @return 标记成功返回 true
     */
    public boolean markChanged(Object fact) {
        return session != null && session.touch(fact);
    }

    /**
     * 获取事实版本号。
     *
     * @param fact 事实
     * @return 版本号，从 0 开始
     */
    int version(Object fact) {
        return session == null ? 0 : session.versionOf(fact);
    }

    /**
     * 设置本轮主结果。
     *
     * @param value 结果值
     */
    public void setResult(Object value) {
        this.result = value;
    }

    /**
     * 获取本轮主结果。
     *
     * @return 主结果，初始为 null
     */
    public Object result() {
        return result;
    }

    /**
     * 记录一条结果条目。
     *
     * @param key   条目键
     * @param value 条目值
     */
    public void record(String key, Object value) {
        if (key != null) {
            entries.put(key, value);
        }
    }

    /**
     * 获取全部结果条目。
     *
     * @return 结果条目快照，只读
     */
    public Map<String, Object> entries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * 获取（或创建）指定键的列表。
     *
     * @param key 列表键
     * @return 列表实例，可直接追加
     */
    public List<Object> list(String key) {
        String listKey = (key == null || key.isBlank()) ? DEFAULT_LIST_KEY : key;
        return lists.computeIfAbsent(listKey, ignored -> new ArrayList<>());
    }

    /**
     * 获取（或创建）指定键的列表。
     *
     * @return 列表实例
     */
    public List<Object> list() {
        return list(DEFAULT_LIST_KEY);
    }

    /**
     * 向列表追加元素。
     *
     * @param key     列表键
     * @param element 元素
     */
    public void addToList(String key, Object element) {
        list(key).add(element);
    }

    /**
     * 获取全部列表结果。
     *
     * @return 列表结果快照，只读
     */
    public Map<String, List<Object>> lists() {
        Map<String, List<Object>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : lists.entrySet()) {
            snapshot.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 请求停止本轮推理。
     */
    public void halt() {
        this.halted = true;
    }

    /**
     * 是否已请求停止本轮推理。
     *
     * @return 已请求停止返回 true
     */
    public boolean halted() {
        return halted;
    }
}
