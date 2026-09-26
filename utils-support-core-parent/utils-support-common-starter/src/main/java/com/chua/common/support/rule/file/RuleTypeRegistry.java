package com.chua.common.support.rule.file;

import com.chua.common.support.rule.Pattern;
import com.chua.common.support.rule.RuleException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则文件类型注册表。
 *
 * <p>把规则文件里出现的类型标识（别名）解析为 Java 类。
 * 规则文件由运维/业务方编写，若允许其自由书写全限定类名，
 * 等于把类路径暴露给配置。因此本注册表提供<b>白名单</b>语义：</p>
 *
 * <ul>
 *   <li><b>严格模式（默认）</b> — 只接受已注册的别名，
 *       规则文件无法引用类路径上的任意类</li>
 *   <li><b>宽松模式</b> — 由使用方显式开启后，允许回退到
 *       {@link Class#forName(String)} 解析全限定类名</li>
 * </ul>
 *
 * <h3>为什么类型也需要白名单</h3>
 * <p>类型本身只参与 {@code isInstance} 判断与 getter 调用，风险低于动作与表达式；
 * 但允许配置指定任意类会扩大攻击面（例如诱导引擎加载重量级类触发静态初始化）。
 * 默认严格是更安全的默认值，同时保留宽松模式以兼容存量配置。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RuleTypeRegistry types = RuleTypeRegistry.create()
 *         .register("order", OrderFact.class)
 *         .register("user", UserFact.class);
 *
 * RuleTypeRegistry relaxed = RuleTypeRegistry.create()
 *         .allowClassName(true)
 *         .register("order", OrderFact.class);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleTypeRegistry {

    /**
     * 别名到类型的映射
     */
    private final Map<String, Class<?>> aliases = new ConcurrentHashMap<>();

    /**
     * 通配符/正则到类型的映射，供「一族类型共用一条规则」使用
     */
    private final Map<String, Class<?>> patterns = new ConcurrentHashMap<>();

    /**
     * 是否允许直接使用全限定类名
     */
    private volatile boolean allowClassName;

    /**
     * 创建注册表。
     */
    private RuleTypeRegistry() {
    }

    /**
     * 创建注册表构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建空注册表（严格模式，不含任何别名）。
     *
     * @return 注册表
     */
    public static RuleTypeRegistry create() {
        return new RuleTypeRegistry();
    }

    /**
     * 注册类型别名。
     *
     * @param alias    别名
     * @param factType 类型
     * @return 当前注册表
     */
    public RuleTypeRegistry register(String alias, Class<?> factType) {
        if (alias == null || alias.isBlank()) {
            throw new RuleException("类型别名不能为空");
        }
        if (factType == null) {
            throw new RuleException("类型不能为 null");
        }
        aliases.put(alias.trim(), factType);
        return this;
    }

    /**
     * 注册一个<b>通配符或正则</b>类型模式，让一条规则覆盖一族事实类型。
     *
     * <p>两种写法：</p>
     * <ul>
     *   <li>通配符：{@code *} 任意长度、{@code ?} 单字符，
     *       例如 {@code "pay.*"} 匹配所有以 pay 开头的别名；</li>
     *   <li>正则：{@code /.../} 包裹，例如 {@code "/^Order.*Fact$/"}。</li>
     * </ul>
     *
     * <p>匹配对象是<b>已注册的别名</b>而不是类全名——注册表仍是白名单的唯一入口，
     * 模式只能挑到已注册的类型，不会凭空引入新类。</p>
     *
     * <p><b>不允许歧义</b>：一个标识符同时命中多条模式、且这些模式指向<b>不同</b>类型时
     * 直接报错并列出候选，而不是「取第一个」——静默选一个会让规则行为随注册顺序漂移。
     * 多条模式指向同一类型不算歧义。</p>
     *
     * @param pattern  通配符或 {@code /正则/}
     * @param factType 事实类型
     * @return 当前注册表
     */
    public RuleTypeRegistry registerPattern(String pattern, Class<?> factType) {
        if (pattern == null || pattern.isBlank()) {
            throw new RuleException("类型模式不能为空");
        }
        if (factType == null) {
            throw new RuleException("类型不能为 null");
        }
        String key = pattern.trim();
        if (!isRegexPattern(key) && key.indexOf('*') < 0 && key.indexOf('?') < 0) {
            throw new RuleException("类型模式必须含通配符 * 或 ? ，或用 /正则/ 包裹：" + key);
        }
        if (isRegexPattern(key)) {
            // 提前编译：配置写错在注册时就暴露，而不是等到规则求值
            Pattern.compileRegex(key.substring(1, key.length() - 1));
        }
        patterns.put(key, factType);
        return this;
    }

    /**
     * 判断是否为 {@code /正则/} 形式。
     *
     * @param key 模式
     * @return 是返回 true
     */
    private static boolean isRegexPattern(String key) {
        return key.length() >= 2 && key.charAt(0) == '/'
                && key.charAt(key.length() - 1) == '/';
    }

    /**
     * 用通配符/正则模式匹配已注册别名。
     *
     * <p>命中多条模式时：<b>解析到同一个类型不算歧义</b>（多条规则指向同一
     * 实现，结果唯一）；<b>指向不同类型才报错</b>。这比「一律报错」更实用，
     * 同时避免了「取第一个」——那会让规则行为随注册顺序漂移。</p>
     *
     * @param identifier 待解析标识符
     * @return 命中的类型，未命中返回 null
     */
    private Class<?> resolveByPattern(String identifier) {
        if (patterns.isEmpty()) {
            return null;
        }
        Class<?> wildcardHit = null;
        String wildcardKey = null;
        Class<?> regexHit = null;
        String regexKey = null;
        for (Map.Entry<String, Class<?>> entry : patterns.entrySet()) {
            String pattern = entry.getKey();
            Class<?> candidate = null;
            boolean isRegex = isRegexPattern(pattern);
            if (isRegex) {
                if (Pattern.regexMatches(identifier,
                        pattern.substring(1, pattern.length() - 1))) {
                    candidate = entry.getValue();
                    if (regexHit != null && regexHit != candidate) {
                        throw ambiguous(identifier, regexKey, pattern);
                    }
                    regexHit = candidate;
                    regexKey = pattern;
                }
            } else if (Pattern.wildcardMatches(identifier, pattern)) {
                candidate = entry.getValue();
                if (wildcardHit != null && wildcardHit != candidate) {
                    throw ambiguous(identifier, wildcardKey, pattern);
                }
                wildcardHit = candidate;
                wildcardKey = pattern;
            }
            if (candidate == null) {
                continue;
            }
            // 通配符与正则同时命中且指向不同类型：意图冲突，必须让作者收窄
            if (wildcardHit != null && regexHit != null && wildcardHit != regexHit) {
                throw ambiguous(identifier, wildcardKey, regexKey);
            }
        }
        // 正则优先于通配符：正则表达的是更明确的意图
        return regexHit != null ? regexHit : wildcardHit;
    }

    /**
     * 构造模式歧义异常。
     *
     * @param identifier 标识符
     * @param first      首个命中模式
     * @param second     第二个命中模式
     * @return 异常
     */
    private static RuleException ambiguous(String identifier, String first, String second) {
        return new RuleException("类型标识符 '" + identifier + "' 同时命中多条模式："
                + first + " 与 " + second + "；请收窄模式，规则不允许歧义匹配");
    }

    /**
     * 批量注册类型别名。
     *
     * @param mapping 别名到类型的映射
     * @return 当前注册表
     */
    public RuleTypeRegistry registerAll(Map<String, Class<?>> mapping) {
        if (mapping != null) {
            for (Map.Entry<String, Class<?>> entry : mapping.entrySet()) {
                register(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    /**
     * 设置是否允许全限定类名。
     *
     * @param allowClassName true 允许
     * @return 当前注册表
     */
    public RuleTypeRegistry allowClassName(boolean allowClassName) {
        this.allowClassName = allowClassName;
        return this;
    }

    /**
     * 解析类型标识。
     *
     * @param identifier 别名或全限定类名
     * @return 解析出的类型
     * @throws RuleException 无法解析时抛出
     */
    public Class<?> resolve(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new RuleException("类型标识不能为空");
        }
        String key = identifier.trim();
        Class<?> resolved = aliases.get(key);
        if (resolved != null) {
            return resolved;
        }
        // 精确别名未命中时，再按通配符/正则模式匹配已注册别名
        Class<?> byPattern = resolveByPattern(key);
        if (byPattern != null) {
            return byPattern;
        }
        if (!allowClassName) {
            throw new RuleException("未注册的类型别名：" + key
                    + "（当前为严格模式；如需使用全限定类名请调用 allowClassName(true)）");
        }
        try {
            return Class.forName(key, true, classLoader());
        } catch (ClassNotFoundException e) {
            throw new RuleException("类型无法解析：" + key, e);
        }
    }

    /**
     * 判断类型标识是否可解析。
     *
     * @param identifier 别名或全限定类名
     * @return 可解析返回 true
     */
    public boolean resolvable(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        String key = identifier.trim();
        if (aliases.containsKey(key)) {
            return true;
        }
        if (resolveByPattern(key) != null) {
            return true;
        }
        if (!allowClassName) {
            return false;
        }
        try {
            Class.forName(key, false, classLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 获取已注册别名。
     *
     * @return 别名快照，只读
     */
    public Map<String, Class<?>> aliases() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
    }

    /**
     * 获取类加载器。
     *
     * @return 上下文类加载器，缺失时回退到本类类加载器
     */
    private ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? RuleTypeRegistry.class.getClassLoader() : loader;
    }

    /**
     * 注册表构建器。
     */
    public static final class Builder {

        /**
         * 注册表
         */
        private final RuleTypeRegistry registry = new RuleTypeRegistry();

        /**
         * 创建构建器。
         */
        private Builder() {
        }

        /**
         * 注册类型别名。
         *
         * @param alias    别名
         * @param factType 类型
         * @return 当前构建器
         */
        public Builder register(String alias, Class<?> factType) {
            registry.register(alias, factType);
            return this;
        }

        /**
         * 批量注册类型别名。
         *
         * @param mapping 别名到类型的映射
         * @return 当前构建器
         */
        public Builder registerAll(Map<String, Class<?>> mapping) {
            registry.registerAll(mapping);
            return this;
        }

        /**
         * 允许使用全限定类名。
         *
         * @param allow true 允许
         * @return 当前构建器
         */
        public Builder allowClassName(boolean allow) {
            registry.allowClassName(allow);
            return this;
        }

        /**
         * 构建注册表。
         *
         * @return 注册表
         */
        public RuleTypeRegistry build() {
            return registry;
        }
    }
}
