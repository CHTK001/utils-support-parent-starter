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
 * 自动创建 {@link SpringBootObjectContext} 并注册为 Spring Bean，提供：
 * <ul>
 *   <li>将 Spring ApplicationContext 的 Bean 管理能力桥接到 ObjectContext 体系</li>
 *   <li>支持 SPI 发现、注解扫描、包扫描</li>
 *   <li>外部容器（OSGi、远程节点等）可通过
 *       {@link ObjectContext#registerBean(com.chua.common.support.objects.register.BeanDefinitionRegister)}
 *       自行挂接</li>
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
     * 桥接 Spring ApplicationContext 与核心容器。</p>
     *
     * @param applicationContext Spring 应用上下文
     * @return SpringBootObjectContext 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SpringBootObjectContext springBootObjectContext(ApplicationContext applicationContext) {
        log.info("[springboot-context] 创建 SpringBootObjectContext...");

        ObjectContextConfig config = ObjectContextConfig.defaults();
        SpringBootObjectContext context = new SpringBootObjectContext(applicationContext, config);

        log.info("[springboot-context] SpringBootObjectContext 已创建，Bean总数: {}",
                context.getBeanDefinitionCount());

        return context;
    }
}