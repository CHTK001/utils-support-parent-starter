package com.chua.common.support.lang.placeholder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * 复合占位符解析器。
 * <p>
 * 该类支持同时解析多种格式的占位符，例如：
 * ${key:default} 和 &lt;key:default&gt;。
 * 通过组合多个 {@link PropertyResolver} 实现链式处理，
 * 前一个解析器的输出将作为下一个解析器的输入。
 * </p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 创建支持 ${} 和 <> 两种格式的解析器
 * CompositePropertyResolver resolver = CompositePropertyResolver.builder()
 *     .addResolver(PlaceholderSupport.DEFAULT_PLACEHOLDER_PREFIX, 
 *                  PlaceholderSupport.DEFAULT_PLACEHOLDER_SUFFIX,
 *                  PlaceholderSupport.DEFAULT_VALUE_SEPARATOR)
 *     .addResolver("<", ">", ":")
 *     .build(placeholderResolver);
 *
 * // 执行解析
 * String result = resolver.resolvePlaceholders("user=${DB_USER:root}, pass=<DB_PASS:123456>");
 * }</pre>
 *
 * @author CH
 * @since 2024-12-23
 */
public class CompositePropertyResolver implements PropertyResolver {

    /**
     * 包含的所有具体占位符解析器列表。
     * 解析过程将按顺序依次调用每个解析器。
     */
    private final List<PropertyResolver> resolvers;

    /**
     * 主要的占位符配置支持对象（取第一个解析器的配置）。
     * 用于获取统一的占位符前缀、后缀等元数据信息。
     */
    private final PlaceholderSupport primaryPlaceholderSupport;

    /**
     * 私有构造函数，通过传入的解析器列表初始化实例。
     *
     * @param resolvers 必须非空且至少包含一个解析器
     * @throws IllegalArgumentException 当 resolvers 为 null 或为空时抛出
     */
    private CompositePropertyResolver(List<PropertyResolver> resolvers) {
        if (resolvers == null || resolvers.isEmpty()) {
            throw new IllegalArgumentException("resolvers cannot be null or empty");
        }
        this.resolvers = new ArrayList<>(resolvers);
        // 默认以第一个解析器的配置作为主配置
        this.primaryPlaceholderSupport = resolvers.get(0).getPlaceholderSupport();
    }

    /**
     * 可变参数构造函数，方便直接传入多个解析器实例。
     *
     * @param resolvers 一组具体的占位符解析器
     */
    public CompositePropertyResolver(PropertyResolver... resolvers) {
        this(Arrays.asList(resolvers));
    }

    /**
     * 创建一个构建器实例，用于链式配置和构建复合解析器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建默认的复合解析器，同时支持 ${key:default} 和 &lt;key:default&gt; 格式。
     *
     * @param placeholderResolver 实际的属性值提供者（如从系统属性、环境变量中获取）
     * @return 配置好的复合解析器实例
     */
    public static CompositePropertyResolver createDefault(PlaceholderResolver placeholderResolver) {
        return builder()
                .addResolver("${", "}", ":")
                .addResolver("<", ">", ":")
                .build(placeholderResolver);
    }

    /**
     * 创建默认的复合解析器，并设置忽略无法解析的占位符。
     * 如果某个占位符无法找到对应值，将保留原样而不是抛出异常。
     *
     * @param placeholderResolver 实际的属性值提供者
     * @return 配置好的复合解析器实例
     */
    public static CompositePropertyResolver createDefaultIgnoreUnresolvable(PlaceholderResolver placeholderResolver) {
        return builder()
                .ignoreUnresolvablePlaceholders(true)
                .addResolver("${", "}", ":")
                .addResolver("<", ">", ":")
                .build(placeholderResolver);
    }

    /**
     * 递归解析字符串中的所有占位符。
     * 按照注册顺序依次调用每个解析器，上一个解析器的结果传递给下一个。
     *
     * @param text 待解析的原始文本
     * @return 解析后的文本，若输入为 null 或空则直接返回
     */
    @Override
    public String resolvePlaceholders(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (PropertyResolver resolver : resolvers) {
            result = resolver.resolvePlaceholders(result);
        }
        return result;
    }

    /**
     * 获取主占位符支持配置（即第一个解析器的配置）。
     *
     * @return 主要占位符配置对象
     */
    @Override
    public PlaceholderSupport getPlaceholderSupport() {
        return primaryPlaceholderSupport;
    }

    /**
     * 向所有内部解析器添加一个键值对。
     * 此操作会同步应用到每一个注册的解析器上。
     *
     * @param name  属性名称
     * @param value 属性值
     */
    @Override
    public void add(String name, Object value) {
        for (PropertyResolver resolver : resolvers) {
            resolver.add(name, value);
        }
    }

