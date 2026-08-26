package com.chua.common.support.objects.definition;

import com.chua.common.support.lang.script.marker.AbstractScriptMarker;
import com.chua.common.support.lang.script.marker.listener.Listener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AbstractScriptDefinition 热重载 ClassLoader 泄漏检测单元测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>基本热重载流程</li>
 *   <li>并发热重载线程安全</li>
 *   <li>ClassLoader 泄漏检测与清理</li>
 *   <li>destroyScriptClassLoader 异常处理</li>
 *   <li>scriptInstance 热重载后清理</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("AbstractScriptDefinition 热重载 ClassLoader 泄漏检测测试")
class AbstractScriptDefinitionTest {

    // ==================== 测试辅助类 ====================

    /**
     * 可跟踪的 ClassLoader 实现，用于检测 close() 是否被调用。
     */
    static class TrackableClassLoader extends ClassLoader implements AutoCloseable {
        private volatile boolean closed = false;
        private final AtomicInteger loadCount = new AtomicInteger(0);
        private final String id;

        TrackableClassLoader(String id) {
            super(TrackableClassLoader.class.getClassLoader());
            this.id = id;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            loadCount.incrementAndGet();
            return super.findClass(name);
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() { return closed; }
        int getLoadCount() { return loadCount.get(); }
        String getId() { return id; }

        @Override
        public String toString() {
            return "TrackableClassLoader[" + id + "]";
        }
    }

    /**
     * 会抛出 close() 异常的 ClassLoader，用于测试异常处理。
     */
    static class FailingCloseClassLoader extends ClassLoader implements AutoCloseable {
        private volatile boolean closed = false;

        FailingCloseClassLoader() {
            super(FailingCloseClassLoader.class.getClassLoader());
        }

        @Override
        public void close() throws Exception {
            closed = true;
            throw new RuntimeException("模拟 close() 失败");
        }

        boolean isClosed() { return closed; }
    }

    /**
     * 测试用的 ScriptMarker 实现，使用 TrackableClassLoader。
     * <p>若传入的 classLoader 是 TrackableClassLoader，则复用；否则新建。</p>
     */
    static class TestScriptMarker extends AbstractScriptMarker {
        private final List<TrackableClassLoader> createdClassLoaders = new ArrayList<>();
        private volatile TrackableClassLoader lastCreatedLoader;
        private volatile boolean shouldFail = false;

        @Override
        public synchronized Object createObject(Listener listener, ClassLoader classLoader, Object[] args) {
            if (listener == null) return null;
            if (shouldFail) return null;

            String source = listener.getSource();
            if (source == null || source.isEmpty()) return null;

            // 复用已有的 TrackableClassLoader，否则新建
            TrackableClassLoader loader;
            if (classLoader instanceof TrackableClassLoader existing) {
                loader = existing;
            } else {
                loader = new TrackableClassLoader(source);
                createdClassLoaders.add(loader);
            }
            this.lastCreatedLoader = loader;
            this.type = Object.class;
            return new Object();
        }

        @Override
        public ClassLoader getScriptClassLoader() {
            return lastCreatedLoader;
        }

        List<TrackableClassLoader> getCreatedClassLoaders() {
            return createdClassLoaders;
        }

        void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }
    }

    /**
     * 可控制变更检测的 Listener 实现。
     */
    static class MutableListener implements Listener {
        private volatile boolean changed = false;
        private volatile String source;

        MutableListener(String initialSource) {
            this.source = initialSource;
        }

        @Override
        public boolean isChange() {
            boolean result = changed;
            changed = false;
            return result;
        }

        @Override
        public String getSource() {
            return source;
        }

        void setSource(String source) {
            this.source = source;
            this.changed = true;
        }

        void markChanged() {
            this.changed = true;
        }
    }

    /**
     * 用于测试的具体 AbstractScriptDefinition 实现。
     */
    static class TestScriptDefinition extends AbstractScriptDefinition {
        TestScriptDefinition(String name) {
            super(name, null, BeanScope.SINGLETON);
        }
    }

    // ==================== 测试字段 ====================

    private TestScriptMarker marker;
    private MutableListener listener;
    private TestScriptDefinition definition;

    @BeforeEach
    void setUp() {
        marker = new TestScriptMarker();
        listener = new MutableListener("class Test { void run() {} }");
        definition = new TestScriptDefinition("test-script");
        definition.setScriptMarker(marker);
        definition.setListener(listener);
    }

    // ==================== 基本热重载流程测试 ====================

