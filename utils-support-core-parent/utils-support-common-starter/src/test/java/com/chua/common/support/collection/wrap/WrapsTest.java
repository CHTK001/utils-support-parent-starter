package com.chua.common.support.collection.wrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.RandomAccess;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分装对象语义测试：钉住「实现 java.util 接口、与原生集合互等、视图实时、
 * 序列化往返、相等性/字符串化不被分装改变」这几条兼容性契约。
 *
 * <p>分装体系的动机是省内存（底层默认换成 Eclipse Collections 存储），但前提是
 * 既有代码把分装对象当普通 {@code java.util} 集合使用时行为完全一致——
 * 任何一处 equals/视图/迭代顺序偏差都会在下游表现为隐蔽 bug，
 * 因此这里对每种类型逐一验证，并覆盖大量元素（触发底层扩容）路径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Wraps
 */
class WrapsTest {

    /**
     * 列表：增删改查、子表、与原生 ArrayList 互等且 toString 一致。
     */
    @Test
    @DisplayName("列表分装满足 List 契约并与 ArrayList 互等")
    void listContract() {
        List<String> list = Wraps.list();
        assertTrue(list instanceof WrappedList);
        assertTrue(list instanceof RandomAccess, "分装列表必须保留 RandomAccess 特征");

        list.add("a");
        list.add("b");
        list.add(1, "x");
        assertEquals(List.of("a", "x", "b"), list);
        assertEquals("x", list.get(1));
        assertEquals(1, list.indexOf("x"));
        assertEquals("b", list.set(2, "c"));
        assertTrue(list.remove("a"));
        assertEquals(List.of("x", "c"), list);

        List<String> raw = new ArrayList<>(List.of("x", "c"));
        assertEquals(list, raw);
        assertEquals(raw, list, "equals 必须双向成立");
        assertEquals(raw.hashCode(), list.hashCode());
        assertEquals(raw.toString(), list.toString());
        assertEquals(raw, list.subList(0, 2));

        // 大量元素触发底层扩容路径
        List<Integer> big = Wraps.list();
        for (int i = 0; i < 10_000; i++) {
            big.add(i);
        }
        assertEquals(10_000, big.size());
        assertEquals(9_999, big.get(9_999));
        big.removeIf(v -> v % 2 == 0);
        assertEquals(5_000, big.size());
    }

    /**
     * 集合：增删查、与原生 HashSet 互等且 hashCode 一致。
     */
    @Test
    @DisplayName("集合分装满足 Set 契约并与 HashSet 互等")
    void setContract() {
        Set<String> set = Wraps.set();
        assertTrue(set instanceof WrappedSet);
        assertTrue(set.add("a"));
        assertFalse(set.add("a"), "重复元素必须收敛");
        set.add("b");
        assertEquals(2, set.size());
        assertTrue(set.contains("a"));
        assertTrue(set.remove("b"));
        assertEquals(1, set.size());

        Set<String> raw = new HashSet<>(List.of("a"));
        assertEquals(set, raw);
        assertEquals(raw, set);
        assertEquals(raw.hashCode(), set.hashCode());
        assertEquals(raw.toString(), set.toString());

        Set<Integer> big = Wraps.set();
        for (int i = 0; i < 10_000; i++) {
            big.add(i);
        }
        assertEquals(10_000, big.size());
        assertTrue(big.contains(5_432));
        assertFalse(big.contains(-1));
    }

    /**
     * 映射：增删改、键集/值集/条目集实时视图、与原生 HashMap 互等。
     */
    @Test
    @DisplayName("映射分装满足 Map 契约、视图实时且与 HashMap 互等")
    void mapContract() {
        Map<String, Integer> map = Wraps.map();
        assertTrue(map instanceof WrappedMap);
        map.put("a", 1);
        map.put("b", 2);
        assertEquals(2, map.size());
        assertEquals(1, map.get("a"));
        assertEquals(10, map.getOrDefault("z", 10));
        assertEquals(1, map.remove("a"));

        // 视图实时性
        map.put("c", 3);
        assertTrue(map.keySet().remove("b"));
        assertFalse(map.containsKey("b"), "键集视图的修改必须作用于本映射");
        assertTrue(map.values().remove(3));
        assertFalse(map.containsKey("c"), "值集视图的修改必须作用于本映射");
        map.computeIfAbsent("d", k -> 40);
        assertEquals(40, map.get("d"));
        assertEquals(1, map.size());

        Map<String, Integer> raw = new HashMap<>(map);
        assertEquals(map, raw);
        assertEquals(raw, map);
        assertEquals(raw.hashCode(), map.hashCode());
        assertEquals(raw.toString(), map.toString());

        Map<Integer, Integer> big = Wraps.map();
        for (int i = 0; i < 10_000; i++) {
            big.put(i, i * 2);
        }
        assertEquals(10_000, big.size());
        assertEquals(19_998, big.get(9_999));
        assertEquals(10_000, big.entrySet().size());
    }

