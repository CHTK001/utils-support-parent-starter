package com.chua.common.support.task.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * 流程节点接口。
 *
 * <p>流程编排图中每个节点的执行逻辑载体。与旧版"注解 + SPI 注册"不同，
 * 新架构中节点是普通接口，直接以实例形式通过 {@link Flow#addNode} 加入流程，
 * 无需任何 SPI 注册，灵活性和可组合性更强。</p>
 *
 * <p>节点通过 {@link FlowContext} 与流程交互：</p>
 * <ul>
 *   <li>读取节点配置属性：{@link FlowContext#currentNodeProps()}</li>
 *   <li>读写共享上下文：{@link FlowContext#getAttribute(String)}、
 *       {@link FlowContext#setAttribute(String, Object)}</li>
 *   <li>更新当前数据：{@link FlowContext#getData()}、{@link FlowContext#setData(Object)}</li>
 *   <li>控制流程流向：{@link FlowContext#waitForResume()} 挂起、
 *       {@link FlowContext#exit()} 终止、{@link FlowContext#setNextNodeId(String)} 指定下一节点</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * FlowNode node = new FlowNode() {
 *     @Override
 *     public String type() {
 *         return "custom";
 *     }
 *
 *     @Override
 *     public void execute(FlowContext context) {
 *         context.setData("hello");
 *     }
 * };
 *
 * Flow flow = FlowEngine.createFlow("demo").addNode("n1", node);
 * }</pre>
 *
 * <p>内置了常用二级节点接口，直接使用即可：{@link StartNode}、{@link EndNode}、
 * {@link ConditionNode}、{@link TransformNode}、{@link LogNode}、{@link HttpCallNode}、
 * {@link SpiderNode}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FlowNode {

    /**
     * 获取节点类型标识。
     *
     * <p>用于节点清单展示与 JSON 图定义导入时匹配节点实现。</p>
     *
     * @return 节点类型标识
     */
    String type();

    /**
     * 执行节点逻辑。
     *
     * <p>由流程引擎调度到当前节点时调用。执行期间可通过上下文读写数据、
     * 控制流程动作；抛出异常会被引擎捕获并标记实例为失败状态。</p>
     *
     * @param context 当前流程上下文
     */
    void execute(FlowContext context);

    /**
     * 创建节点实例。
     *
     * <p>默认返回当前实例；有状态要求的节点实现可覆写此方法，返回适合并发执行的独立实例。</p>
     *
     * @return 节点实例
     */
    default FlowNode cloneNode() {
        return this;
    }

    /**
     * 获取节点配置字段列表。
     *
     * <p>用于流程清单展示时展示当前节点的配置项。未提供配置字段时返回空列表。</p>
     *
     * @return 节点配置字段列表
     */
    default List<FlowNodeField> configSchema() {
        return new ArrayList<>();
    }
}
