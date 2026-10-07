package com.chua.common.support.collection.wrap;

import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集合后端 SPI 切换测试：钉住「默认 eclipse、可切 jdk、未知名称快速失败、
 * 系统属性驱动、坏配置回落默认」这几条解析契约。
 *
 * <p>本体系对外承诺的行为是：换后端只影响内存布局与性能特征，不改变任何
 * 集合语义；且默认必须走 Eclipse Collections（省内存），Eclipse 缺失时才自动
 * 落到 JDK。选择逻辑出错的后果是「悄悄用了更耗内存的实现」或「启动即崩」，
 * 因此每个分支都需要显式验证。测试期间会修改全局后端状态，
 * 统一在 {@code @AfterEach} 还原默认。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see CollectionBackends
 * @see Wraps
 */
class CollectionBackendTest {

    /**
     * 每个用例前还原为默认（eclipse）后端，避免上个用例的切换泄漏。
     */
    @BeforeEach
    void resetBackend() {
        System.clearProperty(CollectionBackends.PROPERTY);
        CollectionBackends.reload();
    }

    /**
     * 每个用例后清理系统属性并还原默认后端。
     */
    @AfterEach
    void cleanup() {
        System.clearProperty(CollectionBackends.PROPERTY);
        CollectionBackends.reload();
    }

    /**
     * 默认后端是 eclipse：分装对象的底层存储必须是 Eclipse Collections 实现。
     */
    @Test
    @DisplayName("默认后端为 eclipse，底层存储为 FastList/UnifiedSet/UnifiedMap")
    void defaultBackendIsEclipse() {
        assertEquals("eclipse", CollectionBackends.name());
        assertInstanceOf(EclipseCollectionBackend.class, CollectionBackends.get());
        assertInstanceOf(WrappedList.class, Wraps.list());
        assertInstanceOf(FastList.class, ((WrappedList<?>) Wraps.list()).delegate());
        assertInstanceOf(UnifiedSet.class, ((WrappedSet<?>) Wraps.set()).delegate());
        assertInstanceOf(UnifiedMap.class, ((WrappedMap<?, ?>) Wraps.map()).delegate());
    }

    /**
     * 代码切换到 jdk 后底层存储变为 JDK 集合，且语义不变；切回 eclipse 恢复。
     */
    @Test
    @DisplayName("use() 可在 eclipse/jdk 间切换且语义不变")
    void switchBackendByName() {
        CollectionBackends.use("jdk");
        assertEquals("jdk", CollectionBackends.name());
        assertInstanceOf(JdkCollectionBackend.class, CollectionBackends.get());
        assertInstanceOf(ArrayList.class, ((WrappedList<?>) Wraps.list()).delegate());
        assertInstanceOf(HashSet.class, ((WrappedSet<?>) Wraps.set()).delegate());
        assertInstanceOf(HashMap.class, ((WrappedMap<?, ?>) Wraps.map()).delegate());

        // 切换后集合语义仍与原生一致
        Set<String> set = Wraps.set();
        set.add("a");
        assertEquals(new HashSet<>(List.of("a")), set);
        List<String> list = Wraps.list();
        list.add("x");
        assertEquals(new ArrayList<>(List.of("x")), list);
        Map<String, String> map = Wraps.map();
        map.put("k", "v");
        assertEquals(new HashMap<>(Map.of("k", "v")), map);

        CollectionBackends.use("eclipse");
        assertEquals("eclipse", CollectionBackends.name());
        assertInstanceOf(UnifiedSet.class, ((WrappedSet<?>) Wraps.set()).delegate());
    }

    /**
     * 未知后端名快速失败，且不改变当前后端。
     */
    @Test
    @DisplayName("use() 未知名称抛 IllegalArgumentException 且不影响当前后端")
    void unknownBackendNameFailsFast() {
        String before = CollectionBackends.name();
        assertThrows(IllegalArgumentException.class, () -> CollectionBackends.use("no-such-backend"));
        assertThrows(IllegalArgumentException.class, () -> CollectionBackends.use(""));
        assertThrows(IllegalArgumentException.class, () -> CollectionBackends.use("  "));
        assertThrows(IllegalArgumentException.class, () -> CollectionBackends.use(null));
        assertEquals(before, CollectionBackends.name(), "失败的切换不得污染当前后端");
    }

    /**
     * 系统属性驱动后端选择（含空白容错），reload 生效。
     */
    @Test
    @DisplayName("系统属性 utils.support.collection.backend 驱动后端选择")
    void systemPropertySelectsBackend() {
        System.setProperty(CollectionBackends.PROPERTY, "jdk");
        assertEquals("jdk", CollectionBackends.reload().name());
        assertInstanceOf(HashMap.class, ((WrappedMap<?, ?>) Wraps.map()).delegate());

        System.setProperty(CollectionBackends.PROPERTY, "  eclipse  ");
        assertEquals("eclipse", CollectionBackends.reload().name());
        assertInstanceOf(UnifiedMap.class, ((WrappedMap<?, ?>) Wraps.map()).delegate());

        System.clearProperty(CollectionBackends.PROPERTY);
        assertEquals("eclipse", CollectionBackends.reload().name());
    }

