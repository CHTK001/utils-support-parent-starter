package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.callback.PipelineListener;
import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.exception.PipelineException;
import com.chua.common.support.task.pipeline.node.DecisionNode;
import com.chua.common.support.task.pipeline.node.EndNode;
import com.chua.common.support.task.pipeline.node.SubPipelineNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认流水线实现。
 *
 * <p>流水线引擎的核心实现，负责节点调度、动作处理、循环检测、回调触发和 B+ 树打印。</p>
 *
 * <p><strong>执行流程：</strong></p>
 * <ol>
 *   <li>从起始节点开始，根据 {@code nextNodeId} 依次执行节点</li>
 *   <li>每个节点执行前触发 {@link PipelineListener#beforeNode}</li>
 *   <li>节点执行后根据 {@link Action} 决定下一步：NEXT 按序前进、PREV 回退、JUMP 跳转等</li>
 *   <li>节点未指定 nextNodeId 时，按添加顺序自动前进到下一节点</li>
 *   <li>循环检测：节点重复执行时抛出 {@link PipelineException}（REPLAY 动作除外）</li>
 *   <li>执行 {@link EndNode} 或到达终止节点 ID 时结束</li>
 * </ol>
 *
 * @author CH
 */
public class DefaultPipeline implements Pipeline {

    /**
     * 流水线唯一标识
     */
    private final String id;

    /**
     * 起始节点 ID
     */
    private final String startNodeId;

    /**
     * 终止节点 ID
     */
    private final String endNodeId;

    /**
     * 节点 ID -&gt; 节点实例的映射
     */
    private final Map<String, PipelineNode> nodeMap;

    /**
     * 有序节点列表，用于确定默认执行顺序
     */
    private final List<PipelineNode> orderedNodes;

    /**
     * 全局回调监听器列表
     */
    private final List<PipelineListener> listeners;

    /**
     * 节点拓扑边集合，用于 B+ 树打印
     */
    private final Map<String, List<Edge>> flowTree;

    /**
     * 被判断节点分支指向的目标节点 ID 集合，构建树时跳过这些节点的默认边
     */
    private final Set<String> decisionTargets;

    /**
     * 构造默认流水线。
     *
     * @param id           流水线 ID
     * @param startNodeId  起始节点 ID
     * @param endNodeId    终止节点 ID
     * @param nodeMap      节点 ID 映射
     * @param orderedNodes 有序节点列表
     * @param listeners    全局回调监听器
     */
    public DefaultPipeline(String id, String startNodeId, String endNodeId,
                           Map<String, PipelineNode> nodeMap,
                           List<PipelineNode> orderedNodes,
                           List<PipelineListener> listeners) {
        this.id = id;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.nodeMap = nodeMap;
        this.orderedNodes = orderedNodes;
        this.listeners = listeners;
        this.decisionTargets = new HashSet<>();
        this.flowTree = buildFlowTree();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public <T> PipelineContext<T> execute(T input) {
        PipelineContext<T> ctx = new PipelineContext<>(id, input);
        if (startNodeId != null) {
            ctx.setNextNodeId(startNodeId);
        }
        run(ctx);
        return ctx;
    }

    @Override
    public <T> PipelineContext<T> resume(PipelineContext<T> ctx) {
        ctx.setAction(Action.NEXT);
        run(ctx);
        return ctx;
    }

    /**
     * 单次 execute/resume 的最大节点执行次数，防止无限循环
     */
    private static final int MAX_EXECUTION_DEPTH = 1000;

    /**
     * 流水线核心执行循环。
     *
     * @param ctx 流水线上下文
     * @param <T> 数据类型
     */
    @SuppressWarnings("unchecked")
    private <T> void run(PipelineContext<T> ctx) {
        int depth = 0;
        try {
            while (ctx.getAction() != Action.EXIT) {
                if (++depth > MAX_EXECUTION_DEPTH) {
                    throw new PipelineException("Execution depth exceeded " + MAX_EXECUTION_DEPTH + ", possible infinite loop");
                }
                String nodeId = ctx.getNextNodeId();
                if (nodeId == null) {
                    break;
                }

                PipelineNode node = nodeMap.get(nodeId);
                if (node == null) {
                    throw new PipelineException("Node not found: " + nodeId);
                }

                if (ctx.getAction() != Action.REPLAY && ctx.getHistory().contains(nodeId)) {
                    throw new PipelineException("Cycle detected, node already executed: " + nodeId);
                }

                if (ctx.getAction() == Action.REPLAY) {
                    ctx.getHistory().remove(nodeId);
                    ctx.setAction(Action.NEXT);
                }

                ctx.setCurrentNodeId(nodeId);
                fireBeforeNode(ctx);
                String prevNextId = ctx.getNextNodeId();

                try {
                    node.execute(ctx);
                } catch (Exception e) {
                    fireOnError(ctx, e);
                    throw new PipelineException("Node execution failed: " + nodeId, e);
                }

                ctx.addHistory(nodeId);
                fireAfterNode(ctx);

                if (ctx.getAction() == Action.EXIT || ctx.getAction() == Action.WAIT) {
                    break;
                }

                if (ctx.getAction() == Action.REPLAY) {
                    ctx.setNextNodeId(ctx.getCurrentNodeId());
                } else if (ctx.getAction() == Action.PREV) {
                    ctx.setAction(Action.NEXT);
                    List<String> history = ctx.getHistory();
                    String currId = ctx.getCurrentNodeId();
                    history.remove(currId);
                    if (history.size() >= 1) {
                        String prevId = history.remove(history.size() - 1);
                        ctx.setNextNodeId(prevId);
                    } else {
                        ctx.setNextNodeId(null);
                    }
                } else if (ctx.getAction() == Action.JUMP) {
                    ctx.setAction(Action.NEXT);
                } else if (Action.NEXT.equals(ctx.getAction()) && Objects.equals(prevNextId, ctx.getNextNodeId())) {
                    if (decisionTargets.contains(nodeId)) {
                        ctx.setNextNodeId(null);
                    } else {
                        String nextId = getNextNodeIdInOrder(nodeId);
                        ctx.setNextNodeId(nextId);
                    }
                }

                if (endNodeId != null && nodeId.equals(endNodeId)) {
                    ctx.setAction(Action.EXIT);
                    break;
                }
            }

            fireOnComplete(ctx);
        } catch (PipelineException e) {
            throw e;
        } catch (Exception e) {
            fireOnError(ctx, e);
            throw new PipelineException("Pipeline execution failed", e);
        }
    }

    /**
     * 在有序节点列表中查找当前节点的下一个节点。
     *
     * @param currentNodeId 当前节点 ID
     * @return 下一节点 ID，不存在时返回 null
     */
    private String getNextNodeIdInOrder(String currentNodeId) {
        if (orderedNodes == null) {
            return null;
        }
        boolean found = false;
        for (PipelineNode node : orderedNodes) {
            String nid = nodeId(node);
            if (found) {
                return nid;
            }
            if (Objects.equals(nid, currentNodeId)) {
                found = true;
            }
        }
        return null;
    }

    /**
     * 获取节点 ID。
     *
     * @param node 节点实例
     * @return 节点 ID
     */
    static String nodeId(PipelineNode node) {
        if (node instanceof DecisionNode) {
            return ((DecisionNode) node).getId();
        }
        if (node instanceof com.chua.common.support.task.pipeline.node.TaskNode) {
            return ((com.chua.common.support.task.pipeline.node.TaskNode) node).getId();
        }
        if (node instanceof EndNode) {
            return ((EndNode) node).getId();
        }
        if (node instanceof com.chua.common.support.task.pipeline.node.StartNode) {
            return ((com.chua.common.support.task.pipeline.node.StartNode) node).getId();
        }
        if (node instanceof SubPipelineNode) {
            return ((SubPipelineNode) node).getId();
        }
        return node.getClass().getSimpleName();
    }

    /**
     * 拓扑边，表示节点间的连接关系。
     */
    static class Edge {

        /**
         * 源节点 ID
         */
        final String from;

        /**
         * 目标节点 ID
         */
        final String to;

        /**
         * 边标签，如 "true"、"false"、"sub"
         */
        final String label;

        Edge(String from, String to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }

    /**
     * 构建节点拓扑边集合，用于 B+ 树打印。
     *
     * @return 节点 ID -&gt; 出边列表的映射
     */
    private Map<String, List<Edge>> buildFlowTree() {
        Map<String, List<Edge>> tree = new LinkedHashMap<>();

        decisionTargets.clear();
        for (PipelineNode node : orderedNodes) {
            if (node instanceof DecisionNode) {
                DecisionNode dn = (DecisionNode) node;
                dn.getBranches().values().forEach(decisionTargets::add);
            }
        }

        if (orderedNodes == null) {
            return tree;
        }
        for (int i = 0; i < orderedNodes.size(); i++) {
            PipelineNode node = orderedNodes.get(i);
            String nid = nodeId(node);
            String defaultNext = (i + 1 < orderedNodes.size()) ? nodeId(orderedNodes.get(i + 1)) : null;

            if (node instanceof DecisionNode) {
                DecisionNode dn = (DecisionNode) node;
                dn.getBranches().forEach((result, nextId) ->
                    tree.computeIfAbsent(nid, k -> new ArrayList<>())
                        .add(new Edge(nid, nextId, result ? "true" : "false")));
            } else if (node instanceof EndNode) {
                continue;
            } else if (node instanceof SubPipelineNode) {
                SubPipelineNode sn = (SubPipelineNode) node;
                String subStart = sn.getSubPipelineStartId();
                tree.computeIfAbsent(nid, k -> new ArrayList<>())
                    .add(new Edge(nid, subStart, "sub"));
                if (defaultNext != null) {
                    String subEnd = sn.getSubPipelineEndId();
                    tree.computeIfAbsent(subEnd, k -> new ArrayList<>())
                        .add(new Edge(subEnd, defaultNext, ""));
                }
            } else {
                if (defaultNext != null && !decisionTargets.contains(nid)) {
                    tree.computeIfAbsent(nid, k -> new ArrayList<>())
                        .add(new Edge(nid, defaultNext, ""));
                }
            }

            if (endNodeId != null && Objects.equals(nid, endNodeId)) {
                tree.computeIfAbsent(nid, k -> new ArrayList<>());
            }
        }
        return tree;
    }

    @Override
    public void printTree(List<String> history) {
        Set<String> executed = history != null ? new HashSet<>(history) : Collections.emptySet();
        System.out.println("Pipeline: " + id);
        printNode(startNodeId, "", true, executed);
    }

    /**
     * 递归打印节点树。
     *
     * @param nodeId   当前节点 ID
     * @param prefix   行前缀
     * @param isLast   是否为同级最后一个节点
     * @param executed 已执行节点 ID 集合
     */
    private void printNode(String nodeId, String prefix, boolean isLast, Set<String> executed) {
        if (nodeId == null) {
            return;
        }

        String marker = executed.contains(nodeId) ? " *" : "";
        String connector = isLast ? "└── " : "├── ";
        System.out.println(prefix + connector + nodeId + marker);

        List<Edge> edges = flowTree.get(nodeId);
        if (edges == null || edges.isEmpty()) {
            return;
        }

        String childPrefix = prefix + (isLast ? "    " : "│   ");
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            boolean lastEdge = (i == edges.size() - 1);

            String mark = executed.contains(edge.to) ? " *" : "";

            PipelineNode childNode = nodeMap.get(edge.to);
            if (childNode instanceof SubPipelineNode) {
                String conn = lastEdge ? "└── " : "├── ";
                System.out.println(childPrefix + conn + edge.to + mark);
                SubPipelineNode sn = (SubPipelineNode) childNode;
                String childChildPrefix = childPrefix + (lastEdge ? "    " : "│   ");
                System.out.println(childChildPrefix + "└── [sub] " + sn.getSubPipelineId());
            } else {
                printNode(edge.to, childPrefix, lastEdge, executed);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Pipeline{id='").append(id).append("', nodes=[");
        sb.append(orderedNodes.stream().map(n -> {
            String nid = nodeId(n);
            List<Edge> edges = flowTree.get(nid);
            if (edges != null && !edges.isEmpty()) {
                return nid + " -> " + edges.stream()
                    .map(e -> e.to + (e.label.isEmpty() ? "" : "[" + e.label + "]"))
                    .collect(Collectors.joining(", "));
            }
            return nid;
        }).collect(Collectors.joining(", ")));
        sb.append("]}");
        return sb.toString();
    }

    private void fireBeforeNode(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.beforeNode(ctx);
        }
    }

    private void fireAfterNode(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.afterNode(ctx);
        }
    }

    private void fireOnError(PipelineContext<?> ctx, Throwable e) {
        for (PipelineListener listener : listeners) {
            listener.onError(ctx, e);
        }
    }

    private void fireOnComplete(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.onComplete(ctx);
        }
    }
}
