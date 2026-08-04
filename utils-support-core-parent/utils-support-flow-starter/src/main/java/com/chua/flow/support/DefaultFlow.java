package com.chua.flow.support;

import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowDefinition;
import com.chua.common.support.task.flow.FlowException;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowJson;
import com.chua.common.support.task.flow.FlowNodeExecutor;
import com.chua.common.support.task.flow.FlowNodeRegistry;
import com.chua.common.support.task.flow.FlowProps;
import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认流程实现。
 *
 * <p>将链式 DSL 写入的节点与连线转换为统一的 {@link FlowDefinition} 图模型，
 * 并通过桥接将图结构映射到 {@link PipelineBuilder} 构建可执行流水线：</p>
 * <ul>
 *   <li>普通节点（含 start/end/httpCall 等）映射为 TaskNode，
 *       节点执行后按显式顺序边设置下一节点</li>
 *   <li>condition 节点映射为 DecisionNode，判断结果走 true/false 分支</li>
 *   <li>节点执行器通过 SPI 注册表 {@link FlowNodeRegistry} 按类型实例化</li>
 * </ul>
 *
 * <p>流程定义可导入导出，导出格式与前端 ReFlow 画布数据一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultFlow implements Flow {

    /**
     * 上下文属性键：流程实例引用
     */
    static final String INSTANCE_ATTRIBUTE_KEY = "flow.instance";

    /**
     * 条件节点类型标识
     */
    private static final String CONDITION_NODE_TYPE = "condition";

    /**
     * 顺序边标签（空字符串表示默认顺序边）
     */
    private static final String DEFAULT_EDGE_LABEL = "";

    /**
     * 流程定义图模型
     */
    private final FlowDefinition definition;

    /**
     * 起始节点 ID
     */
    private String startNodeId;

    /**
     * 节点 ID 到显式顺序边目标节点 ID 的映射
     */
    private final Map<String, String> nextTargets = new HashMap<>();

    /**
     * 节点 ID 到条件分支映射的映射
     */
    private final Map<String, Map<Boolean, String>> branches = new HashMap<>();

    /**
     * 以指定 ID 创建流程。
     *
     * @param id 流程 ID
     */
    public DefaultFlow(String id) {
        this.definition = new FlowDefinition();
        this.definition.setId(id);
    }

    /**
     * 从流程定义图模型创建流程。
     *
     * <p>解析定义中的节点与连线，重建执行映射；校验全部节点类型已注册。</p>
     *
     * @param definition 流程定义图模型
     */
    public DefaultFlow(FlowDefinition definition) {
        this.definition = definition;
        this.startNodeId = resolveStartNodeId();
        rebuildMappings();
    }

    @Override
    public String getId() {
        return definition.getId();
    }

    @Override
    public Flow addNode(String id, String type, FlowProps props) {
        Map<String, Object> propsMap = props != null ? props.toMap() : Collections.emptyMap();
        definition.getNodes().add(FlowDefinition.FlowNodeDef.of(id, type, propsMap));
        if (startNodeId == null) {
            startNodeId = id;
        }
        return this;
    }

    @Override
    public Flow addNode(String id, String type, Map<String, Object> props) {
        return addNode(id, type, FlowProps.of(props));
    }

    @Override
    public Flow addNode(String id, String type) {
        return addNode(id, type, FlowProps.EMPTY);
    }

    @Override
    public Flow addNext(String from, String to) {
        definition.getEdges().add(FlowDefinition.FlowEdgeDef.of(from, to));
        // 仅当源节点尚无显式顺序边时记录首条边为目标
        if (!nextTargets.containsKey(from)) {
            nextTargets.put(from, to);
        }
        return this;
    }

    @Override
    public Flow when(String nodeId, boolean result, String target) {
        definition.getEdges().add(new FlowDefinition.FlowEdgeDef(
                nodeId, target, result ? "true" : "false"));
        branches.computeIfAbsent(nodeId, key -> new HashMap<>()).put(result, target);
        return this;
    }

    @Override
    public Flow start(String nodeId) {
        this.startNodeId = nodeId;
        return this;
    }

    @Override
    public Flow end(String nodeId) {
        definition.getEdges().add(FlowDefinition.FlowEdgeDef.of(nodeId, nodeId));
        return this;
    }

    @Override
    public FlowInstance createInstance() {
        return createInstance(Collections.emptyMap());
    }

    @Override
    public FlowInstance createInstance(Map<String, Object> params) {
        if (definition.getNodes().isEmpty()) {
            throw new FlowException("流程定义为空，无法创建实例: " + definition.getId());
        }
        return new DefaultFlowInstance(this, buildPipeline(), startNodeId, params);
    }

    @Override
    public String exportJson() {
        return FlowJson.toJson(definition);
    }

    @Override
    public Flow importJson(String json) {
        FlowDefinition parsed = FlowJson.fromJson(json);
        validateTypes(parsed);
        return new DefaultFlow(parsed);
    }

    /**
     * 获取流程定义图模型。
     *
     * @return 流程定义
     */
    public FlowDefinition getDefinition() {
        return definition;
    }

    /**
     * 获取指定节点的配置属性。
     *
     * @param nodeId 节点 ID
     * @return 节点属性，节点不存在时返回空属性
     */
    public FlowProps nodeProps(String nodeId) {
        FlowDefinition.FlowNodeDef node = definition.findNode(nodeId);
        if (node == null) {
            return FlowProps.EMPTY;
        }
        return FlowProps.of(node.props());
    }

    /**
     * 校验流程定义中的全部节点类型均已注册。
     *
     * <p>未注册的节点类型在导入时直接报错，避免运行期才发现配置错误。</p>
     *
     * @param parsed 待校验的流程定义
     */
    private void validateTypes(FlowDefinition parsed) {
        for (FlowDefinition.FlowNodeDef node : parsed.getNodes()) {
            if (!FlowNodeRegistry.exists(node.type())) {
                throw new FlowException("未注册的节点类型: " + node.type());
            }
        }
    }

    /**
     * 解析起始节点 ID。
     *
     * <p>未显式指定时默认取第一个节点作为起始节点。</p>
     *
     * @return 起始节点 ID
     */
    private String resolveStartNodeId() {
        if (!definition.getNodes().isEmpty()) {
            return definition.getNodes().get(0).id();
        }
        return null;
    }

    /**
     * 重建执行映射。
     *
     * <p>从定义中的连线解析顺序边与条件分支，
     * 在从 JSON 导入创建流程时恢复执行结构。</p>
     */
    private void rebuildMappings() {
        for (FlowDefinition.FlowEdgeDef edge : definition.getEdges()) {
            if (DEFAULT_EDGE_LABEL.equals(edge.label())) {
                if (!nextTargets.containsKey(edge.from())) {
                    nextTargets.put(edge.from(), edge.to());
                }
            } else {
                boolean result = "true".equals(edge.label());
                branches.computeIfAbsent(edge.from(), key -> new HashMap<>())
                        .put(result, edge.to());
            }
        }
    }

    /**
     * 构建可执行流水线。
     *
     * <p>普通节点包装为 TaskNode（执行后按显式顺序边设置下一节点）；
     * condition 节点包装为 DecisionNode（按条件结果走分支）。
     * 节点执行器与实例通过上下文属性 {@value #INSTANCE_ATTRIBUTE_KEY} 传递，
     * 保证同一实例始终操作同一上下文。</p>
     *
     * @return 可执行流水线
     */
    private Pipeline buildPipeline() {
        PipelineBuilder builder = PipelineBuilder.newBuilder(definition.getId());
        for (FlowDefinition.FlowNodeDef node : definition.getNodes()) {
            String nodeId = node.id();
            FlowProps props = FlowProps.of(node.props());
            if (CONDITION_NODE_TYPE.equals(node.type())) {
                buildDecisionNode(builder, nodeId, props);
            } else {
                buildTaskNode(builder, node);
            }
        }
        if (startNodeId != null) {
            builder.start(startNodeId);
        }
        return builder.build();
    }

    /**
     * 构建条件节点。
     *
     * <p>条件判断依据节点属性中的 key/equals/notEmpty 求值，
     * 结果走注册的 true/false 分支。</p>
     *
     * @param builder 流水线构建器
     * @param nodeId  节点 ID
     * @param props   节点属性
     */
    private void buildDecisionNode(PipelineBuilder builder, String nodeId, FlowProps props) {
        Map<Boolean, String> nodeBranches = branches.getOrDefault(nodeId, Collections.emptyMap());
        builder.decision(nodeId, context -> evaluateCondition(props, context))
                .when(true, nodeBranches.get(true))
                .when(false, nodeBranches.get(false))
                .then();
    }

    /**
     * 构建普通执行节点。
     *
     * <p>执行对应类型的节点执行器，节点未改变执行动作时按显式顺序边进入下一节点。</p>
     *
     * @param builder 流水线构建器
     * @param node    节点定义
     * @param props   节点属性
     */
    private void buildTaskNode(PipelineBuilder builder, FlowDefinition.FlowNodeDef node) {
        String nodeId = node.id();
        String nextTarget = nextTargets.get(nodeId);
        builder.task(nodeId, context -> executeNode(node, nextTarget, context));
    }

    /**
     * 执行单个节点。
     *
     * @param node       节点定义
     * @param nextTarget 显式顺序边目标节点 ID
     * @param context    流水线上下文
     */
    private void executeNode(FlowDefinition.FlowNodeDef node, String nextTarget,
                             PipelineContext<?> context) {
        FlowInstance instance = context.getAttribute(INSTANCE_ATTRIBUTE_KEY);
        FlowNodeExecutor executor = FlowNodeRegistry.getExecutor(node.type());
        if (executor == null) {
            throw new FlowException("未注册的节点类型: " + node.type());
        }
        executor.execute(instance);
        // 节点未改变执行动作时，按显式顺序边进入下一节点
        if (context.getAction() == Action.NEXT && nextTarget != null) {
            context.setNextNodeId(nextTarget);
        }
    }

    /**
     * 求值条件节点的判断结果。
     *
     * <p>支持三种判断模式：</p>
     * <ul>
     *   <li>{@code key + equals} — 比较上下文属性是否等于指定值</li>
     *   <li>{@code key + notEmpty} — 判断上下文属性是否非空</li>
     *   <li>仅 {@code key} — 默认判断上下文属性是否存在且非空</li>
     * </ul>
     *
     * @param props   节点属性
     * @param context 流水线上下文
     * @return 条件判断结果
     */
    private boolean evaluateCondition(FlowProps props, PipelineContext<?> context) {
        FlowInstance instance = context.getAttribute(INSTANCE_ATTRIBUTE_KEY);
        String key = props.getString("key");
        Object value = resolveValue(key, instance);
        if (props.has("equals")) {
            Object expected = props.get("equals");
            return expected != null ? expected.equals(value) : value == null;
        }
        Boolean notEmpty = props.getBoolean("notEmpty");
        boolean requireNotEmpty = notEmpty == null || notEmpty;
        return requireNotEmpty ? value != null : value == null;
    }

    /**
     * 解析条件节点取值来源。
     *
     * <p>键为 "current" 时取当前处理数据，否则取实例上下文属性。</p>
     *
     * @param key      取值键
     * @param instance 流程实例
     * @return 取值结果
     */
    private Object resolveValue(String key, FlowInstance instance) {
        if (key == null) {
            return null;
        }
        if ("current".equals(key)) {
            return instance.getCurrentData();
        }
        return instance.getAttribute(key);
    }

    /**
     * 获取节点列表（供实例构建使用）。
     *
     * @return 节点定义列表
     */
    List<FlowDefinition.FlowNodeDef> nodes() {
        return new ArrayList<>(definition.getNodes());
    }
}
