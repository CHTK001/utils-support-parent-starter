package com.chua.common.support.task.flow;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程定义图模型。
 *
 * <p>前后端共享的流程 JSON 图格式载体，包含节点与连线集合。
 * 可序列化为 JSON 并在前端 re流 画布与后端引擎之间双向传递，
 * 是流程编排的核心数据契约。</p>
 *
 * <p>JSON 结构示例：</p>
 * <pre>{@code
 * {
 *   "id": "flow1",
 *   "name": "审批流程",
 *   "nodes": [
 *     { "id": "start", "type": "start", "props": {}, "x": 100, "y": 100 }
 *   ],
 *   "edges": [
 *     { "from": "start", "to": "end", "label": "" }
 *   ]
 * }
 * }</pre> ]
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class FlowDefinition {

    /**
     * 流程定义 标识
     */
    private String id;

    /**
     * 流程名称
     */
    private String name;

    /**
     * 节点列表
     */
    private List<FlowNodeDef> nodes = new ArrayList<>();

    /**
     * 连线列表
     */
    private List<FlowEdgeDef> edges = new ArrayList<>();

    /**
     * 查找指定节点定义。
     *
     * @param nodeId 节点 标识
     * @return 节点定义，不存在时返回 空
     */
    public FlowNodeDef findNode(String nodeId) {
        for (FlowNodeDef node : nodes) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    /**
     * 查找节点的出边列表。
     *
     * @param nodeId 节点 标识
     * @return 出边列表，不存在时返回空列表
     */
    public List<FlowEdgeDef> findOutEdges(String nodeId) {
        List<FlowEdgeDef> result = new ArrayList<>();
        for (FlowEdgeDef edge : edges) {
            if (edge.from().equals(nodeId)) {
                result.add(edge);
            }
        }
        return result;
    }

    /**
     * 流程节点定义。
     *
     * <p>描述流程图中的一个节点，包含标识、类型、配置参数与画布坐标。</p>
     *
     * @param id    节点唯一标识
     * @param type  节点类型，对应节点类型注册表中的类型标识
     * @param props 节点配置参数
     * @param x     画布横坐标，仅供前端渲染使用
     * @param y     画布纵坐标，仅供前端渲染使用
     * @author CH
     * @since 4.0.0.42
     */
    public record FlowNodeDef(
            String id,
            String type,
            Map<String, Object> props,
            double x,
            double y
    ) {

        /**
         * 创建带默认坐标的节点定义。
         *
         * @param id    节点唯一标识
         * @param type  节点类型
         * @param props 节点配置参数
         * @return 节点定义实例
         */
        public static FlowNodeDef of(String id, String type, Map<String, Object> props) {
            return new FlowNodeDef(id, type, props, 0, 0);
        }

        /**
         * 创建空属性的节点定义。
         *
         * @param id   节点唯一标识
         * @param type 节点类型
         * @return 节点定义实例
         */
        public static FlowNodeDef of(String id, String type) {
            return new FlowNodeDef(id, type, new LinkedHashMap<>(), 0, 0);
        }
    }

    /**
     * 流程连线定义。
     *
     * <p>描述节点之间的有向边，label 为空表示顺序边，
     * 为 "true"/"false" 时表示条件分支的走向。</p>
     *
     * @param from  源节点 标识
     * @param to    目标节点 标识
     * @param label 边标签
     * @author CH
     * @since 4.0.0.42
     */
    public record FlowEdgeDef(
            String from,
            String to,
            String label
    ) {

        /**
         * 创建顺序连线。
         *
         * @param from 源节点 标识
         * @param to   目标节点 标识
         * @return 连线定义实例
         */
        public static FlowEdgeDef of(String from, String to) {
            return new FlowEdgeDef(from, to, "");
        }
    }
}
