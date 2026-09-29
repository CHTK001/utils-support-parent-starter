package com.chua.common.support.task.script;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 脚本引擎发现入口（全局唯一）。
 *
 * <p>上层只认本类，不直接触碰具体发现机制：默认用仓库自带的 {@code ServiceProvider} SPI，
 * 宿主可调用 {@link #useResolver(ScriptProviderResolver)} 换成自己的实现
 * （Spring 宿主由 {@code utils-support-spring-starter} 换成 {@code SpringFactoriesLoader}）。
 * 这样「引擎在哪」只有一处配置，避免各模块各写一套查找逻辑。</p>
 *
 * <p>本类不依赖 Spring，因此纯 JDK 场景（单测、命令行）也能直接用。</p>
 *
 * @since 4.0.0.43
 * @author CH
 */
public final class ScriptProviders {

    /**
     * 当前生效的发现实现；未替换时用仓库自带 SPI 兜底
     */
    private static volatile ScriptProviderResolver resolver = DefaultResolver.INSTANCE;

    /**
     * 创建 脚本引擎发现入口 实例
     */
    private ScriptProviders() {
    }

    /**
     * 替换发现实现（宿主启动时调用一次）。
     *
     * @param custom 自定义实现；传 null 表示回到默认实现
     */
    public static void useResolver(ScriptProviderResolver custom) {
        resolver = custom == null ? DefaultResolver.INSTANCE : custom;
    }

    /**
     * 取当前发现实现。
     *
     * @return 发现实现，永不为 null
     */
    public static ScriptProviderResolver resolver() {
        return resolver;
    }

    /**
     * 按引擎名取脚本引擎实例。
     *
     * @param engine 引擎名，允许为 null/空白
     * @return 引擎实例；未注册或引擎名为空返回 null
     */
    public static ScriptProvider resolve(String engine) {
        if (engine == null || engine.isBlank()) {
            return null;
        }
        return resolver.resolve(engine.trim());
    }

    /**
     * 枚举全部已注册引擎名。
     *
     * @return 去重后的引擎名集合，无实现时为空集合
     */
    public static Set<String> engines() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(resolver.engines()));
    }

    /**
     * 默认发现实现：走仓库自带的 {@code ServiceProvider} SPI。
     *
     * <p>保留它的意义是向后兼容：非 Spring 场景（单测、命令行工具）仍然可用，
     * 不需要为了找引擎而引入 Spring。</p>
     */
    private static final class DefaultResolver implements ScriptProviderResolver {

        /**
         * 无状态单例
         */
        private static final DefaultResolver INSTANCE = new DefaultResolver();

        /**
         * 创建 默认发现实现 实例
         */
        private DefaultResolver() {
        }

        /**
         * 按引擎名取脚本引擎实例。
         *
         * @param engine 引擎名
         * @return 引擎实例；未注册返回 null
         */
        @Override
        public ScriptProvider resolve(String engine) {
            try {
                return ServiceProvider.of(ScriptProvider.class).getExtension(engine);
            } catch (Exception ex) {
                return null;
            }
        }

        /**
         * 枚举全部已注册引擎名。
         *
         * @return 引擎名集合
         */
        @Override
        public Set<String> engines() {
            try {
                Set<String> extensions = ServiceProvider.of(ScriptProvider.class).getExtensions();
                return extensions == null ? Set.of() : extensions;
            } catch (Exception ex) {
                return Set.of();
            }
        }
    }
}
