package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.spring.support.aop.CollapsibleAdvisor;
import com.chua.spring.support.annotation.Collapsible;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollapsibleIntercept} 回归测试：key() SpEL 归约、fallback 降级、全局默认配置、
 * 接口多实现共享执行器名的防护（B2）、Map 元素 key 归约（B5）。
 *
 * @author CH
 * @since 2026/09/04
 */
class CollapsibleInterceptTest {

    /**
     * 业务实体：equals 基于身份，id 相同也不相等（用于验证 key() 归约）
     */
    static final class Entity {

        private final long id;

        private final String name;

        Entity(long id, String name) {
            this.id = id;
            this.name = name;
        }

        public long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /**
     * 公共降级 Bean
     */
    @Component("fallbackService")
    static class FallbackService {

        public Map<Long, String> emptyMap(List<Long> ids) {
            return Collections.emptyMap();
        }
    }

    /**
     * 折叠目标 service
     */
    @Component
    static class UserService {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        @Collapsible(name = "test-key", waitThreshold = 4, collectingWaitTime = 500, key = "id")
        public Map<Long, String> findByEntities(List<Entity> entities) {
            calls.computeIfAbsent("key", ignored -> new AtomicInteger()).incrementAndGet();
            Map<Long, String> result = new LinkedHashMap<>();
            for (Entity entity : entities) {
                result.put(entity.getId(), "u" + entity.getId());
            }
            return result;
        }

        @Collapsible(name = "test-fb", waitThreshold = 2, collectingWaitTime = 500,
                fallback = "fallbackService#emptyMap")
        public Map<Long, String> failing(List<Long> ids) {
            calls.computeIfAbsent("fb", ignored -> new AtomicInteger()).incrementAndGet();
            throw new IllegalStateException("下游故障");
        }

        @Collapsible(name = "test-global")
        public Map<Long, String> globalDefault(List<Long> ids) {
            calls.computeIfAbsent("global", ignored -> new AtomicInteger()).incrementAndGet();
            Map<Long, String> result = new LinkedHashMap<>();
            for (Long id : ids) {
                result.put(id, "g" + id);
            }
            return result;
        }

        @Collapsible(name = "test-key-map", waitThreshold = 4, collectingWaitTime = 500, key = "['id']")
        public Map<Long, String> findByMaps(List<Map<String, Object>> elements) {
            calls.computeIfAbsent("key-map", ignored -> new AtomicInteger()).incrementAndGet();
            Map<Long, String> result = new LinkedHashMap<>();
            for (Map<String, Object> element : elements) {
                result.put((Long) element.get("id"), "u" + element.get("id"));
            }
            return result;
        }

        @Collapsible(name = "test-bad-key", waitThreshold = 4, collectingWaitTime = 500, key = "id[")
        public Map<Long, String> badKey(List<Long> ids) {
            return Map.of();
        }

        public int getCallCount(String method) {
            AtomicInteger counter = calls.get(method);
            return counter != null ? counter.get() : 0;
        }
    }

    /**
     * 查询接口（无注解，注解在实现类方法上；JDK 代理下默认名称取实现类）
     */
    interface Query {

        Map<Long, String> batchFind(List<Long> ids);
    }

    /**
     * 实现 A：显式共享 name（与实现 B 相同），触发混目标碰撞
     */
    @Component("implA")
    static class QueryImplA implements Query {

        @Override
        // 显式共享 name + 高阈值 + 长等待：保证 A/B 两组调用进入同一执行器并收集到同一批
        @Collapsible(name = "shared-impl", waitThreshold = 10, collectingWaitTime = 500)
        public Map<Long, String> batchFind(List<Long> ids) {
            Map<Long, String> result = new LinkedHashMap<>();
            for (Long id : ids) {
                result.put(id, "a" + id);
            }
            return result;
        }
    }

    /**
     * 实现 B：显式共享 name（与实现 A 相同），触发混目标碰撞
     */
    @Component("implB")
    static class QueryImplB implements Query {

        @Override
        // 显式共享 name + 高阈值 + 长等待：保证 A/B 两组调用进入同一执行器并收集到同一批
        @Collapsible(name = "shared-impl", waitThreshold = 10, collectingWaitTime = 500)
        public Map<Long, String> batchFind(List<Long> ids) {
            Map<Long, String> result = new LinkedHashMap<>();
            for (Long id : ids) {
                result.put(id, "b" + id);
            }
            return result;
        }
    }

    /**
     * 最小 Spring 配置：全局默认阈值 2（验证注解未指定时读取全局）
     */
    @Configuration
    @EnableAspectJAutoProxy
    static class SpringConfig {

        @Bean
        CollapsibleIntercept collapsibleIntercept() {
            CollapseConfig global = new CollapseConfig();
            global.setWaitThreshold(2);
            global.setCollectingWaitTime(500);
            return new CollapsibleIntercept(global);
        }

