package com.chua.springboot.support.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 将外部对象注册到 Spring 容器中的桥接工具。
 *
 * <p>仅在 {@link ConfigurableApplicationContext} 可用时生效：
 * 通过 {@link DefaultListableBeanFactory#registerSingleton(String, Object)}
 * 将对象以单例形式加入 Spring 容器，后续 {@code @Autowired} 可正常解析。</p>
 *
 * <p>已注册的 beanName 会在内部缓存中记录，避免重复注册。</p>
 *
 * @author CH
 * @since 2026/07/29
 */
@Slf4j
final class SpringObjectContextBridge {

    /**
     * 已注册到 Spring 的 beanName 缓存，key = beanName，value = bean 实例引用
     */
    private static final ConcurrentMap<String, Object> REGISTERED = new ConcurrentHashMap<>();

    private SpringObjectContextBridge() {
    }

    /**
     * 若 Spring 容器中尚未注册该对象，则以单例形式注册。
     * <p>beanName 默认取小驼峰类名（与 {@code TypeBeanDefinition} 命名一致）。</p>
     *
     * @param applicationContext Spring 容器
     * @param bean               待注册对象
     */
    public static void registerIfAbsent(ApplicationContext applicationContext, Object bean) {
        if (applicationContext == null || bean == null) {
            return;
        }
        String beanName = resolveBeanName(bean.getClass());
        if (applicationContext.containsBean(beanName)) {
            return;
        }
        if (!(applicationContext instanceof ConfigurableApplicationContext configurable)) {
            return;
        }
        DefaultListableBeanFactory factory = (DefaultListableBeanFactory) configurable.getAutowireCapableBeanFactory();
        if (factory.containsBean(beanName)) {
            return;
        }
        Object previous = REGISTERED.putIfAbsent(beanName, bean);
        if (previous != null) {
            return;
        }
        try {
            // 注册单例，使 Spring 后续可通过 getBean / @Autowired 获取
            ((SingletonBeanRegistry) factory).registerSingleton(beanName, bean);
            // 注册 RootBeanDefinition，使 getBeanDefinitionNames / getBeanDefinitionCount 等元数据接口可用
            RootBeanDefinition bd = new RootBeanDefinition(bean.getClass());
            bd.setScope(org.springframework.beans.factory.config.BeanDefinition.SCOPE_SINGLETON);
            bd.setAutowireCandidate(true);
            factory.registerBeanDefinition(beanName, bd);
            log.debug("[SpringObjectContextBridge] 已同步注册到 Spring: {}", beanName);
        } catch (Exception e) {
            REGISTERED.remove(beanName);
            log.trace("[SpringObjectContextBridge] 注册失败: {}", beanName, e);
        }
    }

    /**
     * 解析默认 beanName：小驼峰类名（首字母小写），与 {@code TypeBeanDefinition.of} 保持一致。
     *
     * @param beanClass Bean 类型
     * @return beanName
     */
    private static String resolveBeanName(Class<?> beanClass) {
        if (beanClass == null) {
            return null;
        }
        String simpleName = beanClass.getSimpleName();
        if (simpleName.isEmpty()) {
            return beanClass.getName();
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}