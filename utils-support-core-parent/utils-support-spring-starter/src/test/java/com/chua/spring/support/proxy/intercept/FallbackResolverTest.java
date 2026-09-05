package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FallbackResolver} 回归测试：bean#method 与同类方法名两种降级引用。
 *
 * @author CH
 * @since 2026/09/04
 */
class FallbackResolverTest {

    /**
     * 公共降级 Bean（统一空降级）
     */
    @Component("fallbackService")
    static class FallbackService {

        public Map<Long, String> emptyMap(List<Long> ids) {
            return Collections.emptyMap();
        }
    }

    /**
     * 业务 Service（同类降级方法）
     */
    static class TargetService {

        public Map<Long, String> query(List<Long> ids) {
            Map<Long, String> result = new LinkedHashMap<>();
            result.put(1L, "ok");
            return result;
        }

        public Map<Long, String> localFallback(List<Long> ids) {
            Map<Long, String> result = new LinkedHashMap<>();
            result.put(-1L, "local");
            return result;
        }
    }

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(FallbackService.class, TargetService.class);
        FallbackResolver.registerApplicationContext(context);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    /**
     * bean#method 跨 Bean 统一降级
     */
    @Test
    void beanMethodFallbackResolvesFromContainer() throws Exception {
        TargetService target = context.getBean(TargetService.class);
        ProxyMethod proxyMethod = buildProxyMethod(target, "query", Collections.singletonList(1L));
        Object result = FallbackResolver.resolve("fallbackService#emptyMap", proxyMethod);
        assertTrue(result instanceof Map && ((Map<?, ?>) result).isEmpty(),
                "应返回统一空降级 Map，实际 " + result);
    }

    /**
     * 同类方法名降级（历史行为兼容）
     */
    @Test
    void sameClassMethodFallbackResolves() throws Exception {
        TargetService target = context.getBean(TargetService.class);
        ProxyMethod proxyMethod = buildProxyMethod(target, "query", Collections.singletonList(1L));
        Object result = FallbackResolver.resolve("localFallback", proxyMethod);
        assertEquals("local", ((Map<?, ?>) result).get(-1L), "同类降级方法应被调用");
    }

    /**
     * Bean 不存在时返回 null（不阻断主流程）
     */
    @Test
    void missingBeanReturnsNull() throws Exception {
        TargetService target = context.getBean(TargetService.class);
        ProxyMethod proxyMethod = buildProxyMethod(target, "query", Collections.singletonList(1L));
        assertNull(FallbackResolver.resolve("noSuchBean#emptyMap", proxyMethod));
    }

    /**
     * 方法不存在时返回 null
     */
    @Test
    void missingMethodReturnsNull() throws Exception {
        TargetService target = context.getBean(TargetService.class);
        ProxyMethod proxyMethod = buildProxyMethod(target, "query", Collections.singletonList(1L));
        assertNull(FallbackResolver.resolve("fallbackService#noSuchMethod", proxyMethod));
    }

    private static ProxyMethod buildProxyMethod(TargetService target, String methodName, List<Long> args) throws Exception {
        Method method = TargetService.class.getMethod(methodName, List.class);
        return ProxyMethod.builder()
                .target(target)
                .method(method)
                .args(new Object[]{args})
                .build();
    }
}
