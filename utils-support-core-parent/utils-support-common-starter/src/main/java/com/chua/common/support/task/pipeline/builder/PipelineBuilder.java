package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.callback.PipelineListener;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.NullUnmarked;

/**
 * 流水线构建器。
 *
 * <p>链式 API 构建流水线，支持添加执行节点、判断节点、子流水线节点，以及注册全局回调。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * Pipeline pipeline = PipelineBuilder.newBuilder("order")
 *     .task("validate", ctx -> validate(ctx.getCurrentData()))
 *     .decision("check", ctx -> ctx.getCurrentData() != null)
 *         .when(true, "process")
 *         .when(false, "error")
 *         .then()
 *     .task("process", ctx -> process(ctx.getCurrentData()))
 *     .task("error", ctx -> log.error("invalid data"))
 *     .addListener(new LoggingListener())
 *     .build();
 *
 * PipelineContext<String> ctx = pipeline.execute("orderData");
 * }</pre>
 *
 * @author CH
 */
@NullUnmarked
public class PipelineBuilder {

    /**
     * 流水线唯一标识
     */
    private final String id;

    /**
     * 按添加顺序排列的节点列表
     */
    private final List<PipelineNode> nodes;

    /**
     * 全局回调监听器列表
     */
    private final List<PipelineListener> listeners;

    /**
     * 节点 ID -&gt; 节点实例的映射
     */
    private final Map<String, PipelineNode> nodeMap;

    /**
     * 起始节点 ID
     */
    private String startNodeId;

    /**
     * 终止节点 ID
     */
    private String endNodeId;

    /**
     * 是否已构建，防止重复调用 build()
     */
    private boolean built;

    /**
     * 私有构造器。
     *
     * @param id 流水线唯一标识
     */
    private PipelineBuilder(String id) {
        this.id = id;
        this.nodes = new ArrayList<>();
        this.listeners = new ArrayList<>();
        this.nodeMap = new LinkedHashMap<>();
        this.startNodeId = null;
        this.endNodeId = null;
    }

    /**
     * 创建流水线构建器，自动生成流水线 ID。
     *
     * @return PipelineBuilder
     */
    public static PipelineBuilder newBuilder() {
        return newBuilder("pipeline-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 创建流水线构建器。
     *
     * @param id 流水线唯一标识
     * @return PipelineBuilder
     */
    public static PipelineBuilder newBuilder(String id) {
        return new PipelineBuilder(id);
    }

    /**
     * 添加执行节点。
     *
     * @param id   节点唯一标识
     * @param task 业务逻辑执行器
     * @return this
     */
    public PipelineBuilder task(String id, Consumer<PipelineContext<?>> task) {
        TaskNode node = new TaskNode(id, task);
        nodes.add(node);
        nodeMap.put(id, node);
        return this;
    }

    /**
     * 添加子流水线节点。
     *
     * @param id          节点唯一标识
     * @param subPipeline 子流水线实例
     * @return this
     */
    public PipelineBuilder pipeline(String id, Pipeline subPipeline) {
        SubPipelineNode node = new SubPipelineNode(id, subPipeline);
        nodes.add(node);
        nodeMap.put(id, node);
        return this;
    }

    /**
     * 添加判断节点。
     *
     * <p>返回 {@link DecisionBuilder} 用于配置分支路径；
     * 完成后需调用 {@link DecisionBuilder#then()} 返回当前构建器。</p>
     *
     * @param id        节点唯一标识
     * @param condition 条件判断逻辑
     * @return DecisionBuilder
     */
    public DecisionBuilder decision(String id, Predicate<PipelineContext<?>> condition) {
        return new DecisionBuilder(id, condition, this);
    }

    /**
     * 注册全局回调监听器。
     *
     * @param listener 监听器实例
     * @return this
     */
    public PipelineBuilder addListener(PipelineListener listener) {
        this.listeners.add(listener);
        return this;
    }

    /**
     * 指定起始节点 ID。
     *
     * <p>不指定时默认以第一个添加的节点作为起始节点。</p>
     *
     * @param id 起始节点 ID
     * @return this
     */
    public PipelineBuilder start(String id) {
        this.startNodeId = id;
        return this;
    }

    /**
     * 指定终止节点 ID。
     *
     * @param id 终止节点 ID
     * @return this
     */
    public PipelineBuilder end(String id) {
        this.endNodeId = id;
        return this;
    }

    /**
     * 添加节点（供其他构建器内部使用）。
     *
     * @param node 节点实例
     */
    void addNode(PipelineNode node) {
        if (node instanceof StartNode) {
            StartNode sn = (StartNode) node;
            nodes.add(sn);
            nodeMap.put(sn.getId(), sn);
        }
    }

    /**
     * 构建流水线。
     *
     * @return 构建完成的 Pipeline 实例
     * @throws IllegalStateException 当未定义任何节点或重复构建时抛出
     */
    public Pipeline build() {
        if (built) {
            throw new IllegalStateException("Pipeline already built");
        }
        built = true;

        if (nodeMap.isEmpty()) {
            throw new IllegalStateException("No nodes defined");
        }

        if (startNodeId == null) {
            startNodeId = nodeMap.keySet().iterator().next();
        }

        return new DefaultPipeline(id, startNodeId, endNodeId, nodeMap, nodes, listeners);
    }

    /**
     * 判断分支构建器。
     *
     * <p>用于 {@link #decision(String, Predicate)} 方法返回的中间构建器；
     * 通过 {@link #when(boolean, String)} 配置分支路径；
     * 最后调用 {@link #then()} 返回父构建器。</p>
     */
    public class DecisionBuilder {

        /**
         * 判断节点 ID
         */
        private final String id;

        /**
         * 条件判断逻辑
         */
        private final Predicate<PipelineContext<?>> condition;

        /**
         * 父构建器引用
         */
        private final PipelineBuilder parent;

        /**
         * 判断节点实例
         */
        private final DecisionNode node;

        /**
         * 构造判断分支构建器。
         *
         * @param id        节点 ID
         * @param condition 条件判断逻辑
         * @param parent    父构建器
         */
        DecisionBuilder(String id, Predicate<PipelineContext<?>> condition, PipelineBuilder parent) {
            this.id = id;
            this.condition = condition;
            this.parent = parent;
            this.node = new DecisionNode(id, condition);
        }

        /**
         * 注册分支。
         *
         * @param result     条件结果（true 或 false）
         * @param nextNodeId 该结果对应的下一节点 ID
         * @return this
         */
        public DecisionBuilder when(boolean result, String nextNodeId) {
            node.when(result, nextNodeId);
            return this;
        }

        /**
         * 完成分支配置，返回父构建器。
         *
         * @return 父构建器
         */
        public PipelineBuilder then() {
            parent.nodes.add(node);
            parent.nodeMap.put(id, node);
            return parent;
        }
    }
}
