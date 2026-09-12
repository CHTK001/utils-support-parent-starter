package com.chua.common.support.task.flow;

import java.util.Map;

/**
* 流程编排图接口。
*
* <p>由 {@link Flow#createGraph()} 创建，进入可执行状态。
* 通过链式 DSL 配置节点连线：</p>
* <ul>
*   <li>{@link #start(String)} — 指定起始节点</li>
*   <li>{@link #next(String...)} — 顺序连线（一个节点连多个后续节点）</li>
*   <li>{@link #when(String, boolean, String)} — 条件分支连线</li>
*   <li>{@link #end(String...)} — 标记终止节点</li>
* </ul>
*
* <p>使用示例：</p>
* <pre>{@code
* FlowGraph graph = flow.createGraph()
*     .start("start").next("fetch").next("check")
*     .when("check", true, "transform", "notify")
*     .when("check", false, "end")
*     .next("transform").next("end")
*     .end();
*
* FlowInstance instance = graph.createInstance(Map.of("bizId", "1"));
* instance.run();
* }</pre>h.createInstance(Map.of("bizId", "1"));
* instance.run();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface FlowGraph {

    /**
    * 指定起始节点。
    *
    * <p>流程从该节点开始执行。</p>
    *
    * @param nodeId 起始节点 标识
    * @return 当前编排图，支持链式调用
     */
    FlowGraph start(String nodeId);

    /**
    * 顺序连线。
    *
    * <p>从前一个节点依次连接到后续节点，每个参数顺序连接。
    * 例如 {@code next("a", "b", "c")} 表示前一个节点 → a → b → C。</p>
    *
    * @param nodeIds 目标节点 标识，顺序排列
    * @return 当前编排图，支持链式调用
     */
    FlowGraph next(String... nodeIds);

    /**
    * 配置条件节点分支。
    *
    * <p>为条件节点指定判断结果对应的下一节点，true/false 各可配置多条。
    * 多目标时按声明顺序依次执行（与 vue-流 中一个 源处理 连多条边对应）。</p>
    *
    * @param nodeId  条件节点 标识
    * @param result  判断结果
    * @param targets 该结果对应的下一节点 标识，可多个
    * @return 当前编排图，支持链式调用
     */
    FlowGraph when(String nodeId, boolean result, String... targets);

    /**
    * 标记终止节点并结束编排构建。
    *
    * <p>指定的节点作为流程出口，执行到该节点后流程完成。
    * 若不传参数，则默认标记最后一个节点为终止节点。</p>
    *
    * @param nodeIds 终止节点 标识，可选
    * @return 当前编排图，支持链式调用
     */
    FlowGraph end(String... nodeIds);

    /**
    * 创建流程实例。
    *
    * @return 流程实例，持有唯一执行上下文
     */
    FlowInstance createInstance();

    /**
    * 创建流程实例并携带初始参数。
    *
    * <p>参数在首次运行时合并写入实例上下文。</p>
    *
    * @param params 初始参数
    * @return 流程实例，持有唯一执行上下文
     */
    FlowInstance createInstance(Map<String, Object> params);

    /**
    * 导出流程定义 JSON。
    *
    * <p>输出前后端统一的图格式，与前端 ReFlow 画布数据一致。</p>
    *
    * @return 流程定义 JSON 字符串
     */
    String exportJson();
}
