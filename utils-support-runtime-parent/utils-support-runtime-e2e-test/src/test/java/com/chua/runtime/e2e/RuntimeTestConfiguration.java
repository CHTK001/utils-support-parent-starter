package com.chua.runtime.e2e;

import com.chua.runtime.apm.ApmBootstrap;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.nio.file.Paths;

/**
 * Spring Boot 测试配置 — 将 ApmBootstrap 注册为 Spring Bean。
 *
 * <p>通过 {@code @SpringBootTest} 加载此配置，可在测试中通过依赖注入获取 ApmBootstrap 实例，
 * 验证各 Handler 在 Spring 容器中的行为。</p>
 *
 * <p>{@code @ComponentScan} 确保同包下的 {@code TestController} 等组件被自动注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpringBootApplication(scanBasePackages = "com.chua.runtime.e2e")
public class RuntimeTestConfiguration {

    private static final String PROP_LEAK_THRESHOLD = "leak.threshold.ms";
    private static final String DEFAULT_LEAK_THRESHOLD = "1000";

    @Bean
    public ApmBootstrap apmBootstrap() {
        String threshold = System.getProperty(PROP_LEAK_THRESHOLD, DEFAULT_LEAK_THRESHOLD);
        System.setProperty(PROP_LEAK_THRESHOLD, threshold);
        ApmBootstrap apm = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
        apm.start();
        return apm;
    }
}