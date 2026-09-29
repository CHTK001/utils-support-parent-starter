package com.chua.spring.support.script;

import com.chua.common.support.task.script.ScriptProvider;
import com.chua.common.support.task.script.ScriptProviderResolver;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Spring 自带 {@link SpringFactoriesLoader} 的脚本引擎发现实现。
 *
 * <p>从 {@code META-INF/spring.factories} 读取 {@code ScriptProvider} 的全部实现，
 * 按 {@link ScriptProvider#engineName()} 建索引；索引惰性构建且实例缓存复用，保证
 * 「检测引擎」与「执行引擎」拿到的是同一个对象。非 Spring 场景仍由
 * {@code utils-support-common-starter} 的默认 {@code ServiceProvider} 发现兜底。</p>
 *
 * <p>本类由 {@code ScriptProviderSpringRegistrar} 在 Spring 容器初始化时注册到
 * {@code ScriptProviders}。</p>
 *
 * @author CH
 * @since 4.0.0.43
 * @see SpringFactoriesLoader
 */
public class SpringScriptProviderResolver implements ScriptProviderResolver {

    /**
     * 引擎名 -> 引擎实例 的惰性缓存（volatile + 双重检查，构建后只读）
     */
    private volatile Map<String, ScriptProvider> providers;

    /**
     * 按引擎名取脚本引擎实例。
     *
     * @param engine 引擎名，允许为 null
     * @return 命中的引擎实例；未注册返回 null
     */
    @Override
    public ScriptProvider resolve(String engine) {
        if (engine == null) {
            return null;
        }
        return providerMap().get(engine);
    }

    /**
     * 枚举全部已注册引擎名。
     *
     * @return 引擎名集合，无实现时为空集合
     */
    @Override
    public Set<String> engines() {
        return providerMap().keySet();
    }

    /**
     * 取惰性构建的引擎索引。
     *
     * @return 引擎名到实例的只读映射
     */
    private Map<String, ScriptProvider> providerMap() {
        Map<String, ScriptProvider> local = providers;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (providers == null) {
                providers = loadProviders();
            }
            return providers;
        }
    }

    /**
     * 通过 SpringFactoriesLoader 加载并索引全部引擎实现。
     *
     * @return 引擎名到实例的只读映射
     */
    private Map<String, ScriptProvider> loadProviders() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }
        List<ScriptProvider> factories = SpringFactoriesLoader.loadFactories(ScriptProvider.class, classLoader);
        Map<String, ScriptProvider> result = new LinkedHashMap<>(factories.size());
        for (ScriptProvider provider : factories) {
            String name = provider.engineName();
            if (name != null && !name.isBlank()) {
                result.putIfAbsent(name.trim(), provider);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
