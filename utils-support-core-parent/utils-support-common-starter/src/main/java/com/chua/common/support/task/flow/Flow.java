package com.chua.common.support.task.flow;

import java.util.List;
import java.util.Map;

/**
 * 流程容器接口。
 *
 * <p>负责节点定义的管理，通过 {@link #addNode(String, FlowNode)} 添加节点实例
 * （节点直接以对象形式加入，无需 SPI 注册），通过 {@link #createGraph()}
 * 开始创建编排图并进入可执行状态。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * Flow flow = FlowEngine.createFlow("demo")
 *     .addNode("start", new DefaultStartNode())
 *     .addNode("fetch", new SpiderFlowNode(), Map.of("urls", List.of("https://example.com")))
 *     .addNode("check", new DefaultConditionNode(), Map.of("key", "spider.result"))
 *     .addNode("transform", new DefaultTransformNode(), Map.of("source", "attribute:bizId"))
 *     .addNode("end", new DefaultEndNode());
 *
 * FlowGraph graph = flow.createGraph()
 *     .start("start").next("fetch").next("check")
 *     .when("check", true, "transform")
 *     .when("check", false, "end")
 *     .next("transform").next("end")
 *     .end();
 *
 * FlowInstance instance = graph.createInstance(Map.of("bizId", "1"));
 * instance.run();
 * }</pre>rue, "transform")
 *     .when("check", false, "end")
 *     .next("transform").next("end")
 *     .end();
 *
 * FlowInstance instance = graph.createInstance(Map.of("bizId", "1"));
 * instance.run();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface Flow {

    /**
    * 获取流程唯一标识。
    *
    * @return 流程 标识
    */
    String getId();

    /**
    * 添加节点定义。
    *
    * <p>节点直接以实例形式加入，不需要 SPI 注册，灵活可组合。
    * 节点类型由 {@link FlowNode#type()} 提供，无配置属性。</p>
    *
    * @param id   节点唯一标识
    * @param node 节点实例
    * @return 当前流程，支持链式调用
    */
    Flow addNode(String id, FlowNode node);

    /**
    * 添加节点定义并携带配置属性。
    *
    * <p>节点执行时通过 {@link FlowContext#currentNodeProps()} 读取配置属性，
    * 属性同时参与流程定义 JSON 的导出。</p>
    *
    * @param id    节点唯一标识
    * @param node  节点实例
    * @param props 节点配置属性
    * @return 当前流程，支持链式调用
    */
    Flow addNode(String id, FlowNode node, Map<String, Object> props);

    /**
    * 获取指定节点。
    *
    * @param id 节点唯一标识
    * @return 节点实例，不存在时返回 空
    */
    FlowNode getNode(String id);

    /**
    * 判断节点是否存在。
    *
    * @param id 节点唯一标识
    * @return 存在返回 true，否则返回 false
    */
    boolean containsNode(String id);

    /**
    * 获取全部节点实例。
    *
    * @return 节点实例列表
    */
    List<FlowNode> listNodes();

    /**
    * 开始创建编排图。
    *
    * <p>调用后进入可执行状态，通过 {@link FlowGraph} 的
    * {@code start(id).next(...).when(...).end()} 链式 DSL 构建节点连线。</p>
    *
    * @return 编排图实例
    */
    FlowGraph createGraph();

    /**
    * 导出流程定义 JSON。
    *
    * <p>输出前后端统一的图格式，与前端 ReFlow 画布数据一致。</p>
    *
    * @return 流程定义 JSON 字符串
    */
    String exportJson();

    /**
    * 从 JSON 导入流程定义。
    *
    * <p>解析前端 ReFlow 导出的图数据，重建可执行流程。
    * 节点类型需已注册，否则抛出异常。</p>
    *
    * @param json 流程定义 JSON 字符串
    * @return 流程实例
    */
    Flow importJson(String json);
}
