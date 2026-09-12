package com.chua.flow.support;

import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowDefinition;
import com.chua.common.support.task.flow.FlowException;
import com.chua.common.support.task.flow.FlowGraph;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowJson;
import com.chua.common.support.task.flow.FlowNode;
import com.chua.common.support.task.flow.FlowNodeRegistry;
import com.chua.common.support.task.flow.FlowProps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 默认流程实现。
*
* <p>节点定义的管理容器：通过 {@link #addNode(String, FlowNode)} 直接添加节点实例
* （无需 SPI 注册），节点类型由 {@link FlowNode#type()} 提供；
* 通过 {@link #createGraph()} 开始创建编排图，进入可执行状态。</p>
*
* <p>流程定义可导入导出，导出格式与前端 ReFlow 画布数据一致，
* JSON 导入时通过 {@link FlowNodeRegistry} 按类型创建节点副本。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DefaultFlow implements Flow {

    /**
    * 流程定义图模型
     */
    private final FlowDefinition definition;

    /**
    * 节点 标识 到节点实例的映射
     */
    private final Map<String, FlowNode> nodes = new LinkedHashMap<>();

    /**
    * 以指定 标识 创建流程。
    *
    * @param id 流程 标识
     */
    public DefaultFlow(String id) {
        this.definition = new FlowDefinition();
        this.definition.setId(id);
    }

    /**
    * 从流程定义图模型创建流程。
    *
    * <p>通过节点类型注册表按类型创建节点副本，恢复节点实例映射。
    * 未注册的节点类型直接报错，避免运行期才发现配置错误。</p>
    *
    * @param definition 流程定义图模型
     */
    public DefaultFlow(FlowDefinition definition) {
        this.definition = definition;
        for (FlowDefinition.FlowNodeDef node : definition.getNodes()) {
            FlowNode instance = FlowNodeRegistry.createNode(node.type());
            if (instance == null) {
                throw new FlowException("未注册的节点类型: " + node.type());
            }
            nodes.put(node.id(), instance);
        }
    }

    @Override
    /** 获取标识 */
    public String getId() {
        return definition.getId();
    }

    @Override
    /** 添加节点 */
    public Flow addNode(String id, FlowNode node) {
        return addNode(id, node, Collections.emptyMap());
    }

    @Override
    /** 添加节点 */
    public Flow addNode(String id, FlowNode node, Map<String, Object> props) {
        if (node == null) {
            throw new FlowException("节点实例不能为空: " + id);
        }
        if (nodes.containsKey(id)) {
            throw new FlowException("节点已存在: " + id);
        }
        Map<String, Object> propsMap = props != null ? props : Collections.emptyMap();
        definition.getNodes().add(FlowDefinition.FlowNodeDef.of(id, node.type(), propsMap));
        nodes.put(id, node);
        return this;
    }

    @Override
    /** 获取节点 */
    public FlowNode getNode(String id) {
        return nodes.get(id);
    }

    @Override
    /** contains节点 */
    public boolean containsNode(String id) {
        return nodes.containsKey(id);
    }

    @Override
    /** 列表节点 */
    public List<FlowNode> listNodes() {
        return new ArrayList<>(nodes.values());
    }

    @Override
    /** 创建图计算 */
    public FlowGraph createGraph() {
        return new DefaultFlowGraph(this);
    }

    @Override
    /** 导出json */
    public String exportJson() {
        return FlowJson.toJson(definition);
    }

    @Override
    /** 导入json */
    public Flow importJson(String json) {
        FlowDefinition parsed = FlowJson.fromJson(json);
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
    * @param nodeId 节点 标识
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
    * 解析起始节点 标识。
    *
    * <p>取第一个无入边的节点作为起始节点；
    * 全部节点都有入边时回退取第一个节点。</p>
    *
    * @return 起始节点 标识，无节点时返回 空
     */
    String resolveStartNodeId() {
        if (definition.getNodes().isEmpty()) {
            return null;
        }
        List<String> all = new ArrayList<>();
        for (FlowDefinition.FlowNodeDef node : definition.getNodes()) {
            all.add(node.id());
        }
        for (FlowDefinition.FlowEdgeDef edge : definition.getEdges()) {
            all.remove(edge.to());
        }
        if (!all.isEmpty()) {
            return all.get(0);
        }
        return definition.getNodes().get(0).id();
    }
}
