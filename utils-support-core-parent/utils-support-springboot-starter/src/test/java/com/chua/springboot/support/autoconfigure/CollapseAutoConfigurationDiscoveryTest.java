package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.spring.support.aop.CollapsibleAdvisor;
import com.chua.spring.support.proxy.intercept.CollapsibleIntercept;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AutoConfiguration.imports} 自动发现路径端到端验证：
 * [A] 注册文件存在于产物 classpath 且含折叠自动配置全限定名；
 * [B] 真实 SpringApplication 启动（非手动 @Import）能自动发现并装配折叠 Bean、绑定全局配置。
 *
 * <p>[B] 不排除任何自动配置：同时证明完整自动装配链（含 datasource 依赖的
 * {@code ConcurrentEndpoint} 等）在无 datasource 环境下可正常启动（仓储缺失时优雅降级）。</p>
 *
 * @author CH
 * @since 2026/09/04
 */
class CollapseAutoConfigurationDiscoveryTest {

    /**
     * 最小 Boot 应用（启动时经 AutoConfiguration.imports 自动发现折叠自动配置）
     */
    @SpringBootApplication
    static class TestApplication {
    }

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /**
     * [A] imports 注册文件存在于 classpath 且含折叠自动配置
     */
    @Test
    void importsFileRegistersCollapseAutoConfiguration() throws Exception {
        try (InputStream in = CollapseAutoConfigurationDiscoveryTest.class.getClassLoader()
                .getResourceAsStream(IMPORTS_RESOURCE)) {
            assertNotNull(in, IMPORTS_RESOURCE + " 应存在于产物 classpath");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("com.chua.springboot.support.autoconfigure.CollapseAutoConfiguration"),
                    "imports 文件应注册折叠自动配置类");
        }
    }

    /**
     * [B] 真 SpringApplication 启动自动发现折叠自动配置并绑定全局配置（无自动配置排除）
     */
    @Test
    void springApplicationDiscoversAndWiresCollapse() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .properties("collapse.executor.wait-threshold=2")
                .run()) {
            assertNotNull(ctx.getBean(CollapsibleAdvisor.class), "折叠 Advisor 应被自动装配创建");
            CollapsibleIntercept intercept = ctx.getBean(CollapsibleIntercept.class);
            Field field = CollapsibleIntercept.class.getDeclaredField("globalDefaults");
            field.setAccessible(true);
            CollapseConfig global = (CollapseConfig) field.get(intercept);
            assertEquals(2, global.getWaitThreshold(),
                    "SpringApplication 启动应经 CollapseProperties 绑定 collapse.executor.wait-threshold=2");
        }
    }
}
