package com.chua.flow.support;

import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowDefinition;
import com.chua.common.support.task.flow.FlowException;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowJson;
import com.chua.common.support.task.flow.FlowNodeMetadata;
import com.chua.common.support.task.flow.FlowNodeRegistry;
import com.chua.flow.support.node.ConditionFlowNode;
import com.chua.flow.support.node.EndFlowNode;
import com.chua.flow.support.node.HttpCallFlowNode;
import com.chua.flow.support.node.LogFlowNode;
import com.chua.flow.support.node.StartFlowNode;
import com.chua.flow.support.node.TransformFlowNode;

import java.util.List;
import java.util.Map;

/**
 * 流程引擎入口。
 *
 * <p>提供流程的创建、JSON 解析与节点类型清单查询能力，
 * 是编排系统的统一访问入口。类加载时自动注册内置节点类型，
 * 供 JSON 导入与节点类型清单查询使用。</p>
 *
 * <pre>{@code
 * Flow flow = FlowEngine.createFlow("demo")
 *     .addNode("start", new StartFlowNode())
 *     .addNode("echo", new LogFlowNode(), Map.of("message", "hello"))
 *     .addNode("end", new EndFlowNode());
 *
 * FlowInstance instance = flow.createGraph()
 *     .start("start").next("echo").next("end").end()
 *     .createInstance();
 * instance.run();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FlowEngine {

    /**
    * 是否已注册内置节点
    */
    private static volatile boolean builtInRegistered = false;

    /**
    * 私有构造方法，禁止实例化工具类。
    */
    private FlowEngine() {
    }

    /**
    * 创建空流程。
    *
    * @param id 流程 ID
    * @return 流程构建入口
    */
    public static Flow createFlow(String id) {
        ensureBuiltInRegistered();
        return new DefaultFlow(id);
    }

    /**
    * 从流程定义创建流程。
    *
    * @param definition 流程定义图模型
    * @return 流程实例
    */
    public static Flow createFlow(FlowDefinition definition) {
        ensureBuiltInRegistered();
        return new DefaultFlow(definition);
    }

    /**
    * 从 JSON 解析创建流程。
    *
    * <p>解析前端 ReFlow 导出的图数据，校验节点类型后重建可执行流程。</p>
    *
    * @param json 流程定义 JSON
    * @return 流程实例
    */
    public static Flow parseJson(String json) {
        FlowDefinition definition = FlowJson.fromJson(json);
        return createFlow(definition);
    }

    /**
    * 从 JSON 解析流程定义并执行一次。
    *
    * <p>快捷执行入口，用于脚本与测试场景。</p>
    *
    * @param json 流程定义 JSON
    * @return 流程实例（已完成执行，可读取上下文）
    */
    public static FlowInstance runOnce(String json) {
        Flow flow = parseJson(json);
        return flow.createGraph().createInstance().run();
    }

    /**
    * 获取全部节点类型清单。
    *
    * <p>供前端属性面板渲染节点类型列表。</p>
    *
    * @return 节点类型元信息列表
    */
    public static List<FlowNodeMetadata> listNodeTypes() {
        ensureBuiltInRegistered();
        return FlowNodeRegistry.listMetadata();
    }

    /**
    * 校验 JSON 流程定义是否合法。
    *
    * <p>校验节点类型是否全部已注册，未注册时抛出异常。</p>
    *
    * @param json 流程定义 JSON
    * @return 校验通过返回 true
    */
    public static boolean validate(String json) {
        FlowDefinition definition = FlowJson.fromJson(json);
        for (FlowDefinition.FlowNodeDef node : definition.getNodes()) {
            if (!FlowNodeRegistry.exists(node.type())) {
                throw new FlowException("未注册的节点类型: " + node.type());
            }
        }
        return true;
    }

    /**
    * 注册内置节点类型。
    *
    * <p>登记 flow-starter 内置节点到 {@link FlowNodeRegistry}，
    * 供 JSON 导入创建节点副本与节点类型清单展示。</p>
    */
    private static synchronized void ensureBuiltInRegistered() {
        if (builtInRegistered) {
            return;
        }
        FlowNodeRegistry.register("start", new StartFlowNode(), "起始节点");
        FlowNodeRegistry.register("end", new EndFlowNode(), "终止节点");
        FlowNodeRegistry.register("condition", new ConditionFlowNode(), "条件分支");
        FlowNodeRegistry.register("transform", new TransformFlowNode(), "数据转换");
        FlowNodeRegistry.register("log", new LogFlowNode(), "日志输出");
        FlowNodeRegistry.register("httpCall", new HttpCallFlowNode(), "HTTP 调用");
        builtInRegistered = true;
    }
}
