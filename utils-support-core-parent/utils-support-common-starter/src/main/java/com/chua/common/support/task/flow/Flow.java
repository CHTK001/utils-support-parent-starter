package com.chua.common.support.task.flow;

import java.util.Map;

/**
 * 流程接口。
 *
 * <p>流程编排的核心抽象，定义一条可执行的节点链路。
 * 支持 {@code addNode} + {@code addNext} 链式构建、条件分支、
 * 实例创建以及 JSON 图导入导出。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * Flow flow = FlowEngine.createFlow("demo")
 *     .addNode("start", "start")
 *     .addNext("start", "fetch")
 *     .addNode("fetch", "spider", FlowProps.of(Map.of("urls", List.of("https://example.com"))))
 *     .addNext("fetch", "check")
 *     .addNode("check", "condition", FlowProps.of(Map.of("key", "spider.result")))
 *     .when("check", true, "save")
 *     .when("check", false, "end")
 *     .addNext("check", "save")
 *     .addNode("save", "httpCall", FlowProps.of(Map.of("url", "https://api.example.com/save")))
 *     .addNext("save", "end")
 *     .addNode("end", "end")
 *     .build();
 *
 * FlowInstance instance = flow.createInstance(Map.of("bizId", "1"));
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
     * @return 流程 ID
     */
    String getId();

    /**
     * 添加节点。
     *
     * @param id    节点唯一标识
     * @param type  节点类型
     * @param props 节点配置属性
     * @return 当前流程，支持链式调用
     */
    Flow addNode(String id, String type, FlowProps props);

    /**
     * 添加节点。
     *
     * @param id    节点唯一标识
     * @param type  节点类型
     * @param props 节点配置属性
     * @return 当前流程，支持链式调用
     */
    Flow addNode(String id, String type, Map<String, Object> props);

    /**
     * 添加无属性节点。
     *
     * @param id   节点唯一标识
     * @param type 节点类型
     * @return 当前流程，支持链式调用
     */
    Flow addNode(String id, String type);

    /**
     * 添加顺序连线。
     *
     * <p>从源节点到目标节点的顺序边，源节点执行完毕后按默认顺序进入目标节点。</p>
     *
     * @param from 源节点 ID
     * @param to   目标节点 ID
     * @return 当前流程，支持链式调用
     */
    Flow addNext(String from, String to);

    /**
     * 配置条件节点分支。
     *
     * <p>为条件节点指定判断结果对应的下一节点，true/false 各可配置一条。</p>
     *
     * @param nodeId 条件节点 ID
     * @param result 判断结果
     * @param target 该结果对应的下一节点 ID
     * @return 当前流程，支持链式调用
     */
    Flow when(String nodeId, boolean result, String target);

    /**
     * 指定起始节点 ID。
     *
     * @param nodeId 起始节点 ID
     * @return 当前流程，支持链式调用
     */
    Flow start(String nodeId);

    /**
     * 指定终止节点 ID。
     *
     * @param nodeId 终止节点 ID
     * @return 当前流程，支持链式调用
     */
    Flow end(String nodeId);

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

    /**
     * 从 JSON 导入流程定义。
     *
     * <p>解析前端 ReFlow 导出的图数据，重建可执行流程。
     * 节点类型需已注册，否则抛出异常。</p>
     *
     * @param json 流程定义 JSON 字符串
     * @return 可执行的流程实例
     */
    Flow importJson(String json);
}
