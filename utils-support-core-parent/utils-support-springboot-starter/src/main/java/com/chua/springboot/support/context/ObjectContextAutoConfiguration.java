package com.chua.springboot.support.context;

import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;
import com.chua.springboot.support.autoconfigure.UtilsSpringBootAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * ObjectContext 自动配置 — 在 Spring Boot 容器中创建 {@link SpringBootObjectContext}。
 *
 * <p>依赖 {@link UtilsSpringBootAutoConfiguration} 完成 ApplicationContext 注入后，
 * 自动创建 {@link SpringBootObjectContext} 并注册为 Spring Bean，同时：
 * <ul>
 *   <li>集成 OSGI 模块上下文（如果 classpath 存在 OSGI 实现）</li>
 *   <li>将 Spring ApplicationContext 的 Bean 管理能力桥接到 ObjectContext 体系</li>
 *   <li>支持 SPI 发现、注解扫描、包扫描</li>
 * </ul>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * object-context:
 *   spi-enabled: true
 *   annotation-scan-enabled: false
 *   scan-packages:
 *     - com.example.app
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@AutoConfiguration(after = UtilsSpringBootAutoConfiguration.class)
@ConditionalOnClass(ObjectContext.class)
public class ObjectContextAutoConfiguration {

    /**
     * 创建 SpringBootObjectContext Bean。
     *
     * <p>自动读取 application.yml 中的 object-context.* 配置，
     * 集成 Spring ApplicationContext 与 OSGI 模块上下文。
     *
     * @param applicationContext Spring 应用上下文
     * @return SpringBootObjectContext 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SpringBootObjectContext springBootObjectContext(ApplicationContext applicationContext) {
        log.info("[ObjectContextAutoConfiguration] 创建 SpringBootObjectContext...");

        ObjectContextConfig config = ObjectContextConfig.defaults();
        SpringBootObjectContext context = new SpringBootObjectContext(applicationContext, config);

        log.info("[ObjectContextAutoConfiguration] SpringBootObjectContext 已创建，"
                + "Bean总数: {}, OSGI: {}",
                context.getBeanDefinitionCount(),
                context.isOsgiEnabled() ? "已激活" : "未启用");

        return context;
    }
}