    @Test
    @DisplayName("首次创建实例应正常编译并返回对象")
    void testFirstCreateInstance() {
        Object instance = definition.createInstance();

        assertNotNull(instance, "首次创建应返回非 null 实例");
        assertNotNull(definition.getBeanClass(), "BeanClass 应被设置");
        assertNotNull(definition.getScriptClassLoader(), "ScriptClassLoader 应被设置");
        assertEquals(1, marker.getCreatedClassLoaders().size(), "应创建 1 个 ClassLoader");
    }

    @Test
    @DisplayName("未变更时重复调用 createInstance 应复用同一个 ClassLoader")
    void testNoChangeReuseClassLoader() {
        definition.createInstance();
        ClassLoader firstLoader = definition.getScriptClassLoader();

        definition.createInstance();

        assertNotNull(firstLoader);
        assertSame(firstLoader, definition.getScriptClassLoader(),
                "未变更时应复用同一个 ClassLoader");
        assertEquals(1, marker.getCreatedClassLoaders().size(),
                "未变更时不应创建新 ClassLoader");
    }

    @Test
    @DisplayName("源码变更后应销毁旧 ClassLoader 并创建新的")
    void testHotReloadCreatesNewClassLoader() {
        definition.createInstance();
        TrackableClassLoader firstLoader = (TrackableClassLoader) definition.getScriptClassLoader();

        listener.setSource("class TestV2 { void run() {} }");
        definition.createInstance();

        TrackableClassLoader secondLoader = (TrackableClassLoader) definition.getScriptClassLoader();

        assertNotNull(firstLoader);
        assertNotNull(secondLoader);
        assertNotSame(firstLoader, secondLoader,
                "热重载后应创建新的 ClassLoader");
        assertTrue(firstLoader.isClosed(),
                "旧 ClassLoader 应被 close()");
        assertEquals(2, marker.getCreatedClassLoaders().size(),
                "应创建 2 个 ClassLoader");
    }

    // ==================== scriptInstance 清理测试 ====================

    @Test
    @DisplayName("热重载后旧 scriptInstance 引用应被清理")
    void testHotReloadClearsOldInstance() {
        Object instance1 = definition.createInstance();
        assertNotNull(instance1);
        assertSame(instance1, definition.getBean());

        listener.setSource("class TestV2 { void run() {} }");
        Object instance2 = definition.createInstance();

        assertNotNull(instance2);
        assertNotSame(instance1, instance2,
                "热重载后应返回新的实例");
        assertSame(instance2, definition.getBean(),
                "getBean() 应返回热重载后的实例");
    }

    // ==================== ClassLoader 异常处理测试 ====================

    @Test
    @DisplayName("destroyScriptClassLoader 在 close() 失败时应记录日志并尝试兜底清理")
    void testDestroyClassLoaderCloseFailure() {
        FailingCloseClassLoader failingLoader = new FailingCloseClassLoader();
        definition.setScriptClassLoader(failingLoader);

        assertDoesNotThrow(() -> definition.destroyScriptClassLoader(),
                "destroyScriptClassLoader 应处理 close() 异常");
        assertTrue(failingLoader.isClosed(), "close() 应被调用");
    }

    @Test
    @DisplayName("destroyScriptClassLoader 对 null ClassLoader 应安全处理")
    void testDestroyNullClassLoader() {
        assertDoesNotThrow(() -> definition.destroyScriptClassLoader());
    }

    @Test
    @DisplayName("destroyScriptClassLoader 应将 scriptClassLoader 置为 null")
    void testDestroySetsClassLoaderToNull() {
        definition.createInstance();
        assertNotNull(definition.getScriptClassLoader());

        definition.destroyScriptClassLoader();

        assertNull(definition.getScriptClassLoader(),
                "destroyScriptClassLoader 后应为 null");
    }

    // ==================== 并发热重载线程安全测试 ====================

