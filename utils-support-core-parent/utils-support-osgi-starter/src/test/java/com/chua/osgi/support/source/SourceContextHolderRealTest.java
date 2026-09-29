package com.chua.osgi.support.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceContextHolder} 的真实测试。
 *
 * <p>这是源码扩展点能复用容器 Bean 的唯一通道——源码活在独立 ClassLoader 里，
 * 拿不到 {@code @Autowired}，全靠这里把 Mapper / Service 递过去。
 * 通道不通则源码扩展点只能做「无依赖的计算」，对业务接入毫无价值。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
class SourceContextHolderRealTest {

    /**
     * 测试用接口。
     */
    public interface Repo {

        /**
         * 按 id 取值。
         *
         * @param id 标识
         * @return 值
         */
        String find(String id);
    }

    /**
     * 测试用实现（用具体类而非 lambda：Lambda 的 getClass() 是合成类，
     * 正好能验证按类型查找时的回退逻辑）。
     */
    static final class RepoImpl implements Repo {

        @Override
        public String find(String id) {
            return "repo:" + id;
        }
    }

    /**
     * 另一个实现类，用于验证同类型多实例时取哪一个。
     */
    static final class OtherRepoImpl implements Repo {

        @Override
        public String find(String id) {
            return "other:" + id;
        }
    }

    /**
     * 每个用例后清理静态状态，避免跨用例污染。
     */
    @AfterEach
    void tearDown() {
        SourceContextHolder.clear();
    }

    /**
     * 未安装时取不到 Bean，且给出可操作的告警文案。
     */
    @Test
    @DisplayName("未安装上下文时返回 null，不抛异常")
    void withoutInstall_shouldReturnNull() {
        assertNull(SourceContextHolder.get());
        assertNull(SourceContextHolder.get(Repo.class));
    }

    /**
     * 安装后可按类型与按名取到同一个实例。
     */
    @Test
    @DisplayName("安装后可按类型与按名取到 Bean")
    void installedContext_shouldResolveByTypeAndName() {
        var repo = new RepoImpl();
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("studentMapper", repo);
        SourceContextHolder.install(beans);

        var context = SourceContextHolder.get();
        assertNotNull(context, "安装后上下文应可用");
        assertEquals(1, context.size());
        assertSame(repo, context.get(Repo.class), "按类型应取到");
        assertSame(repo, context.getBean("studentMapper"), "按名应取到");
        assertSame(repo, SourceContextHolder.get(Repo.class), "静态门面应一致");
        assertTrue(context.contains(Repo.class));
    }

    /**
     * Bean 以实现类注册时，按接口类型也应能取到。
     *
     * <p>真实场景里 Map 里放的是 {@code studentMapper} 这类具体实现，
     * 而源码想按接口取；不处理这一层就会取到 null。</p>
     */
    @Test
    @DisplayName("按接口类型取实现类注册的 Bean")
    void shouldResolveInterfaceWhenRegisteredByImplClass() {
        var repo = new OtherRepoImpl();
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("repo", repo);
        SourceContextHolder.install(beans);

        assertSame(repo, SourceContextHolder.get(Repo.class),
                "实现类注册的 Bean 应能按接口取到");
        assertEquals("other:x", SourceContextHolder.get(Repo.class).find("x"));
    }

    /**
     * 未提供的类型返回 null 而不是抛异常——源码据此可自行降级。
     */
    @Test
    @DisplayName("未提供的类型返回 null")
    void missingType_shouldReturnNull() {
        SourceContextHolder.install(Map.of("other", "value"));
        assertNull(SourceContextHolder.get(Repo.class));
        assertNull(SourceContextHolder.getBean("absent"));
    }

    /**
     * 空映射视为未安装，避免留下一个永远取不到东西的空上下文。
     */
    @Test
    @DisplayName("安装空映射视为未安装")
    void emptyMap_shouldMeanNotInstalled() {
        SourceContextHolder.install(Map.of());
        assertNull(SourceContextHolder.get(), "空映射不应留下空上下文");

        SourceContextHolder.install(null);
        assertNull(SourceContextHolder.get(), "null 应清空上下文");
    }

    /**
     * clear 后上下文释放，源码不再持有容器 Bean 引用。
     */
    @Test
    @DisplayName("clear 后上下文释放")
    void clear_shouldReleaseContext() {
        SourceContextHolder.install(Map.of("repo", new RepoImpl()));
        assertNotNull(SourceContextHolder.get());

        SourceContextHolder.clear();

        assertNull(SourceContextHolder.get());
        assertNull(SourceContextHolder.get(Repo.class));
    }

    /**
     * 映射里的 null 值不应进按类型索引，否则按类型取会拿到 null 而非正确实例。
     */
    @Test
    @DisplayName("映射中的 null 值被跳过")
    void nullBeanValue_shouldBeSkipped() {
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("nullBean", null);
        beans.put("repo", new RepoImpl());
        SourceContextHolder.install(beans);

        assertNotNull(SourceContextHolder.get(Repo.class), "null 不应污染类型索引");
        assertFalse(SourceContextHolder.get().contains(String.class));
    }
}
