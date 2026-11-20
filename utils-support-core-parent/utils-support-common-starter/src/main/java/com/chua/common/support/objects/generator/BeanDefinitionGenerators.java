package com.chua.common.support.objects.generator;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link BeanDefinitionGenerator} SPI 的共享排序缓存。
 *
 * <p>背景：{@code registerBean} 是容器的高频入口，此前每次注册都要执行
 * {@code ServiceProvider.of(...).collect()}（重建实例映射、过滤空值、包装不可变列表）
 * 再经 stream 排序拷贝一份，产生一整串临时对象。而生成器属于「启动期 SPI」：
 * 实现由 {@code META-INF/services} 在类路径装配阶段确定，运行期不预期动态增删，
 * 因此这里按 {@link ServiceProvider} 实例身份缓存排序后的只读列表——
 * 首次调用完成 SPI 收集与排序，之后每次注册只剩一次哈希查找。</p>
 *
 * <p>缓存以 provider 实例为键：{@link ServiceProvider#of(Class)} 内部缓存被回收重建时，
 * 新实例身份不同，自然触发一次重算，无需额外失效监听；强引用键同时保证
 * 已缓存的 provider 不会被误回收，避免缓存反复失效。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
public final class BeanDefinitionGenerators {

    /**
     * 排序后的生成器列表缓存（键为 ServiceProvider 实例身份）。
     */
    private static final ConcurrentMap<ServiceProvider<?>, List<BeanDefinitionGenerator>> CACHE =
            new ConcurrentHashMap<>();

    /**
     * 工具类，禁止实例化。
     */
    private BeanDefinitionGenerators() {
    }

    /**
     * 获取按优先级降序排列的全部 BeanDefinitionGenerator。
     *
     * <p>结果只读且跨调用共享，调用方不得修改返回列表。</p>
     *
     * @return 生成器列表（非 null，不可变）
     */
    public static List<BeanDefinitionGenerator> sorted() {
        ServiceProvider<BeanDefinitionGenerator> provider = ServiceProvider.of(BeanDefinitionGenerator.class);
        return CACHE.computeIfAbsent(provider, p -> provider.collect().stream()
                .sorted(Comparator.comparingInt(BeanDefinitionGenerator::getPriority).reversed())
                .toList());
    }
}