    /**
     * 从所有内部解析器中移除指定名称的属性。
     *
     * @param name 要移除的属性名称
     */
    @Override
    public void remove(String name) {
        for (PropertyResolver resolver : resolvers) {
            resolver.remove(name);
        }
    }

    /**
     * 为所有内部解析器设置统一的占位符值解析器。
     *
     * @param placeholderResolver 新的占位符值解析器
     */
    @Override
    public void setPlaceholderResolver(PlaceholderResolver placeholderResolver) {
        for (PropertyResolver resolver : resolvers) {
            resolver.setPlaceholderResolver(placeholderResolver);
        }
    }

    /**
     * 获取当前持有的所有解析器列表的副本（只读）。
     *
     * @return 解析器列表
     */
    public List<PropertyResolver> getResolvers() {
        return new ArrayList<>(resolvers);
    }

    /**
     * 动态添加一个新的解析器到列表中。
     * 注意：这不会更新 primaryPlaceholderSupport，它仍指向第一个解析器。
     *
     * @param resolver 要添加的解析器
     * @return 当前实例，支持链式调用
     */
    public CompositePropertyResolver addResolver(PropertyResolver resolver) {
        if (resolver != null) {
            resolvers.add(resolver);
        }
        return this;
    }

    /**
     * 构建器类，用于灵活配置复合解析器。
     */
    public static class Builder {

        /**
         * 存储配置的占位符分隔符规则列表。
         */
        private final List<PlaceholderConfig> configs = new ArrayList<>();

        /**
         * 是否忽略无法解析的占位符。
         * 若为 true，无法匹配的占位符将保持原样；否则可能抛出异常。
         */
        private boolean ignoreUnresolvablePlaceholders = false;

        /**
         * 是否在解析后自动去除值的空白字符。
         */
        private boolean trimValues = true;

        /**
         * 添加一种占位符格式的配置。
         *
         * @param prefix    占位符前缀，例如 "${" 或 "<"
         * @param suffix    占位符后缀，例如 "}" 或 ">"
         * @param separator 键值分隔符，例如 ":"
         * @return 当前构建器实例
         */
        public Builder addResolver(String prefix, String suffix, String separator) {
            configs.add(new PlaceholderConfig(prefix, suffix, separator));
            return this;
        }

        /**
         * 快捷添加标准的美元符号占位符格式 (${key:default})。
         *
         * @return 当前构建器实例
         */
        public Builder addDollarResolver() {
            return addResolver("${", "}", ":");
        }

        /**
         * 快捷添加尖括号占位符格式 (&lt;key:default&gt;)。
         *
         * @return 当前构建器实例
         */
        public Builder addAngleResolver() {
            return addResolver("<", ">", ":");
        }

        /**
         * 设置是否忽略无法解析的占位符。
         *
         * @param ignore 若为 true 则忽略未找到的占位符
         * @return 当前构建器实例
         */
        public Builder ignoreUnresolvablePlaceholders(boolean ignore) {
            this.ignoreUnresolvablePlaceholders = ignore;
            return this;
        }

        /**
         * 设置是否修剪解析后的值（去除首尾空格）。
         *
         * @param trim 若为 true 则修剪空格
         * @return 当前构建器实例
         */
        public Builder trimValues(boolean trim) {
            this.trimValues = trim;
            return this;
        }

        /**
         * 根据配置构建最终的复合解析器实例。
         *
         * @param placeholderResolver 实际的属性值解析器
         * @return 构建完成的 CompositePropertyResolver 实例
         */
        public CompositePropertyResolver build(PlaceholderResolver placeholderResolver) {
            // 如果没有显式添加任何解析器，默认添加美元符号解析器
            if (configs.isEmpty()) {
                addDollarResolver();
            }

            List<PropertyResolver> resolvers = new ArrayList<>();
            for (PlaceholderConfig config : configs) {
                // 为每种格式创建对应的 PlaceholderSupport 配置
                PlaceholderSupport support = new PlaceholderSupport(
                        config.prefix, config.suffix, config.separator);
                support.setIgnoreUnresolvablePlaceholders(ignoreUnresolvablePlaceholders);
                support.setTrimValues(trimValues);
                support.setResolver(placeholderResolver);
                // 包装成具体的字符串值解析器并加入列表
                resolvers.add(new StringValuePropertyResolver(support));
            }

            return new CompositePropertyResolver(resolvers);
        }

        /**
         * 内部静态类，用于封装占位符的格式配置。
         */
        private static class PlaceholderConfig {
            final String prefix;
            final String suffix;
            final String separator;

            PlaceholderConfig(String prefix, String suffix, String separator) {
                this.prefix = prefix;
                this.suffix = suffix;
                this.separator = separator;
            }
        }
    }
}
