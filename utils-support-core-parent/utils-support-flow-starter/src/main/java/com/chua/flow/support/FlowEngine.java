package com.chua.flow.support;

import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowDefinition;
import com.chua.common.support.task.flow.FlowException;
import com.chua.common.support.task.flow.FlowJson;
import com.chua.common.support.task.flow.FlowNodeMetadata;
import com.chua.common.support.task.flow.FlowNodeRegistry;

import java.util.List;

/**
 * 流程引擎入口。
 *
 * <p>提供流程的创建、JSON 解析与节点类型清单查询能力，
 * 是编排系统的统一访问入口。</p>
 *
 * <pre>{@code
 * Flow flow = FlowEngine.createFlow("demo")
 *     .addNode("start", "start")
 *     .addNext("start", "end")
 *     .addNode("end", "end")
 *     .build();
 *
 * FlowInstance instance = flow.createInstance();
 * instance.run();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FlowEngine {

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
        return new DefaultFlow(id);
    }

    /**
     * 从流程定义创建流程。
     *
     * @param definition 流程定义图模型
     * @return 流程实例
     */
    public static Flow createFlow(FlowDefinition definition) {
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
    public static com.chua.common.support.task.flow.FlowInstance runOnce(String json) {
        Flow flow = parseJson(json);
        return flow.createInstance().run();
    }

    /**
     * 获取全部节点类型清单。
     *
     * <p>供前端属性面板渲染节点类型列表。</p>
     *
     * @return 节点类型元信息列表
     */
    public static List<FlowNodeMetadata> listNodeTypes() {
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
}
