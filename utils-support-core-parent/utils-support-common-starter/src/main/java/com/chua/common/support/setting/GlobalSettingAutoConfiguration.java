package com.chua.common.support.setting;

import com.chua.common.support.application.GlobalSettingFactory;
import com.chua.common.support.utils.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
@AutoConfiguration(after = GlobalSettingFactoryAutoConfiguration.class)
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

        List<String> basePackages = getBasePackages();
        if (basePackages.isEmpty()) {
            log.debug("[GlobalSettingAutoConfiguration] 未找到基础包，跳过扫描");
            return;
        }

        log.info("[GlobalSettingAutoConfiguration] 扫描基础包: {}", basePackages);
        for (String basePackage : basePackages) {
            try {
                scanAndRegister(factory, scanner, basePackage);
            } catch (Exception e) {
                log.warn("[GlobalSettingAutoConfiguration] 扫描包失败: package={}, error={}",
                        basePackage, e.getMessage());
            }
        }
    }

    /**
     * 扫描单个基础包下的所有 @GlobalSettingGroup 注解类。
     */
    private void scanAndRegister(GlobalSettingFactory factory,
                                  ClassPathScanningCandidateComponentProvider scanner,
                                  String basePackage) {
        Set<BeanDefinition> candidates = scanner.findCandidateComponents(basePackage);
        for (BeanDefinition def : candidates) {
            try {
                Class<?> clazz = Class.forName(def.getBeanClassName());
                GlobalSettingGroup annotation = clazz.getAnnotation(GlobalSettingGroup.class);
                if (annotation == null) {
                    continue;
                }
                Object bean = obtainBean(clazz);
                factory.register(annotation.value(), bean, annotation.enabled());
                log.info("[GlobalSettingAutoConfiguration] 注册配置分组: group={}, bean={}",
                        annotation.value(), clazz.getSimpleName());
            } catch (Exception e) {
                log.warn("[GlobalSettingAutoConfiguration] 注册失败: class={}, error={}",
                        def.getBeanClassName(), e.getMessage());
            }
        }
    }

    /**
     * 获取 Bean 实例：优先从 Spring 容器取，无则用 {@link ClassUtils#forObject} 反射实例化。
     */
    private Object obtainBean(Class<?> clazz) {
        String[] beanNames = applicationContext.getBeanNamesForType(clazz);
        if (beanNames.length > 0) {
            // Spring 容器已有（如 @Component 标注），优先复用
            return applicationContext.getBean(beanNames[0]);
        }
        // 无 Spring 托管，使用 ClassUtils 反射实例化（支持构造参数推断）
        return ClassUtils.forObject(clazz);
    }

    /**
     * 获取扫描的基础包列表。
     * <p>
     * 优先级：{@code @SpringBootApplication} 注解所在包 > {@code spring.components} 配置。
     * 使用 Spring Boot 标准的 {@link AutoConfigurationPackages} 获取主应用基包，
     * 而非遍历所有 Bean（之前的方法会拿到第一个 Bean 的包名，导致跨包扫描失败）。
     * </p>
     */
    private List<String> getBasePackages() {
        try {
            List<String> packages = AutoConfigurationPackages.get(applicationContext);
            if (!packages.isEmpty()) {
                return packages;
            }
        } catch (Exception e) {
            log.debug("[GlobalSettingAutoConfiguration] AutoConfigurationPackages 获取失败，回退到 @SpringBootApplication 扫描: {}",
                    e.getMessage());
        }

        // 回退方案：从 @SpringBootApplication 注解推断基础包
        try {
            Map<String, Object> beans = applicationContext.getBeansWithAnnotation(
                    org.springframework.boot.autoconfigure.SpringBootApplication.class);
            if (!beans.isEmpty()) {
                Object app = beans.values().iterator().next();
                String pkg = app.getClass().getPackageName();
                if (pkg != null && !pkg.isEmpty()) {
                    return Collections.singletonList(pkg);
                }
            }
        } catch (Exception e) {
            log.debug("[GlobalSettingAutoConfiguration] @SpringBootApplication 回退失败: {}", e.getMessage());
        }

        return Collections.emptyList();
    }
}