    /**
     * 队列：FIFO 行为与相等性语义透传（ArrayDeque 为同一性比较，分装不得改变）。
     */
    @Test
    @DisplayName("队列分装保持 FIFO 行为与底层相等性语义")
    void queueContract() {
        Queue<String> queue = Wraps.queue();
        assertTrue(queue instanceof WrappedQueue);
        assertTrue(queue.offer("a"));
        queue.add("b");
        assertEquals(2, queue.size());
        assertEquals("a", queue.peek());
        assertEquals("a", queue.poll());
        assertEquals("b", queue.element());
        assertEquals("b", queue.remove());
        assertTrue(queue.isEmpty());

        // ArrayDeque 相等性是同一性比较：内容相同但不同实例不相等，toString 仍是内容
        Queue<String> q1 = Wraps.queue();
        q1.add("x");
        Queue<String> q2 = Wraps.queue();
        q2.add("x");
        assertEquals(q1, q1, "自反");
        assertNotEquals(q1, q2, "与底层存储一致：不同实例内容相同也不相等");
        assertEquals("[x]", q1.toString());
        assertEquals(q1.hashCode(), ((WrappedQueue<?>) q1).delegate().hashCode());
    }

    /**
     * 双端队列：两端操作、push/pop、逆序视图与降序迭代。
     */
    @Test
    @DisplayName("双端队列分装支持两端操作、逆序视图与降序迭代")
    void dequeContract() {
        Deque<String> deque = Wraps.deque();
        assertTrue(deque instanceof WrappedDeque);
        deque.add("b");
        deque.addFirst("a");
        deque.addLast("c");
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(deque));
        assertEquals("a", deque.getFirst());
        assertEquals("c", deque.getLast());
        assertEquals("a", deque.pollFirst());
        assertEquals("c", deque.pollLast());
        deque.push("p");
        assertEquals("p", deque.pop());
        assertEquals(List.of("b"), new ArrayList<>(deque));

        deque.add("x");
        deque.add("y");
        assertEquals(List.of("b", "x", "y"), new ArrayList<>(deque));
        assertEquals(List.of("y", "x", "b"), drain(deque.descendingIterator()),
                "降序迭代必须从尾到头");
        assertEquals("[y, x, b]", deque.reversed().toString(), "逆序视图与底层语义一致");

