package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.spring.support.aop.CollapsibleAdvisor;
import com.chua.spring.support.annotation.Collapsible;
import com.chua.spring.support.proxy.intercept.CollapsibleIntercept;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollapseAutoConfiguration} 装配回归测试：
 * [A] 折叠两个 Bean 被自动装配创建（条件评估生效）；
 * [B] {@code collapse.executor.wait-threshold=2} 经 {@link com.chua.springboot.support.properties.CollapseProperties}
 *     绑定后注入 {@link CollapsibleIntercept}（注解未指定阈值时生效）；
 * [C] 自动装配上下文内折叠能力真实生效。
 *
 * @author CH
 * @since 2026/09/04
 */
class CollapseAutoConfigurationTest {

    /**
     * 模拟 Boot 自动装配：导入折叠自动配置类 + 启用 AOP（Boot 中由 AopAutoConfiguration 提供）
     */
    @Configuration
    @EnableAspectJAutoProxy
    @Import(CollapseAutoConfiguration.class)
    static class TestConfig {
    }

    /**
     * 折叠目标 service（不显式指定阈值 -> 走全局默认）
     */
    @Component
    static class UserService {

        private final AtomicInteger calls = new AtomicInteger();

        @Collapsible(name = "boot-wiring", collectingWaitTime = 30)
        public Map<Long, String> batchFind(List<Long> ids) {
            calls.incrementAndGet();
            Map<Long, String> result = new LinkedHashMap<>();
            for (Long id : ids) {
                result.put(id, "u" + id);
            }
            return result;
        }

        public int getCallCount() {
            return calls.get();
        }
    }

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        // 模拟 application.yaml: collapse.executor.wait-threshold=2
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("collapse-test",
                        Collections.singletonMap("collapse.executor.wait-threshold", "2")));
        context.register(TestConfig.class, UserService.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    /**
     * [A] 折叠 Bean 被自动装配创建
     */
    @Test
    void autoConfigurationCreatesCollapseBeans() {
        assertNotNull(context.getBean(CollapsibleAdvisor.class));
        assertNotNull(context.getBean(CollapsibleIntercept.class));
    }

    /**
     * [B] 全局配置绑定并注入拦截器（注解未指定阈值时生效）
     */
    @Test
    void globalDefaultBoundAndInjectedIntoIntercept() throws Exception {
        CollapsibleIntercept intercept = context.getBean(CollapsibleIntercept.class);
        Field field = CollapsibleIntercept.class.getDeclaredField("globalDefaults");
        field.setAccessible(true);
        CollapseConfig global = (CollapseConfig) field.get(intercept);
        assertEquals(2, global.getWaitThreshold(), "collapse.executor.wait-threshold=2 应绑定并注入拦截器");
    }

    /**
     * [C] 自动装配上下文内折叠能力真实生效（4 并发 -> 合并执行，严格少于调用数）
     */
    @Test
    void collapseWorksInAutoConfiguredContext() throws Exception {
        UserService service = context.getBean(UserService.class);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Map<Long, String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Long> mine = Collections.singletonList((long) i);
            futures.add(pool.submit(() -> {
                barrier.await();
                return service.batchFind(mine);
            }));
        }
        for (int i = 0; i < threads; i++) {
            Map<Long, String> sub = futures.get(i).get(15, TimeUnit.SECONDS);
            assertEquals("u" + i, sub.get((long) i), "子结果应精确回填");
        }
        int calls = service.getCallCount();
        assertTrue(calls >= 1 && calls < threads, "4 并发应合并执行（严格少于调用数），实际 " + calls);
        pool.shutdownNow();
    }

    /**
     * [D] 总开关 collapse.executor.enabled=false 关闭折叠自动装配
     */
    @Test
    void disabledByMasterSwitch() {
        try (AnnotationConfigApplicationContext disabled = new AnnotationConfigApplicationContext()) {
            disabled.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("collapse-disabled",
                            Collections.singletonMap("collapse.executor.enabled", "false")));
            disabled.register(TestConfig.class, UserService.class);
            disabled.refresh();
            assertEquals(0, disabled.getBeanNamesForType(CollapsibleIntercept.class).length,
                    "collapse.executor.enabled=false 应关闭折叠拦截器");
            assertEquals(0, disabled.getBeanNamesForType(CollapsibleAdvisor.class).length,
                    "collapse.executor.enabled=false 应关闭折叠 Advisor");
        }
    }
}