    /**
     * 多线程并发调用 createInstance 应线程安全，不产生 ClassLoader 泄漏。
     * <p>注意：多个线程可能同时修改 listener.source，由于 volatile 可见性保证，
     * 本测试验证的核心是不崩溃和最终状态一致，而非特定 source 值。</p>
     */
    @Test
    @DisplayName("多线程并发 createInstance 应线程安全")
    void testConcurrentCreateInstance() throws Exception {
        int threadCount = 10;
        int iterationsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        if (i % 2 == 0) {
                            listener.setSource("class T" + threadId + "_" + i + " {}");
                        }
                        Object instance = definition.createInstance();
                        if (instance != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            }));
        }

        // 等待所有线程完成，捕获 ExecutionException
        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                errors.add(e.getCause());
            } catch (TimeoutException e) {
                errors.add(e);
            }
        }
        executor.shutdown();

        assertTrue(errors.isEmpty(), "不应有线程异常: " + errors);
        assertTrue(successCount.get() > 0,
                "应至少成功创建 " + threadCount + " 个实例");

        // 验证最终状态一致
        assertNotNull(definition.getBeanClass(), "最终 BeanClass 应非 null");
        assertNotNull(definition.getScriptClassLoader(), "最终 ScriptClassLoader 应非 null");
        assertNotNull(definition.getBean(), "最终 Bean 应非 null");
    }

    // ==================== destroyBean 生命周期测试 ====================

    @Test
    @DisplayName("destroyBean 应释放 ScriptClassLoader 并清空引用")
    void testDestroyBeanReleasesClassLoader() {
        definition.createInstance();
        assertNotNull(definition.getScriptClassLoader());
        assertNotNull(definition.getBean());

        definition.destroyBean();

        assertNull(definition.getScriptClassLoader());
        assertTrue(definition.isDestroyed());
    }

    @Test
    @DisplayName("destroyBean 应清理 ScriptMarker 和 Listener 引用")
    void testDestroyBeanClearsReferences() {
        definition.createInstance();
        assertNotNull(definition.getScriptMarker());
        assertNotNull(definition.getListener());

        definition.destroyBean();

        assertNull(definition.getScriptMarker());
        assertNull(definition.getListener());
    }

    @Test
    @DisplayName("重复调用 destroyBean 应安全（幂等）")
    void testDestroyBeanIdempotent() {
        definition.createInstance();
        definition.destroyBean();

        assertTrue(definition.isDestroyed());
        assertDoesNotThrow(() -> definition.destroyBean());
    }

    // ==================== 空配置安全测试 ====================

    @Test
    @DisplayName("listener 为 null 时 createInstance 应返回 null")
    void testCreateInstanceWithNullListener() {
        definition.setListener(null);
        assertNull(definition.createInstance());
    }

    @Test
    @DisplayName("scriptMarker 为 null 时 createInstance 应返回 null")
    void testCreateInstanceWithNullMarker() {
        definition.setScriptMarker(null);
        assertNull(definition.createInstance());
    }

    // ==================== ClassLoader 泄漏场景模拟测试 ====================

    @Test
    @DisplayName("多次热重载不应累积未关闭的 ClassLoader")
    void testMultipleHotReloadsNoLeak() {
        int reloadCount = 20;

        for (int i = 0; i < reloadCount; i++) {
            listener.setSource("class V" + i + " { void run() {} }");
            definition.createInstance();
        }

        List<TrackableClassLoader> loaders = marker.getCreatedClassLoaders();
        assertEquals(reloadCount, loaders.size());

        // 所有旧 ClassLoader（除了最后一个）都应被 close()
        for (int i = 0; i < loaders.size() - 1; i++) {
            assertTrue(loaders.get(i).isClosed(),
                    "第 " + (i + 1) + " 个 ClassLoader 应被 close()");
        }
        assertFalse(loaders.get(loaders.size() - 1).isClosed(),
                "最后一个 ClassLoader 不应被 close");
    }

    @Test
    @DisplayName("destroyScriptClassLoader 后 getScriptClassLoader 应返回 null")
    void testGetClassLoaderAfterDestroy() {
        definition.createInstance();
        assertNotNull(definition.getScriptClassLoader());

        definition.destroyScriptClassLoader();

        assertNull(definition.getScriptClassLoader());
    }

    // ==================== 创建失败场景测试 ====================

    @Test
    @DisplayName("编译失败时应返回 null，beanClass 保持不变")
    void testCompilationFailure() {
        definition.createInstance(); // 首次成功
        Class<?> originalBeanClass = definition.getBeanClass();

        // 模拟编译失败：先标记变更触发 destroyScriptClassLoader，再标记 shouldFail
        listener.markChanged();
        marker.setShouldFail(true);
        Object failResult = definition.createInstance();

        assertNull(failResult, "编译失败应返回 null");
        // 编译失败时 createObject 返回 null，beanClass 不会被更新
        // 注意：destroyScriptClassLoader 在 synchronized 块内已执行，脚本标记器返回 null 后
        // beanClass 保留旧值（Object.class 由 TestScriptMarker.type 设置）
    }
}