    /**
     * 系统属性指向未知后端时回落默认（eclipse）而不是启动失败。
     */
    @Test
    @DisplayName("系统属性指向未知后端时回落默认 eclipse")
    void unknownSystemPropertyFallsBackToDefault() {
        System.setProperty(CollectionBackends.PROPERTY, "not-registered");
        assertEquals("eclipse", CollectionBackends.reload().name());
    }

    /**
     * 两个内置后端均可通过 SPI 名称定位并切换（候选注册完整性）。
     */
    @Test
    @DisplayName("内置后端 eclipse/jdk 均可通过 SPI 名称定位")
    void builtInBackendsRegistered() {
        assertEquals("eclipse", CollectionBackends.use("eclipse").name());
        assertEquals("jdk", CollectionBackends.use("jdk").name());
        assertEquals("eclipse", CollectionBackends.use("ec").name(), "@Spi 别名 ec 可定位到 eclipse 后端");
    }

    /**
     * 队列/双端/有序类型在两后端下都使用统一存储（ArrayDeque/TreeSet/TreeMap）。
     */
    @Test
    @DisplayName("队列与有序类型在两后端下均为 ArrayDeque/TreeSet/TreeMap")
    void uniformStorageForQueueAndSortedTypes() {
        for (String backend : new String[]{"eclipse", "jdk"}) {
            CollectionBackends.use(backend);
            Queue<String> queue = Wraps.queue();
            assertInstanceOf(WrappedQueue.class, queue);
            assertInstanceOf(ArrayDeque.class, ((WrappedQueue<?>) queue).delegate());
            Deque<String> deque = Wraps.deque();
            assertInstanceOf(WrappedDeque.class, deque);
            assertInstanceOf(ArrayDeque.class, ((WrappedDeque<?>) deque).delegate());
            SortedSet<String> sortedSet = Wraps.sortedSet();
            assertInstanceOf(WrappedSortedSet.class, sortedSet);
            assertInstanceOf(TreeSet.class, ((WrappedSortedSet<?>) sortedSet).delegate());
            SortedMap<String, String> sortedMap = Wraps.sortedMap();
            assertInstanceOf(WrappedSortedMap.class, sortedMap);
            assertInstanceOf(TreeMap.class, ((WrappedSortedMap<?, ?>) sortedMap).delegate());
        }
    }

    /**
     * 插入序/优先队列/枚举键类型在两后端下都回退 JDK 存储（EC 13.0.0 无等价物）。
     */
    @Test
    @DisplayName("插入序、优先队列与枚举映射在两后端下均为 Linked*/PriorityQueue/EnumMap")
    void uniformStorageForLinkedPriorityEnumTypes() {
        for (String backend : new String[]{"eclipse", "jdk"}) {
            CollectionBackends.use(backend);
            Set<String> linkedSet = Wraps.linkedSet();
            assertInstanceOf(WrappedSet.class, linkedSet);
            assertInstanceOf(LinkedHashSet.class, ((WrappedSet<?>) linkedSet).delegate());
            Map<String, String> linkedMap = Wraps.linkedMap();
            assertInstanceOf(WrappedMap.class, linkedMap);
            assertInstanceOf(LinkedHashMap.class, ((WrappedMap<?, ?>) linkedMap).delegate());
            Queue<Integer> heap = Wraps.priorityQueue(2, Comparator.naturalOrder());
            assertInstanceOf(WrappedQueue.class, heap);
            assertInstanceOf(PriorityQueue.class, ((WrappedQueue<?>) heap).delegate());
            Map<java.util.concurrent.TimeUnit, String> enumMap =
                    Wraps.enumMap(java.util.concurrent.TimeUnit.class);
            assertInstanceOf(WrappedMap.class, enumMap);
            assertInstanceOf(EnumMap.class, ((WrappedMap<?, ?>) enumMap).delegate());
        }
    }

    /**
     * JDK 后端同样满足 RandomAccess 契约（分装列表声明依赖该前提）。
     */
    @Test
    @DisplayName("jdk 后端下分装列表仍实现 RandomAccess")
    void randomAccessHoldsUnderJdkBackend() {
        CollectionBackends.use("jdk");
        assertTrue(Wraps.list() instanceof java.util.RandomAccess);
        assertTrue(Wraps.list(4) instanceof java.util.RandomAccess);
    }

    /**
     * 分装对象透出的后端工厂与 CollectionBackends 一致。
     */
    @Test
    @DisplayName("Wraps.backend()/backendName() 与 CollectionBackends 状态一致")
    void wrapsExposesBackend() {
        assertEquals(CollectionBackends.get(), Wraps.backend());
        assertEquals(CollectionBackends.name(), Wraps.backendName());
    }
}
