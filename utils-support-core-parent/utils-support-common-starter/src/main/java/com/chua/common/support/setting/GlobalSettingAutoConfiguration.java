package com.chua.common.support.setting;

import com.chua.common.support.application.GlobalSettingFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 全局配置自动注册器。
 * <p>
 * 扫描所有标注了 {@link GlobalSettingGroup} 的 Bean 类，
 * 由 {@link GlobalSettingFactory} 自动注册。无需在 SysSettingServiceImpl 中硬编码。
 * </p>
 *
 * @author CH
 * @since 2024/8/13
 */
@Component
public class GlobalSettingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GlobalSettingAutoConfiguration.class);

    private final ApplicationContext applicationContext;

    public GlobalSettingAutoConfiguration(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 应用启动完成后扫描 @GlobalSettingGroup 注解并注册 Bean。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void registerGlobalSettings() {
        // 仅当 GlobalSettingFactory 已注册为 Bean 时执行（utils-support-common-starter 模块）
        if (!applicationContext.containsBean("globalSettingFactory")) {
            return;
        }

        GlobalSettingFactory factory = GlobalSettingFactory.getInstance();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(GlobalSettingGroup.class));

        // 默认扫描基包：SpringBootApplication 所在包
        String basePackage = getBasePackage();
        if (basePackage == null) {
            log.debug("[GlobalSettingAutoConfiguration] 未找到基础包，跳过扫描");
            return;
        }

        Set<BeanDefinition> candidates = scanner.findCandidateComponents(basePackage);
        for (BeanDefinition def : candidates) {
            try {
                Class<?> clazz = Class.forName(def.getBeanClassName());
                GlobalSettingGroup annotation = clazz.getAnnotation(GlobalSettingGroup.class);
                if (annotation == null) {
                    continue;
                }
                Object bean;
                String[] beanNames = applicationContext.getBeanNamesForType(clazz);
                if (beanNames.length > 0) {
                    // Bean 已注册到 Spring 容器，优先复用
                    bean = applicationContext.getBean(beanNames[0]);
                } else {
                    // Bean 未注册到 Spring 容器（无 @Component），通过反射实例化
                    bean = clazz.getDeclaredConstructor().newInstance();
                }
                factory.register(annotation.value(), bean, annotation.enabled());
                log.info("[GlobalSettingAutoConfiguration] 注册配置分组: group={}, bean={}",
                        annotation.value(), clazz.getSimpleName());
            } catch (Exception e) {
                log.warn("[GlobalSettingAutoConfiguration] 注册失败: class={}, error={}",
                        def.getBeanClassName(), e.getMessage());
            }
        }
    }

    private String getBasePackage() {
        try {
            String[] names = applicationContext.getBeanDefinitionNames();
            for (String name : names) {
                try {
                    Object bean = applicationContext.getBean(name);
                    String pkg = bean.getClass().getPackageName();
                    if (pkg != null && !pkg.isEmpty() && !pkg.startsWith("org.springframework")) {
                        return pkg;
                    }
                } catch (Exception ignored) {
                    // 非单例/抽象 Bean 跳过
                }
            }
        } catch (Exception e) {
            log.debug("[GlobalSettingAutoConfiguration] 获取基础包失败: {}", e.getMessage());
        }
        return null;
    }
}
