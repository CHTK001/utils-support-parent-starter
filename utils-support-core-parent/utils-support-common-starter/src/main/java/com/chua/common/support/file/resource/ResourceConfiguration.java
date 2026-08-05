package com.chua.common.support.file.resource;

import com.chua.common.support.function.SafeConsumer;
import com.chua.common.support.matcher.AntPathMatcher;
import com.chua.common.support.matcher.PathMatcher;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 资源查找配置。
 *
 * <p>封装查找器运行所需的全部上下文：类加载器、路径匹配器、排除规则、并行策略以及
 * 资源命中时的回调。通过 Lombok {@link Builder} 提供流式构造：</p>
 *
 * <pre>
 * ResourceConfiguration config = ResourceConfiguration.builder()
 *     .isParallel(true)
 *     .excludes("*.class")
 *     .build();
 * Set&lt;Resource&gt; resources = ResourceFlow.of("classpath*:config/*.yml", config).getResources();
 * </pre>
 *
 * @author CH
 * @since 1.0.0
 */
@Builder
@Data
public class ResourceConfiguration {

    /**
     * 默认配置实例，使用上下文类加载器与 Ant 路径匹配器，无排除规则、非并行、无回调。
     */
    public static final ResourceConfiguration DEFAULT = ResourceConfiguration.builder().build();

    /**
     * 类加载器，用于 {@code classpath:} / {@code classpath*:} 资源定位。
     */
    @Builder.Default
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    /**
     * 路径匹配器，默认使用 {@link AntPathMatcher}。
     */
    @Builder.Default
    private PathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 排除规则集合，匹配的资源将被过滤。
     */
    @Singular("excludes")
    private Set<String> excludes;

    /**
     * 是否启用并行扫描。
     */
    private boolean isParallel;

    /**
     * 资源命中时的回调消费者。
     */
    private Consumer<Resource> consumer;

    /**
     * 获取资源回调，若未配置则返回空操作消费者。
     *
     * @return 资源回调消费者
     */
    public Consumer<Resource> getConsumer() {
        return consumer != null ? consumer : (SafeConsumer<Resource>) resource -> {
        };
    }
}
