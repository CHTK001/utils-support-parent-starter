package com.chua.common.support.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 树操作工具集 — 提供平铺列表与树结构互转、查找、路径、兄弟、排序、扁平化的通用能力。
 *
 * <p>设计为<strong>函数式访问器</strong>风格：不绑定任何具体节点模型，
 * 通过 {@link Function}/{@link BiConsumer} 访问任意节点的 ID、父 ID 与子节点列表，
 * 可直接适配实体类、DTO、record 等任意结构。</p>
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>{@link #build} — 平铺列表 → 树（自动识别根、孤儿归根、重复 ID 与循环引用快速失败）</li>
 *   <li>{@link #findNode} / {@link #findById} — 按条件或按 ID 查找节点</li>
 *   <li>{@link #getDirectChildren} / {@link #getAllChildren} — 直接子节点 / 全部后代</li>
 *   <li>{@link #getParents} / {@link #getParentChain} — 父链（直接父→根） / 完整路径（根→直接父）</li>
 *   <li>{@link #getSiblings} — 兄弟节点（排除自身；根节点的兄弟即其他根）</li>
 *   <li>{@link #sortTree} — 递归排序整棵树（就地排序各层子列表）</li>
 *   <li>{@link #flatten} — 树 → 平铺列表（DFS 前序）</li>
 *   <li>{@link #getDepth} — 节点深度（根为 0）</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * class Dept {
 *     Long id; Long parentId; List<Dept> children;
 * }
 *
 * List<Dept> roots = TreeUtils.build(deptList,
 *         d -> d.id, d -> d.parentId,
 *         (dept, children) -> dept.children = children);
 *
 * Dept found = TreeUtils.findById(roots, 42L, d -> d.id, d -> d.children);
 * List<Dept> ancestors = TreeUtils.getParents(roots, found, d -> d.children);
 * }</pre>
 *
 * <p>约定与限制：</p>
 * <ul>
 *   <li>路径/父链/兄弟类方法对<strong>目标节点按引用（==）定位</strong>；
 *       按 ID 定位请先使用 {@link #findById}</li>
 *   <li>全部遍历均为显式栈迭代实现，不受树深限制；
 *       但传入含环结构时行为未定义（可能死循环）——
 *       请保证数据无环，{@link #build} 已内置环检测</li>
 *   <li>children 访问器返回 null 时一律按空列表处理</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TreeUtils {

    /**
     * 防止实例化工具类。
     */
    private TreeUtils() {
    }

    /**
     * DFS 遍历帧：记录当前节点、其子列表与已消费的游标位置。
     *
     * @param <T> 节点类型
     */
    private record Frame<T>(T node, List<T> children, int cursor) {
    }

    // ==================== 构建 ====================

    /**
     * 将平铺列表构建为树。
     *
     * <p>根节点判定规则：parentId 为 null，或 parentId 在集合中不存在对应 ID
     * （孤儿节点归根，便于容忍残缺数据）。各层子节点保持入参相对顺序，
     * 通过 {@code childrenSetter} 写回节点本体。</p>
     *
     * @param items          平铺节点列表，不为 null
     * @param idGetter       节点 ID 访问器
     * @param parentIdGetter 父 ID 访问器
     * @param childrenSetter 子节点列表写入器（把构建好的 children 挂到节点上）
     * @param <T>            节点类型
     * @param <I>            ID 类型
     * @return 根节点列表（保持入参中的相对顺序）
     * @throws IllegalArgumentException 当存在重复节点 ID 时
     * @throws IllegalStateException    当存在循环引用导致部分节点不可达时
     */
    public static <T, I> List<T> build(Collection<T> items,
                                       Function<T, I> idGetter,
                                       Function<T, I> parentIdGetter,
                                       BiConsumer<T, List<T>> childrenSetter) {
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(idGetter, "idGetter must not be null");
        Objects.requireNonNull(parentIdGetter, "parentIdGetter must not be null");
        Objects.requireNonNull(childrenSetter, "childrenSetter must not be null");

        Map<I, T> idIndex = new LinkedHashMap<>();
        for (var item : items) {
            var id = idGetter.apply(item);
            if (idIndex.putIfAbsent(id, item) != null) {
                throw new IllegalArgumentException("存在重复节点 ID: " + id);
            }
        }

        Map<I, List<I>> adjacency = new LinkedHashMap<>();
        var rootIds = new ArrayList<I>();
        for (var item : items) {
            var id = idGetter.apply(item);
            var parentId = parentIdGetter.apply(item);
            if (parentId == null || !idIndex.containsKey(parentId)) {
                rootIds.add(id);
            } else {
                adjacency.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
            }
        }

        // 写回 children：按父 ID 分组，依序挂载到节点本体
        var roots = new ArrayList<T>(rootIds.size());
        for (var rootId : rootIds) {
            roots.add(idIndex.get(rootId));
        }
        for (var entry : adjacency.entrySet()) {
            var parent = idIndex.get(entry.getKey());
            var kids = new ArrayList<T>(entry.getValue().size());
            for (var childId : entry.getValue()) {
                kids.add(idIndex.get(childId));
            }
            childrenSetter.accept(parent, kids);
        }

        validateReachable(items.size(), rootIds, adjacency);
        return roots;
    }

    /**
     * 环检测：从全部根出发沿邻接表遍历，应能到达每一个节点；
     * 存在不可达节点即判定循环引用并快速失败。
     */
    private static <I> void validateReachable(int totalNodes, Collection<I> rootIds,
                                              Map<I, List<I>> adjacency) {
        Set<I> reached = new HashSet<>();
        Deque<I> stack = new ArrayDeque<>(rootIds);
        while (!stack.isEmpty()) {
            var current = stack.pop();
            if (!reached.add(current)) {
                continue;
            }
            var kids = adjacency.get(current);
            if (kids != null) {
                stack.addAll(kids);
            }
        }
        if (reached.size() != totalNodes) {
            throw new IllegalStateException(
                    "检测到循环引用: " + (totalNodes - reached.size())
                            + " 个节点无法从任何根节点到达");
        }
    }

    // ==================== 查找 ====================

    /**
     * 按条件查找第一个命中的节点（DFS 前序）。
     *
     * @param roots          根节点集合
     * @param matcher        匹配条件
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 命中的节点；未命中返回 null
     */
    public static <T> T findNode(Collection<T> roots, Predicate<? super T> matcher,
                                 Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(roots, "roots must not be null");
        Objects.requireNonNull(matcher, "matcher must not be null");
        for (var root : roots) {
            var hit = findDfs(root, matcher, childrenGetter);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 按 ID 查找节点（ID 使用 equals 比较）。
     *
     * @param roots          根节点集合
     * @param targetId       目标 ID
     * @param idGetter       ID 访问器
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @param <I>            ID 类型
     * @return 命中的节点；未命中返回 null
     */
    public static <T, I> T findById(Collection<T> roots, I targetId,
                                    Function<T, I> idGetter,
                                    Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(idGetter, "idGetter must not be null");
        return findNode(roots, node -> Objects.equals(idGetter.apply(node), targetId),
                childrenGetter);
    }

    /**
     * DFS 迭代查找（显式栈，不受树深限制）。
     */
    private static <T> T findDfs(T node, Predicate<? super T> matcher,
                                 Function<T, List<T>> childrenGetter) {
        Deque<T> stack = new ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            var current = stack.pop();
            if (matcher.test(current)) {
                return current;
            }
            pushReversed(stack, childrenOf(current, childrenGetter));
        }
        return null;
    }

    // ==================== 子节点 / 后代 ====================

    /**
     * 获取直接子节点（空安全）。
     *
     * @param node           目标节点
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 直接子节点列表；无子节点返回空列表
     */
    public static <T> List<T> getDirectChildren(T node, Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(node, "node must not be null");
        return childrenOf(node, childrenGetter);
    }

    /**
     * 获取全部后代节点（不含自身，DFS 前序，迭代实现不受树深限制）。
     *
     * @param node           目标节点
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 后代节点列表；叶子节点返回空列表
     */
    public static <T> List<T> getAllChildren(T node, Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(node, "node must not be null");
        var descendants = new ArrayList<T>();
        Deque<T> stack = new ArrayDeque<>();
        pushReversed(stack, childrenOf(node, childrenGetter));
        while (!stack.isEmpty()) {
            var current = stack.pop();
            descendants.add(current);
            pushReversed(stack, childrenOf(current, childrenGetter));
        }
        return descendants;
    }

    /**
     * 逆序压栈以保持 DFS 前序顺序。
     */
    private static <T> void pushReversed(Deque<T> stack, List<T> children) {
        for (var i = children.size() - 1; i >= 0; i--) {
            stack.push(children.get(i));
        }
    }

    // ==================== 路径 / 父链 / 兄弟 ====================

    /**
     * 获取从根到目标节点的完整路径（含根与目标自身）。
     *
     * <p>目标按<strong>引用（==）</strong>定位。</p>
     *
     * @param roots          根节点集合
     * @param target         目标节点（须为树中实际节点实例）
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 路径列表 [根, ..., 直接父, 目标]；未找到返回空列表
     */
    public static <T> List<T> getParentChain(Collection<T> roots, T target,
                                             Function<T, List<T>> childrenGetter) {
        return locatePath(roots, target, childrenGetter);
    }

    /**
     * 获取父节点列表：从直接父到根（不含自身）。
     *
     * @param roots          根节点集合
     * @param target         目标节点
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 父节点列表 [直接父, ..., 根]；目标为根时返回空列表
     */
    public static <T> List<T> getParents(Collection<T> roots, T target,
                                         Function<T, List<T>> childrenGetter) {
        var path = locatePath(roots, target, childrenGetter);
        var parents = new ArrayList<T>(Math.max(0, path.size() - 1));
        for (var i = path.size() - 2; i >= 0; i--) {
            parents.add(path.get(i));
        }
        return parents;
    }

    /**
     * 获取兄弟节点：同层中除自身外的其他节点。
     *
     * <p>目标为根节点时，兄弟即其余根节点。</p>
     *
     * @param roots          根节点集合
     * @param target         目标节点
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 兄弟节点列表（保持原顺序）
     */
    public static <T> List<T> getSiblings(Collection<T> roots, T target,
                                          Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(target, "target must not be null");
        var path = locatePath(roots, target, childrenGetter);
        if (path.isEmpty()) {
            return List.of();
        }
        List<T> sameLevel = path.size() == 1
                ? List.copyOf(roots)
                : childrenOf(path.get(path.size() - 2), childrenGetter);
        var siblings = new ArrayList<T>(sameLevel.size());
        for (var candidate : sameLevel) {
            if (candidate != target) {
                siblings.add(candidate);
            }
        }
        return siblings;
    }

    /**
     * 获取目标节点深度：根为 0，每下一层加 1。
     *
     * @param roots          根节点集合
     * @param target         目标节点
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 深度值；未找到返回 -1
     */
    public static <T> int getDepth(Collection<T> roots, T target,
                                   Function<T, List<T>> childrenGetter) {
        var path = locatePath(roots, target, childrenGetter);
        return path.isEmpty() ? -1 : path.size() - 1;
    }

    /**
     * 定位从根到目标的路径（含两端），基于显式栈的迭代 DFS，
     * 不受树深限制。目标按引用（==）匹配。
     */
    private static <T> List<T> locatePath(Collection<T> roots, T target,
                                          Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(roots, "roots must not be null");
        Objects.requireNonNull(target, "target must not be null");
        var path = new ArrayList<T>();
        Deque<Frame<T>> stack = new ArrayDeque<>();
        for (var root : roots) {
            path.clear();
            stack.clear();
            path.add(root);
            if (root == target) {
                return List.copyOf(path);
            }
            stack.push(new Frame<>(root, childrenOf(root, childrenGetter), 0));
            while (!stack.isEmpty()) {
                var top = stack.peek();
                if (top.cursor() >= top.children().size()) {
                    stack.pop();
                    path.removeLast();
                    continue;
                }
                var next = top.children().get(top.cursor());
                stack.pop();
                stack.push(new Frame<>(top.node(), top.children(), top.cursor() + 1));
                path.add(next);
                if (next == target) {
                    return List.copyOf(path);
                }
                stack.push(new Frame<>(next, childrenOf(next, childrenGetter), 0));
            }
        }
        return List.of();
    }

    // ==================== 排序 / 扁平化 ====================

    /**
     * 递归排序整棵树：每一层子列表按比较器就地排序（含根层）。
     *
     * <p>要求各层 children 为可变列表（{@link #build} 产出的即为可变列表）；
     * 不可变列表将抛出 {@link UnsupportedOperationException}。</p>
     *
     * @param roots          根节点列表（就地排序）
     * @param comparator     同层比较器
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     */
    public static <T> void sortTree(List<T> roots, Comparator<? super T> comparator,
                                    Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(roots, "roots must not be null");
        Objects.requireNonNull(comparator, "comparator must not be null");
        roots.sort(comparator);
        Deque<T> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            var current = stack.pop();
            var children = childrenOf(current, childrenGetter);
            children.sort(comparator);
            pushReversed(stack, children);
        }
    }

    /**
     * 扁平化为平铺列表（DFS 前序：根 → 其全部后代 → 下一个根）。
     *
     * @param roots          根节点集合
     * @param childrenGetter 子节点访问器
     * @param <T>            节点类型
     * @return 平铺列表（新列表，不影响原树）
     */
    public static <T> List<T> flatten(Collection<T> roots, Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(roots, "roots must not be null");
        var flat = new ArrayList<T>();
        Deque<T> stack = new ArrayDeque<>();
        pushReversed(stack, List.copyOf(roots));
        while (!stack.isEmpty()) {
            var current = stack.pop();
            flat.add(current);
            pushReversed(stack, childrenOf(current, childrenGetter));
        }
        return flat;
    }

    // ==================== 内部辅助 ====================

    /**
     * 空安全的子节点读取：访问器返回 null 时视为空列表。
     */
    private static <T> List<T> childrenOf(T node, Function<T, List<T>> childrenGetter) {
        Objects.requireNonNull(childrenGetter, "childrenGetter must not be null");
        var children = childrenGetter.apply(node);
        return children == null ? List.of() : children;
    }
}
