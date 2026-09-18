package com.chua.flow.support;

import com.chua.common.support.task.flow.FlowDefinition;
import com.chua.common.support.task.flow.FlowException;
import com.chua.common.support.task.flow.FlowGraph;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowJson;
import com.chua.common.support.task.flow.FlowNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 默认流程编排图实现。
*
* <p>由 {@link DefaultFlow#createGraph()} 创建，进入可执行状态。
* 通过链式 DSL 配置节点连线，构建结束后可创建流程实例：</p>
* <ul>
*   <li>{@link #start(String)} — 指定起始节点</li>
*   <li>{@link #next(String...)} — 顺序连线，前一个节点依次连接后续节点</li>
*   <li>{@link #when(String, boolean, String)} — 条件节点分支连线</li>
*   <li>{@link #end(String...)} — 标记终止节点，结束编排构建</li>
* </ul>
*
* <p>连线写入底层流程定义的 edges 集合，JSON 导出格式与前端 ReFlow 画布数据一致。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DefaultFlowGraph implements FlowGraph {

    /**
    * 底层流程
    */
    private final DefaultFlow flow;

    /**
    * 当前连线游标节点 标识
    */
    private String cursor;

    /**
    * 起始节点 标识
    */
    private String startNodeId;

    /**
    * 以指定流程创建编排图。
    *
    * @param flow 底层流程
    */
    public DefaultFlowGraph(DefaultFlow flow) {
        this.flow = flow;
    }

    @Override
    /** 开始 */
    public FlowGraph start(String nodeId) {
        checkNode(nodeId);
        this.startNodeId = nodeId;
        this.cursor = nodeId;
        return this;
    }

    @Override
    /** 下一个 */
    public FlowGraph next(String... nodeIds) {
        if (nodeIds == null || nodeIds.length == 0) {
            throw new FlowException("next 必须指定至少一个节点");
        }
        if (cursor == null) {
            throw new FlowException("请先调用 start(nodeId) 指定起始节点");
        }
        for (String nodeId : nodeIds) {
            checkNode(nodeId);
            flow.getDefinition().getEdges().add(FlowDefinition.FlowEdgeDef.of(cursor, nodeId));
            cursor = nodeId;
        }
        return this;
    }

    @Override
    /** When.js.js.js */
    public FlowGraph when(String nodeId, boolean result, String... targets) {
        checkNode(nodeId);
        if (targets == null || targets.length == 0) {
            throw new FlowException("when 必须指定至少一个目标节点");
        }
        for (String target : targets) {
            checkNode(target);
            flow.getDefinition().getEdges().add(new FlowDefinition.FlowEdgeDef(
                    nodeId, target, result ? "true" : "false"));
        }
        return this;
    }

    @Override
    /** 结束 */
    public FlowGraph end(String... nodeIds) {
        String[] targets = nodeIds;
        if (targets == null || targets.length == 0) {
            if (cursor == null) {
                throw new FlowException("end 必须指定至少一个节点");
            }
            targets = new String[]{cursor};
        }
        for (String nodeId : targets) {
            checkNode(nodeId);
            flow.getDefinition().getEdges().add(FlowDefinition.FlowEdgeDef.of(nodeId, nodeId));
        }
        return this;
    }

    @Override
    /** 创建Instance */
    public FlowInstance createInstance() {
        return createInstance(Collections.emptyMap());
    }

    @Override
    /** 创建Instance */
    public FlowInstance createInstance(Map<String, Object> params) {
        String resolvedStart = startNodeId != null ? startNodeId : flow.resolveStartNodeId();
        if (resolvedStart == null) {
            throw new FlowException("流程定义为空，无法创建实例: " + flow.getId());
        }
        return new DefaultFlowInstance(flow, resolvedStart, params);
    }

    @Override
    /** 导出json */
    public String exportJson() {
        return FlowJson.toJson(flow.getDefinition());
    }

    /**
    * 校验节点是否已注册到流程。
    *
    * @param nodeId 节点 标识
    */
    private void checkNode(String nodeId) {
        if (!flow.containsNode(nodeId)) {
            throw new FlowException("节点未添加，请先调用 addNode: " + nodeId);
        }
    }
}
