package com.chua.common.support.task.flow;

import java.util.Map;

/**
 * 流程实例接口。
 *
 * <p>一次流程运行的生命周期载体，内部持有唯一的 {@link FlowContext} 上下文。
 * 多次 {@link #run(Map)} 均作用于同一上下文，实现跨节点数据共享与状态累积，
 * 满足"运行必定同一个上下文"的编排约束。</p>
 *
 * <p>典型生命周期：</p>
 * <ul>
 *   <li>{@link #run(Map)} 首次调用 — 从起始节点开始执行，参数写入上下文</li>
 *   <li>节点执行 {@link FlowContext#waitForResume()} — 实例进入 {@link FlowStatus#WAITED} 挂起</li>
 *   <li>{@link #resume()} — 从挂起点继续执行，上下文保持不变</li>
 *   <li>执行至终止节点 — 实例进入 {@link FlowStatus#COMPLETED} 完成</li>
 * </ul>
 *
 * <p>节点与上下文的交互统一通过 {@link #getContext()} 完成，
 * 数据读写、属性存取、流转控制均委托给上下文实现。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * FlowInstance instance = flow.createGraph().createInstance();
 * instance.run(Map.of("bizId", "1"));
 * instance.run(Map.of("bizId", "2"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FlowInstance {

    /**
     * 获取实例唯一标识。
     *
     * @return 实例 ID
     */
    String getInstanceId();

    /**
     * 获取所属流程定义 ID。
     *
     * @return 流程 ID
     */
    String getFlowId();

    /**
     * 获取实例当前状态。
     *
     * @return 实例状态
     */
    FlowStatus getStatus();

    /**
     * 启动或续跑流程，不携带参数。
     *
     * <p>首次调用从起始节点开始执行；已挂起实例调用将合并执行上下文后继续。</p>
     *
     * @return 当前实例
     */
    FlowInstance run();

    /**
     * 启动或续跑流程，并传入运行参数。
     *
     * <p>参数合并写入实例上下文，运行期间各节点可读取。
     * 首次运行创建上下文，后续运行复用同一上下文。</p>
     *
     * @param params 运行参数
     * @return 当前实例
     */
    FlowInstance run(Map<String, Object> params);

    /**
     * 恢复被挂起的实例。
     *
     * <p>从挂起点继续执行后续节点，上下文保持不变。
     * 仅在状态为 {@link FlowStatus#WAITED} 时有效。</p>
     *
     * @return 当前实例
     */
    FlowInstance resume();

    /**
     * 终止实例执行。
     *
     * <p>立即中断流程，实例进入 {@link FlowStatus#TERMINATED} 状态。</p>
     */
    void terminate();

    /**
     * 判断实例是否已执行完成。
     *
     * @return 完成返回 true，否则返回 false
     */
    boolean isCompleted();

    /**
     * 获取实例唯一执行上下文。
     *
     * <p>节点通过上下文读写数据、存取属性、控制流转，
     * 上下文在实例生命周期内保持不变。</p>
     *
     * @return 实例执行上下文
     */
    FlowContext getContext();

    /**
     * 设置单节点最大执行次数上限。
     *
     * <p>需在首次 {@link #run()} 前调用。
     * 节点执行次数达到上限时引擎判定死循环并终止流程，
     * 当上限 <= 0 时恢复默认值。</p>
     *
     * @param maxLoopCount 执行次数上限
     * @return 当前实例
     */
    FlowInstance maxLoopCount(int maxLoopCount);
}
