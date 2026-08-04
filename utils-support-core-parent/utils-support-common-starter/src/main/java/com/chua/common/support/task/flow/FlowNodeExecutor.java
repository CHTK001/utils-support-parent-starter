package com.chua.common.support.task.flow;

/**
 * 流程节点执行器接口。
 *
 * <p>流程图中每种节点类型的执行逻辑载体。实现类需同时标注 {@link FlowNode} 注解
 * 并注册为 SPI 扩展（{@code @Spi}），运行时由 {@link FlowNodeRegistry} 按节点类型
 * 实例化并注入当前流程实例。</p>
 *
 * <p>节点执行器通过 {@link FlowInstance} 完成以下交互：</p>
 * <ul>
 *   <li>读取当前节点配置：{@link FlowInstance#currentNodeProps()}</li>
 *   <li>读写共享上下文：{@link FlowInstance#getAttribute(String)}、
 *       {@link FlowInstance#setAttribute(String, Object)}</li>
 *   <li>更新当前数据：{@link FlowInstance#getCurrentData()}、
 *       {@link FlowInstance#setCurrentData(Object)}</li>
 *   <li>控制流程流向：{@link FlowInstance#waitForResume()} 挂起、
 *       {@link FlowInstance#exit()} 终止</li>
 * </ul>
 *
 * <p>实现示例：</p>
 * <pre>{@code
 * @Spi("spider")
 * @FlowNode(value = "spider", describe = "爬虫抓取")
 * public class SpiderFlowNode implements FlowNodeExecutor {
 *     @Override
 *     public void execute(FlowInstance instance) {
 *         FlowProps props = instance.currentNodeProps();
 *         List<String> urls = props.getStringList("urls");
 *         // 执行爬虫并写入上下文
 *         instance.setAttribute("spider.result", results);
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FlowNodeExecutor {

    /**
     * 执行节点逻辑。
     *
     * <p>由流程引擎在调度到当前节点时调用。执行期间可通过实例读写上下文数据、
     * 控制流程动作；抛出异常会被引擎捕获并标记实例为失败状态。</p>
     *
     * @param instance 当前流程实例
     */
    void execute(FlowInstance instance);
}
