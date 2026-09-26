package com.chua.common.support.rule.file;

import com.chua.common.support.rule.Actions;
import com.chua.common.support.rule.RuleContext;
import com.chua.common.support.rule.RuleException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则文件动作注册表。
 *
 * <p>把规则文件 {@code then} 段里的动作名解析为
 * {@link com.chua.common.support.rule.Action}。</p>
 *
 * <h3>安全边界（重要）</h3>
 * <p>本注册表<b>只接受已注册的动作名</b>，<b>不支持</b>按类名反射实例化动作。
 * 原因是规则文件是可被业务方改写的配置：若允许
 * {@code {"action": "com.evil.EvilAction"}} 这类写法，
 * 任何能改规则文件的人都能在服务进程里执行任意代码（RCE）。
 * 动作名到实现的映射必须由代码显式注册，配置文件只能"点名"。</p>
 *
 * <p>内置动作（见 {@link #createDefault()}）：</p>
 * <table border="1">
 *   <caption>内置动作与参数</caption>
 *   <tr><th>动作名</th><th>参数</th><th>说明</th></tr>
 *   <tr><td>{@code allow}</td><td>无</td><td>产出放行结论</td></tr>
 *   <tr><td>{@code deny}</td><td>无</td><td>产出断路结论</td></tr>
 *   <tr><td>{@code setResult}</td><td>{@code value}</td><td>设置本轮结果</td></tr>
 *   <tr><td>{@code record}</td><td>{@code key}, {@code value}</td><td>记录结果条目</td></tr>
 *   <tr><td>{@code addToList}</td><td>{@code key}, {@code value}</td><td>追加列表元素</td></tr>
 *   <tr><td>{@code retract}</td><td>{@code binding}</td><td>撤销绑定事实</td></tr>
 *   <tr><td>{@code set}</td><td>{@code binding}, {@code property}, {@code value}</td>
 *       <td>修改事实属性</td></tr>
 *   <tr><td>{@code halt}</td><td>无</td><td>停止本轮推理</td></tr>
 * </table>
 *
 * <h3>扩展自定义动作</h3>
 * <pre>{@code
 * RuleActionRegistry registry = RuleActionRegistry.createDefault()
 *         .register("alert", spec -> ctx -> alertService.fire(ctx.get("order")));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleActionRegistry {

    /**
     * 动作工厂：按动作定义构造动作实例
     */
    @FunctionalInterface
    public interface ActionFactory {

        /**
         * 根据动作定义构造动作。
         *
         * @param spec 动作定义
         * @return 动作实例
         */
        com.chua.common.support.rule.Action create(RuleActionSpec spec);
    }

    /**
     * 动作名到工厂的映射
     */
    private final Map<String, ActionFactory> factories = new ConcurrentHashMap<>();

    /**
     * 创建注册表。
     */
    private RuleActionRegistry() {
    }

    /**
     * 创建仅含自定义动作的空注册表。
     *
     * @return 注册表
     */
    public static RuleActionRegistry create() {
        return new RuleActionRegistry();
    }

    /**
     * 创建含内置动作的注册表。
     *
     * @return 注册表
     */
    public static RuleActionRegistry createDefault() {
        return new RuleActionRegistry()
                .register("allow", spec -> Actions.allow())
                .register("deny", spec -> Actions.deny())
                .register("halt", spec -> Actions.halt())
                .register("setResult", spec -> Actions.setResult(spec.optional("value", null)))
                .register("record", spec -> Actions.record(
                        spec.requireText("key"), spec.optional("value", null)))
                .register("addToList", spec -> Actions.addToList(
                        spec.optionalText("key", RuleContext.DEFAULT_LIST_KEY), spec.require("value")))
                .register("retract", spec -> Actions.retract(spec.requireText("binding")))
                .register("set", spec -> Actions.set(
                        spec.requireText("binding"), spec.requireText("property"), spec.optional("value", null)));
    }

    /**
     * 注册动作。
     *
     * @param name    动作名
     * @param factory 动作工厂
     * @return 当前注册表
     */
    public RuleActionRegistry register(String name, ActionFactory factory) {
        if (name == null || name.isBlank()) {
            throw new RuleException("动作名不能为空");
        }
        if (factory == null) {
            throw new RuleException("动作工厂不能为 null");
        }
        factories.put(name.trim(), factory);
        return this;
    }

    /**
     * 按名构造动作。
     *
     * @param spec 动作定义
     * @return 动作实例
     * @throws RuleException 动作未注册时抛出
     */
    public com.chua.common.support.rule.Action create(RuleActionSpec spec) {
        if (spec == null) {
            throw new RuleException("动作定义不能为 null");
        }
        String name = spec.name();
        if (name == null || name.isBlank()) {
            throw new RuleException("动作定义缺少 name 字段");
        }
        ActionFactory factory = factories.get(name.trim());
        if (factory == null) {
            throw new RuleException("未注册的动作：" + name
                    + "（可用动作：" + String.join(", ", factories.keySet()) + "）");
        }
        return factory.create(spec);
    }

    /**
     * 判断动作名是否已注册。
     *
     * @param name 动作名
     * @return 已注册返回 true
     */
    public boolean contains(String name) {
        return name != null && factories.containsKey(name.trim());
    }

    /**
     * 获取已注册动作名。
     *
     * @return 动作名集合，只读
     */
    public java.util.Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashMap<>(factories).keySet());
    }
}