        @Bean
        CollapsibleAdvisor collapsibleAdvisor(CollapsibleIntercept intercept) {
            return new CollapsibleAdvisor(intercept);
        }
    }

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(SpringConfig.class, UserService.class, FallbackService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    /**
     * key() SpEL 归约：不同实体实例共享 id -> 按 id 合并一次核心执行，子 Map 按 id 精确回填
     */
    @Test
    void keySpelMergesByExtractedKey() throws Exception {
        UserService service = context.getBean(UserService.class);
        int threads = 8;
        List<List<Entity>> entitySets = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Entity> entities = new ArrayList<>();
            entities.add(new Entity(i, "n" + i));
            entities.add(new Entity((i + 1) % threads, "m" + i));
            entitySets.add(entities);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemonFactory());
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Map<Long, String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Entity> mine = entitySets.get(i);
            futures.add(pool.submit(() -> {
                barrier.await();
                return service.findByEntities(mine);
            }));
        }
        for (int i = 0; i < threads; i++) {
            Map<Long, String> sub = futures.get(i).get(15, TimeUnit.SECONDS);
            assertEquals(new HashSet<>(entitySets.get(i).stream().map(Entity::getId).toList()).size(),
                    sub.size(), "子结果应按去重后 id 数量回填");
            for (Entity entity : entitySets.get(i)) {
                assertEquals("u" + entity.getId(), sub.get(entity.getId()), "子结果值应精确");
            }
        }
        int calls = service.getCallCount("key");
        assertTrue(calls >= 1 && calls < threads, "key 归约后核心执行应严格少于调用数，实际 " + calls);
        pool.shutdownNow();
    }

    /**
     * fallback 降级：批量执行失败 -> bean#method 统一降级
     */
    @Test
    void batchFailureDegradesViaBeanMethod() throws Exception {
        UserService service = context.getBean(UserService.class);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemonFactory());
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Map<Long, String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Long> mine = Collections.singletonList((long) i);
            futures.add(pool.submit(() -> {
                barrier.await();
                return service.failing(mine);
            }));
        }
        for (Future<Map<Long, String>> future : futures) {
            assertTrue(future.get(15, TimeUnit.SECONDS).isEmpty(), "批量失败应统一降级为空 Map");
        }
        pool.shutdownNow();
    }

    /**
     * 全局默认配置：注解未显式指定阈值（哨兵 -1）时使用全局默认 2
     */
    @Test
    void globalDefaultAppliesWhenAnnotationUnset() throws Exception {
        UserService service = context.getBean(UserService.class);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemonFactory());
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Map<Long, String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Long> mine = Collections.singletonList((long) i);
            futures.add(pool.submit(() -> {
                barrier.await();
                return service.globalDefault(mine);
            }));
        }
        for (int i = 0; i < threads; i++) {
            Map<Long, String> sub = futures.get(i).get(15, TimeUnit.SECONDS);
            assertEquals("g" + i, sub.get((long) i), "全局默认场景结果应正确");
        }
        int calls = service.getCallCount("global");
        assertTrue(calls >= 1 && calls < threads, "全局阈值 2、4 并发应合并执行（严格少于调用数），实际 " + calls);
        pool.shutdownNow();
    }

    /**
     * 混入不同目标实例（接口多实现显式共享 name）时抛出防护异常，而非静默错误结果（B2）
     */
    @Test
    void mixedTargetsUnderSharedNameThrowInsteadOfCorrupting() throws Exception {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(SpringConfig.class, QueryImplA.class, QueryImplB.class)) {
            // JDK 代理只暴露接口类型，须按 bean 名 + 接口类型获取（getBean(具体类) 会失败）
            Query implA = ctx.getBean("implA", Query.class);
            Query implB = ctx.getBean("implB", Query.class);
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads, daemonFactory());
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<Map<Long, String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return implA.batchFind(List.of(1L));
                }));
            }
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return implB.batchFind(List.of(2L));
                }));
            }
            boolean sawGuard = false;
            for (Future<Map<Long, String>> future : futures) {
                try {
                    future.get(15, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    // 防护异常经 ExecutionException 多层包装，遍历异常链查找
                    Throwable cause = e;
                    while (cause != null) {
                        if (cause.getMessage() != null && cause.getMessage().contains("不同目标实例")) {
                            sawGuard = true;
                            break;
                        }
                        cause = cause.getCause();
                    }
                }
            }
            assertTrue(sawGuard, "混入不同目标实例应抛出防护异常而非静默错误结果");
            pool.shutdownNow();
        }
    }

    /**
     * key() SpEL 复杂元素：Map 元素按 ['id'] 归约合并（B5）
     */
    @Test
    void keySpelWithMapElements() throws Exception {
        UserService service = context.getBean(UserService.class);
        int threads = 8;
        List<List<Map<String, Object>>> elementSets = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Map<String, Object>> elements = new ArrayList<>();
            elements.add(Map.of("id", (Object) (long) i, "name", "n" + i));
            elements.add(Map.of("id", (Object) (long) ((i + 1) % threads), "name", "m" + i));
            elementSets.add(elements);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemonFactory());
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Map<Long, String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Map<String, Object>> mine = elementSets.get(i);
            futures.add(pool.submit(() -> {
                barrier.await();
                return service.findByMaps(mine);
            }));
        }
        for (int i = 0; i < threads; i++) {
            Map<Long, String> sub = futures.get(i).get(15, TimeUnit.SECONDS);
            long id0 = i;
            long id1 = (i + 1) % threads;
            assertEquals(2, sub.size(), "子结果应按去重后 id 数量回填");
            assertEquals("u" + id0, sub.get(id0), "子结果值应精确");
            assertEquals("u" + id1, sub.get(id1), "子结果值应精确");
        }
        int calls = service.getCallCount("key-map");
        assertTrue(calls >= 1 && calls < threads, "key 归约后核心执行应严格少于调用数，实际 " + calls);
        pool.shutdownNow();
    }

    /**
     * 非法 key() SpEL 报错：解析失败应抛出异常而非静默降级
     */
    @Test
    void illegalKeySpelThrows() {
        UserService service = context.getBean(UserService.class);
        assertThrows(Throwable.class, () -> service.badKey(List.of(1L)),
                "非法 key() SpEL 应抛异常而非静默");
    }

    private static java.util.concurrent.ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        };
    }
}
