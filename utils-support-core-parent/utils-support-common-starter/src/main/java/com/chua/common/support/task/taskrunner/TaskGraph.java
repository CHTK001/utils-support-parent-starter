package com.chua.common.support.task.taskrunner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
   * 任务拓扑图 — 承载 任务runner 的 DAG 结构并完成合法性校验。
 *
 * <p>职责：</p>
 * <ul>
 *   <li><strong>校验</strong>：任务非空、节点 ID 唯一、依赖必须存在</li>
 *   <li><strong>分层</strong>：Kahn 算法输出拓扑分层，第 0 层为无依赖的最外层节点
 *       （并行执行的基础），存在环时抛出异常并指明环路涉及节点</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TaskGraph {

    /**
     * 运行名称
     */
    private final String name;

    /**
      * 注册顺序保持的节点定义表：标识 -> definition
     */
    private final Map<String, TaskDefinition> definitions = new LinkedHashMap<>();

    /**
     * 创建任务拓扑图。
     *
     * @param name        运行名称，不为空
     * @param definitions 节点定义集合，非空且 标识 唯一、依赖完整
     * @return 拓扑图实例
     * @throws IllegalArgumentException 当集合为空、标识 重复或依赖缺失时
     */
    public static TaskGraph of(String name, Collection<TaskDefinition> definitions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("运行名称不能为空");
        }
        Objects.requireNonNull(definitions, "definitions must not be null");
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("至少注册一个任务节点");
        }
        var graph = new TaskGraph(name);
        for (var def : definitions) {
            Objects.requireNonNull(def, "definition 不能为 null");
            var previous = graph.definitions.putIfAbsent(def.getId(), def);
            if (previous != null) {
                throw new IllegalArgumentException("节点 id 重复: " + def.getId());
            }
        }
        graph.validateDependencies();
        return graph;
    }

    /**
     * 私有构造，统一经 {@link #of(String, Collection)} 创建。
     *
     * @param name 运行名称
     */
    private TaskGraph(String name) {
        this.name = name;
    }

    /**
     * 校验全部依赖指向已注册节点。
     *
     * @throws IllegalArgumentException 当存在未注册的依赖时
     */
    private void validateDependencies() {
        for (var def : definitions.values()) {
            for (var dep : def.getDependencies()) {
                if (!definitions.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "节点 " + def.getId() + " 依赖的 " + dep + " 未注册");
                }
                if (dep.equals(def.getId())) {
                    throw new IllegalArgumentException("节点 " + dep + " 存在自依赖");
                }
            }
        }
    }

    /**
     * Kahn 算法拓扑分层。
     *
     * <p>返回值第 i 层的所有节点仅依赖前 i-1 层节点；同层节点可安全并行执行。</p>
     *
     * @return 分层结果，保证非空
     * @throws IllegalStateException 当存在循环依赖时，消息中列出环内节点
     */
    public List<List<TaskDefinition>> layeredTopology() {
        var inDegree = new HashMap<String, Integer>();
        var dependents = new HashMap<String, List<String>>();
        for (var def : definitions.values()) {
            inDegree.putIfAbsent(def.getId(), 0);
            for (var dep : def.getDependencies()) {
                inDegree.merge(def.getId(), 1, Integer::sum);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(def.getId());
            }
        }

        var initial = new ArrayList<String>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                initial.add(id);
            }
        });
        var currentLayer = initial;

        var layers = new ArrayList<List<TaskDefinition>>();
        var resolved = 0;
        while (!currentLayer.isEmpty()) {
            var nextLayer = new ArrayList<String>();
            var layerDefs = new ArrayList<TaskDefinition>();
            for (var id : currentLayer) {
                layerDefs.add(definitions.get(id));
                resolved++;
                for (var dependent : dependents.getOrDefault(id, List.of())) {
                    var remaining = inDegree.merge(dependent, -1, Integer::sum);
                    if (remaining == 0) {
                        nextLayer.add(dependent);
                    }
                }
            }
            layers.add(List.copyOf(layerDefs));
            currentLayer = nextLayer;
        }

        if (resolved != definitions.size()) {
            var cyclic = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            throw new IllegalStateException("存在循环依赖，涉及节点: " + cyclic);
        }
        return layers;
    }

    /**
      * 获取全部节点 标识（注册顺序）。
     *
     * @return 节点 标识 集合
     */
    public List<String> nodeIds() {
        return List.copyOf(definitions.keySet());
    }

    /**
     * 输出按拓扑分层展平后的建议执行顺序（同层保持注册顺序）。
     *
     * <p>分层过程同时承担环检测职责；调用方无需再单独校验环路。</p>
     *
     * @return 拓扑序节点定义列表
     * @throws IllegalStateException 当存在循环依赖时
     */
    public List<TaskDefinition> definitionsInExecutionOrder() {
        var flattened = new ArrayList<TaskDefinition>(definitions.size());
        layeredTopology().forEach(flattened::addAll);
        return List.copyOf(flattened);
    }

    /**
     * 获取运行名称。
     *
     * @return 运行名称
     */
    public String getName() {
        return name;
    }
}
