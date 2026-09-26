package com.chua.common.support.rule.decision;

import com.chua.common.support.rule.RuleException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 决策实现注册表。
 *
 * <p>解析顺序：<b>显式注册的实现优先</b>，其次是 SPI 自动发现的实现，
 * 最后兜底到内置的 {@link DecisionTableProvider}。这样使用方既能替换行为，
 * 又能在什么都不做的情况下直接用决策表。</p>
 *
 * <p>SPI 声明文件：
 * {@code META-INF/extensions/com.chua.common.support.rule.decision.RuleDecisionProvider}，
 * 每行一个实现类全名。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class DecisionRegistry {

    /**
     * 决策实现 SPI 的扩展点全名
     */
    private static final String EXTENSION_POINT =
            "com.chua.common.support.rule.decision.RuleDecisionProvider";

    /**
     * 显式注册的实现
     */
    private final Map<Class<?>, RuleDecisionProvider> providers = new ConcurrentHashMap<>();

    /**
     * SPI 发现的实现，惰性解析
     */
    private volatile List<RuleDecisionProvider> discovered;

    /**
     * 兜底实现：内置决策表
     */
    private volatile RuleDecisionProvider fallback = DecisionTableProvider.INSTANCE;

    /**
     * 创建注册表。
     */
    public DecisionRegistry() {
    }

    /**
     * 创建只含内置决策表实现的注册表。
     *
     * @return 注册表
     */
    public static DecisionRegistry createDefault() {
        return new DecisionRegistry();
    }

    /**
     * 注册一个决策实现。
     *
     * @param provider 实现
     * @return 当前注册表
     */
    public DecisionRegistry register(RuleDecisionProvider provider) {
        if (provider == null) {
            throw new RuleException("决策实现不能为 null");
        }
        providers.put(provider.getClass(), provider);
        return this;
    }

    /**
     * 覆盖兜底实现。
     *
     * <p>兜底实现只在没有任何显式/SPI 实现能处理该决策表时使用。</p>
     *
     * @param fallback 兜底实现，null 表示不启用兜底
     * @return 当前注册表
     */
    public DecisionRegistry fallback(RuleDecisionProvider fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
     * 选出能处理该表的实现。
     *
     * @param table 决策表
     * @return 实现
     * @throws RuleException 没有任何实现能处理时抛出
     */
    public RuleDecisionProvider resolve(DecisionTable table) {
        for (RuleDecisionProvider provider : providers.values()) {
            if (provider.supports(table)) {
                return provider;
            }
        }
        for (RuleDecisionProvider provider : discover()) {
            if (provider.supports(table)) {
                return provider;
            }
        }
        RuleDecisionProvider last = fallback;
        if (last != null && last.supports(table)) {
            return last;
        }
        throw new RuleException("决策表[" + table.id() + "] 没有可用的决策实现；"
                + "请注册 " + EXTENSION_POINT + " 的实现，"
                + "或确认决策表 kind 是否被某个实现支持");
    }

    /**
     * 解析 SPI 实现，结果缓存。
     *
     * @return SPI 实现列表
     */
    private List<RuleDecisionProvider> discover() {
        List<RuleDecisionProvider> cached = discovered;
        if (cached != null) {
            return cached;
        }
        List<RuleDecisionProvider> found = new CopyOnWriteArrayList<>();
        try {
            ClassLoader loader = DecisionRegistry.class.getClassLoader();
            java.util.Enumeration<java.net.URL> resources =
                    loader.getResources("META-INF/extensions/" + EXTENSION_POINT);
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                for (String className : readAll(url)) {
                    try {
                        Class<?> type = Class.forName(className, true, loader);
                        found.add((RuleDecisionProvider) type.getDeclaredConstructor().newInstance());
                    } catch (ReflectiveOperationException | ClassCastException e) {
                        throw new RuleException("决策实现 SPI 声明无效：" + className, e);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new RuleException("读取决策实现 SPI 声明失败", e);
        }
        List<RuleDecisionProvider> immutable = List.copyOf(found);
        discovered = immutable;
        return immutable;
    }

    /**
     * 读取扩展声明文件内容。
     *
     * @param url 声明文件地址
     * @return 非空行组成的列表
     * @throws java.io.IOException 读取失败时抛出
     */
    private static java.util.List<String> readAll(java.net.URL url) throws java.io.IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(url.openStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        }
        return lines;
    }
}
