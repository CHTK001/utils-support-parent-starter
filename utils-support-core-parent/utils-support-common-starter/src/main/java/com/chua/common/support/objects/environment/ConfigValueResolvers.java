package com.chua.common.support.objects.environment;

import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 配置值表达式解析工具，按 SPI 链尝试解析表达式。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public final class ConfigValueResolvers {

    /** Resolvers */
    private static final List<ConfigValueExpressionResolver> RESOLVERS;

    static {
        List<ConfigValueExpressionResolver> list;
        try {
            list = ServiceProvider.of(ConfigValueExpressionResolver.class).collect();
        } catch (Exception e) {
            list = List.of();
        }
        RESOLVERS = list;
    }

    /** 创建 ConfigValueResolvers 实例 */
    private ConfigValueResolvers() {
    }

    /**
     * 解析配置值表达式。
     *
     * <p>按 SPI 链遍历，首个 {@link ConfigValueExpressionResolver#isSupport} 返回 true 的解析器负责处理。</p>
     *
     * @param expression  原始表达式
     * @param targetType  目标类型
     * @param environment 环境配置
     * @param <T>         泛型类型
     * @return 解析后的值，不支持或无法解析返回 null
     */
    public static <T> T resolve(String expression, Class<T> targetType, Environment environment) {
        if (expression == null || environment == null) {
            return null;
        }
        for (ConfigValueExpressionResolver resolver : RESOLVERS) {
            if (resolver.isSupport(expression)) {
                try {
                    return resolver.resolve(expression, targetType, environment);
                } catch (Exception e) {
                    log.warn("表达式解析失败: resolver={}, expression={}", resolver.getClass().getSimpleName(), expression, e);
                }
            }
        }
        return null;
    }
}