        // 相等性透传（同队列语义：同一性）
        Deque<String> d1 = Wraps.deque();
        d1.add("k");
        Deque<String> d2 = Wraps.deque();
        d2.add("k");
        assertNotEquals(d1, d2);
        assertEquals(d1, d1);
    }

    /**
     * 有序集合：排序、首尾、区间视图（视图共享底层存储）。
     */
    @Test
    @DisplayName("有序集合分装支持排序、首尾与共享存储的区间视图")
    void sortedSetContract() {
        SortedSet<String> set = Wraps.sortedSet();
        assertTrue(set instanceof WrappedSortedSet);
        assertEquals(null, set.comparator(), "自然序比较器为 null");
        set.addAll(List.of("b", "a", "c", "a"));
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(set));
        assertEquals("a", set.first());
        assertEquals("c", set.last());

        SortedSet<String> head = set.headSet("b");
        assertEquals(List.of("a"), new ArrayList<>(head));
        head.add("aa");
        assertTrue(set.contains("aa"), "区间视图必须共享底层存储");
        assertEquals(List.of("a", "aa", "b", "c"), new ArrayList<>(set.tailSet("a")));
        assertEquals(List.of("aa", "b"), new ArrayList<>(set.subSet("aa", "c")));

        // 与原生 TreeSet 按 Set 契约互等
        Set<String> raw = new TreeSet<>(List.of("a", "aa", "b", "c"));
        assertEquals(raw, set);
        assertEquals(raw.hashCode(), set.hashCode());

        // 比较器排序
        SortedSet<Integer> desc = Wraps.sortedSet(Comparator.reverseOrder());
        desc.addAll(List.of(1, 3, 2));
        assertEquals(List.of(3, 2, 1), new ArrayList<>(desc));
        assertEquals(Comparator.reverseOrder(), desc.comparator());
    }

    /**
     * 有序映射：排序、首尾键、区间视图（视图共享底层存储）。
     */
    @Test
    @DisplayName("有序映射分装支持排序、首尾键与共享存储的区间视图")
    void sortedMapContract() {
        SortedMap<String, Integer> map = Wraps.sortedMap();
        assertTrue(map instanceof WrappedSortedMap);
        map.put("b", 2);
        map.put("a", 1);
        map.put("c", 3);
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(map.keySet()));
        assertEquals("a", map.firstKey());
        assertEquals("c", map.lastKey());
        assertEquals(List.of("a", "b"), new ArrayList<>(map.headMap("c").keySet()));
        assertEquals(List.of("b", "c"), new ArrayList<>(map.tailMap("b").keySet()));
        map.headMap("b").put("aa", 26);
        assertEquals(26, map.get("aa"), "区间视图必须共享底层存储");
        Map<String, Integer> raw = new TreeMap<>(map);
        assertEquals(raw, map);
        assertEquals(raw.hashCode(), map.hashCode());
    }

    /**
     * wrap 幂等（已是分装对象原样返回）与 unwrap 解包。
     */
    @Test
    @DisplayName("wrap 幂等、unwrap 还原底层存储")
    void wrapIdempotentAndUnwrap() {
        List<String> list = Wraps.list();
        assertSame(list, Wraps.wrap(list));
        List<String> wrappedRaw = Wraps.wrap(new ArrayList<>());
        assertInstanceOf(WrappedList.class, wrappedRaw, "原生列表必须被分装");
        assertInstanceOf(ArrayList.class, ((WrappedList<?>) wrappedRaw).delegate());

        Set<String> set = Wraps.set();
        assertSame(set, Wraps.wrap(set));

        Map<String, String> map = Wraps.map();
        assertSame(map, Wraps.wrap(map));

        SortedSet<String> sortedSet = Wraps.sortedSet();
        assertSame(sortedSet, Wraps.wrap(sortedSet));

        SortedMap<String, String> sortedMap = Wraps.sortedMap();
        assertSame(sortedMap, Wraps.wrap(sortedMap));

        // unwrap：分装对象还原底层存储；非分装对象原样返回
        Object rawList = Wraps.unwrap(list);
        assertInstanceOf(RandomAccess.class, rawList);
        assertFalse(rawList instanceof WrappedList);
        assertEquals(list, rawList);
        String plain = "x";
        assertSame(plain, Wraps.unwrap(plain));

        // 分装既有集合
        List<String> legacy = Wraps.wrap(new ArrayList<>(List.of("a")));
        assertInstanceOf(WrappedList.class, legacy);
        assertEquals(List.of("a"), legacy);
    }

    /**
     * 序列化往返：7 种分装类型写出后读回仍是分装对象且内容不变。
     *
     * @throws Exception 序列化失败
     */
    @Test
    @DisplayName("7 种分装类型序列化往返后仍为分装对象且内容不变")
    void serializationRoundTrip() throws Exception {
        List<String> list = Wraps.list();
        list.add("a");
        list.add("b");
        List<String> list2 = round(list);
        assertInstanceOf(WrappedList.class, list2);
        assertEquals(list, list2);

        Set<String> set = Wraps.set();
        set.add("p");
        Set<String> set2 = round(set);
        assertInstanceOf(WrappedSet.class, set2);
        assertEquals(set, set2);

        Map<String, Integer> map = Wraps.map();
        map.put("x", 1);
        Map<String, Integer> map2 = round(map);
        assertInstanceOf(WrappedMap.class, map2);
        assertEquals(map, map2);

        Queue<String> queue = Wraps.queue();
        queue.add("q1");
        queue.add("q2");
        Queue<String> queue2 = round(queue);
        assertInstanceOf(WrappedQueue.class, queue2);
        assertEquals("q1", queue2.peek(), "往返后元素顺序保持");
        assertEquals("q1", queue2.poll());
        assertEquals("q2", queue2.poll());

        Deque<String> deque = Wraps.deque();
        deque.add("d1");
        Deque<String> deque2 = round(deque);
        assertInstanceOf(WrappedDeque.class, deque2);
        assertEquals("d1", deque2.peekFirst());

        SortedSet<String> sortedSet = Wraps.sortedSet();
        sortedSet.add("s1");
        SortedSet<String> sortedSet2 = round(sortedSet);
        assertInstanceOf(WrappedSortedSet.class, sortedSet2);
        assertEquals(sortedSet, sortedSet2);

        SortedMap<String, String> sortedMap = Wraps.sortedMap();
        sortedMap.put("k", "v");
        SortedMap<String, String> sortedMap2 = round(sortedMap);
        assertInstanceOf(WrappedSortedMap.class, sortedMap2);
        assertEquals(sortedMap, sortedMap2);
    }

    /**
     * 分装对象声明 Serializable 且底层存储可序列化。
     */
    @Test
    @DisplayName("全部分装类型声明 Serializable")
    void wrappersAreSerializable() {
        assertTrue(Wraps.list() instanceof Serializable);
        assertTrue(Wraps.set() instanceof Serializable);
        assertTrue(Wraps.map() instanceof Serializable);
        assertTrue(Wraps.queue() instanceof Serializable);
        assertTrue(Wraps.deque() instanceof Serializable);
        assertTrue(Wraps.sortedSet() instanceof Serializable);
        assertTrue(Wraps.sortedMap() instanceof Serializable);
    }

    /**
     * 工厂的容量/拷贝构造语义与原生构造器一致。
     */
    @Test
    @DisplayName("容量与拷贝工厂语义与原生构造器一致")
    void factoryCopySemantics() {
        List<String> list = Wraps.list(new ArrayList<>(List.of("a", "b")));
        assertEquals(2, list.size());
        list.add("c");
        assertEquals(3, list.size(), "拷贝工厂必须是独立实例而非视图");

        Set<String> set = Wraps.set(java.util.Arrays.asList("x", "x"));
        assertEquals(1, set.size(), "重复元素收敛语义同 HashSet");

        Map<String, Integer> map = Wraps.map(new HashMap<>(Map.of("k", 1)));
        assertEquals(1, map.size());
        map.put("k2", 2);
        assertEquals(2, map.size());

        List<String> withCap = Wraps.list(16);
        withCap.add("z");
        assertEquals(1, withCap.size());
        Map<String, String> mapCap = Wraps.map(8);
        mapCap.put("a", "b");
        assertEquals("b", mapCap.get("a"));
    }

    /**
     * 插入序映射/集合已纳入分装：迭代顺序与原生 LinkedHashMap/LinkedHashSet 一致。
     */
    @Test
    @DisplayName("linkedMap/linkedSet 分装后仍保留插入顺序")
    void insertionOrderWrapped() {
        Map<String, String> linked = Wraps.linkedMap();
        linked.put("b", "2");
        linked.put("a", "1");
        linked.put("b", "22");
        assertEquals(List.of("b", "a"), List.copyOf(linked.keySet()),
                "插入序映射分装后必须保持插入顺序，更新已有键不改变位置");
        assertEquals("22", linked.get("b"));

        Set<String> linkedSet = Wraps.linkedSet();
        linkedSet.add("b");
        linkedSet.add("a");
        linkedSet.add("b");
        assertEquals(List.of("b", "a"), List.copyOf(linkedSet),
                "插入序集合分装后必须保持插入顺序且重复元素收敛");

        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("b", "2");
        raw.put("a", "1");
        raw.put("b", "22");
        assertEquals(new ArrayList<>(raw.keySet()), new ArrayList<>(linked.keySet()),
                "分装与原生 LinkedHashMap 的键迭代顺序一致");
    }

    /**
     * 插入序映射删除后重插移动到末尾（与 LinkedHashMap 语义一致）。
     */
    @Test
    @DisplayName("linkedMap 删除后重插移动到末尾（同 LinkedHashMap）")
    void linkedMapRemoveThenReinsertMovesToEnd() {
        Map<String, Integer> m = Wraps.linkedMap();
        m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);
        m.remove("a");
        assertEquals(List.of("b", "c"), List.copyOf(m.keySet()));
        m.put("a", 4);
        assertEquals(List.of("b", "c", "a"), List.copyOf(m.keySet()));
        assertEquals(4, m.get("a"));
    }

    /**
     * 插入序拷贝工厂按源迭代顺序复制且为独立实例。
     */
    @Test
    @DisplayName("linkedMap/linkedSet 拷贝工厂按源顺序复制")
    void linkedCopySemantics() {
        Map<String, Integer> src = Wraps.linkedMap();
        src.put("y", 2);
        src.put("x", 1);
        Map<String, Integer> copy = Wraps.linkedMap(src);
        assertEquals(List.of("y", "x"), List.copyOf(copy.keySet()));
        copy.put("z", 3);
        assertEquals(2, src.size(), "拷贝工厂必须是独立实例而非视图");

        Set<String> setCopy = Wraps.linkedSet(java.util.Arrays.asList("y", "x", "y"));
        assertEquals(List.of("y", "x"), List.copyOf(setCopy), "重复元素收敛且保持源顺序");
        assertEquals(2, setCopy.size());
    }

    /**
     * 优先队列分装按比较器出队，容量与无参构造同原生 PriorityQueue。
     */
    @Test
    @DisplayName("priorityQueue 分装按比较器出队")
    void priorityQueueContract() {
        Queue<Integer> heap = Wraps.priorityQueue(4, Comparator.reverseOrder());
        heap.offer(1);
        heap.offer(5);
        heap.offer(3);
        assertEquals(3, heap.size());
        assertEquals(5, heap.poll());
        assertEquals(3, heap.poll());
        assertEquals(1, heap.poll());

        Queue<String> natural = Wraps.priorityQueue();
        natural.offer("b");
        natural.offer("a");
        assertEquals("a", natural.poll(), "自然序优先队列堆顶为最小元素");
        assertEquals("b", natural.poll());
    }

    /**
     * 枚举键映射分装：按枚举声明序迭代、常规 Map 操作与 EnumMap 语义一致。
     */
    @Test
    @DisplayName("enumMap 分装按枚举序迭代且支持常规 Map 操作")
    void enumMapContract() {
        Map<SampleEnum, String> m = Wraps.enumMap(SampleEnum.class);
        m.put(SampleEnum.B, "b");
        m.put(SampleEnum.A, "a");
        assertEquals(List.of(SampleEnum.A, SampleEnum.B), List.copyOf(m.keySet()),
                "枚举键映射按枚举声明序迭代");
        m.put(SampleEnum.A, "a2");
        assertEquals("a2", m.get(SampleEnum.A));
        assertFalse(m.containsKey(SampleEnum.C), "未放入的枚举键不存在");
        assertEquals(2, m.size());
        m.remove(SampleEnum.B);
        assertEquals(List.of(SampleEnum.A), List.copyOf(m.keySet()));
    }

    /**
     * 枚举键类型的样例枚举（供 {@link #enumMapContract()} 使用）。
     */
    private enum SampleEnum {
        /** 枚举值 A。 */
        A,
        /** 枚举值 B。 */
        B,
        /** 枚举值 C。 */
        C
    }

    /**
     * 有序映射拷贝工厂按自然序复制（语义同 {@code new TreeMap<>(m)}）。
     */
    @Test
    @DisplayName("sortedMap(Map) 拷贝工厂按自然序复制")
    void sortedMapCopySemantics() {
        Map<String, Integer> src = Wraps.linkedMap();
        src.put("b", 2);
        src.put("a", 1);
        SortedMap<String, Integer> sorted = Wraps.sortedMap(src);
        assertEquals(List.of("a", "b"), List.copyOf(sorted.keySet()), "拷贝结果为自然序");
        assertEquals(2, sorted.get("b"));
        sorted.put("c", 3);
        assertEquals(List.of("a", "b", "c"), List.copyOf(sorted.keySet()));
    }

    /**
     * 序列化往返工具。
     *
     * @param obj 待序列化对象
     * @param <T> 对象类型
     * @return 读回的对象
     * @throws Exception 序列化失败
     */
    @SuppressWarnings("unchecked")
    private static <T> T round(T obj) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            return (T) ois.readObject();
        }
    }

    /**
     * 倒序迭代器取全部元素为列表。
     *
     * @param it 降序迭代器
     * @param <E> 元素类型
     * @return 元素列表
     */
    private static <E> List<E> drain(java.util.Iterator<E> it) {
        List<E> result = new ArrayList<>();
        while (it.hasNext()) {
            result.add(it.next());
        }
        return result;
    }

    /**
     * 数组双端队列与分装双端队列互操作（相等性透传解包）。
     */
    @Test
    @DisplayName("分装双端队列与原生 ArrayDeque 的 toString 一致")
    void dequeToStringMatchesRaw() {
        Deque<String> wrapped = Wraps.deque();
        wrapped.add("a");
        wrapped.add("b");
        ArrayDeque<String> raw = new ArrayDeque<>(List.of("a", "b"));
        assertEquals(raw.toString(), wrapped.toString());
        assertEquals(raw.size(), wrapped.size());
    }
}
