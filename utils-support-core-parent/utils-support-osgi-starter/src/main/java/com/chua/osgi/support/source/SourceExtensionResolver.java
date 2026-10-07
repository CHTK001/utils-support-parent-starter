package com.chua.osgi.support.source;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.resolver.ServiceResolver;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源码扩展点解析器：把编译产物中的 {@link SourceExtensionFactory} 声明翻译为
 * 可注销的 SPI 服务定义。
 *
 * <p><b>为什么必须是 {@link ServiceResolver} 而不是直接 register(name, 实例)</b>：
 * {@code ServiceProvider#register(String, Object)} 建出的 {@link ServiceDefinition}
 * <b>不设置 finderType</b>，而 {@code unregister} 的匹配逻辑
 * （{@code DefaultServiceProvider#doRegisterRemoveCollection}）第一步就是
 * {@code resolverType.isAssignableFrom(finderType)}——finderType 为 null 时永远不命中。
 * 结果就是：直接注册的扩展点<b>无法注销</b>，热重载会不断堆积同名定义。</p>
 *
 * <p>而 {@link ServiceDefinition#createSingle} 会设置 finderType，配合本类作为
 * {@code resolverType} 传入 {@code unregister(扩展名, SourceExtensionResolver.class)}
 * 即可精确移除。同时本类的 {@link #isDynamic()} 返回 {@code true}，
 * 与该 SPI 对动态解析器的语义约定一致。</p>
 *
 * <p>注册采用<b>替换</b>语义：同名扩展点后注册者覆盖先注册者，
 * 由 {@link #resolve} 内部按名称去重实现，避免热重载后表里同时存在新旧实例。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SourceExtensionResolver implements ServiceResolver {

    /**
     * 本轮要发布的扩展点声明，按 SPI 类型分桶。
     *
     * <p>用 {@link LinkedHashMap} 保持注册顺序稳定，便于日志与测试断言。</p>
     */
    private final Map<Class<?>, List<SourceExtensionFactory>> factories = Wraps.linkedMap();

    /**
     * 发布一批扩展点声明。
     *
     * <p>整体替换而非增量合并：源码热重载要求「源码里删掉的扩展点也消失」，
     * 合并语义会让已删除的扩展点永久残留。</p>
     *
     * @param newFactories 新的扩展点声明列表
     */
    public void replaceAll(List<SourceExtensionFactory> newFactories) {
        factories.clear();
        for (SourceExtensionFactory factory : newFactories) {
            factories.computeIfAbsent(factory.spiType(), k -> Wraps.list()).add(factory);
        }
        log.debug("[osgi-source] 解析器已装载 {} 个扩展点声明，覆盖 {} 种 SPI 类型",
                newFactories.size(), factories.size());
    }

    @Override
    public List<ServiceDefinition> resolve(Class<?> type, ClassLoader classLoader) {
        List<SourceExtensionFactory> declared = factories.get(type);
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        List<ServiceDefinition> result = Wraps.list(declared.size());
        for (SourceExtensionFactory factory : declared) {
            if (factory.extensionName() == null || factory.extensionName().isBlank()) {
                log.warn("[osgi-source] 跳过扩展名空白的声明: spiType={}", type.getName());
                continue;
            }
            try {
                Object instance = factory.create();
                if (instance == null) {
                    continue;
                }
                ServiceDefinition definition = new ServiceDefinition();
                definition.setName(factory.extensionName().toUpperCase());
                definition.setImplClass(instance.getClass());
                definition.setType(type);
                definition.setClassLoader(classLoader);
                definition.setObj(instance);
                definition.setLoaded(true);
                definition.setLoadTime(System.currentTimeMillis());
                // finderType 必须是「解析器类自身」，与 OsgiServiceResolver 保持一致。
                // 注销逻辑 DefaultServiceProvider#doRegisterRemoveCollection 判的是
                // resolverType.isAssignableFrom(finderType)，若 finderType 填业务 SPI 接口，
                // 两侧类型毫无继承关系，该判断恒为 false —— 扩展点永远摘不掉，
                // 表现为「热重载后新代码不生效」且不报错。
                definition.setFinderType(SourceExtensionResolver.class);
                result.add(definition);
            } catch (Exception e) {
                log.error("[osgi-source] 创建扩展点实例失败: {}#{}",
                        type.getSimpleName(), factory.extensionName(), e);
            }
        }
        return result;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
