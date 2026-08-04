package com.chua.common.support.task.flow;

import java.util.Map;

/**
 * 流程实例接口。
 *
 * <p>一次流程运行的生命周期载体，持有唯一执行上下文。
 * 多次 {@link #run(Map)} 均作用于同一上下文，实现跨节点数据共享与状态累积，
 * 满足"运行必定同一个上下文"的编排约束。</p>
 *
 * <p>典型生命周期：</p>
 * <ul>
 *   <li>{@link #run(Map)} 首次调用 — 从起始节点开始执行，参数写入上下文</li>
 *   <li>节点执行 {@link #waitForResume()} — 实例进入 {@link FlowStatus#WAITED} 挂起</li>
 *   <li>{@link #resume()} — 从挂起点继续执行，上下文保持不变</li>
 *   <li>执行至终止节点 — 实例进入 {@link FlowStatus#COMPLETED} 完成</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * FlowInstance instance = flow.createInstance();
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
     * 获取当前正在执行节点的配置属性。
     *
     * <p>由引擎注入当前节点 ID 对应的属性，节点执行器据此读取参数。</p>
     *
     * @return 当前节点属性
     */
    FlowProps currentNodeProps();

    /**
     * 获取当前正在执行的节点 ID。
     *
     * @return 节点 ID
     */
    String getCurrentNodeId();

    /**
     * 获取当前处理数据。
     *
     * @return 当前数据，可能为 null
     */
    Object getCurrentData();

    /**
     * 更新当前处理数据。
     *
     * <p>节点可写入处理后数据，供下游节点继续消费。</p>
     *
     * @param data 当前数据
     */
    void setCurrentData(Object data);

    /**
     * 获取实例上下文属性映射。
     *
     * @return 上下文属性映射
     */
    Map<String, Object> getAttributes();

    /**
     * 写入上下文属性。
     *
     * <p>属性在实例生命周期内共享，供其他节点跨节点读取。</p>
     *
     * @param key   属性键
     * @param value 属性值
     */
    void setAttribute(String key, Object value);

    /**
     * 读取上下文属性。
     *
     * @param key 属性键
     * @param <T> 属性值类型
     * @return 属性值，不存在时返回 null
     */
    <T> T getAttribute(String key);

    /**
     * 挂起当前实例。
     *
     * <p>节点调用后实例进入 {@link FlowStatus#WAITED} 状态，
     * 等待外部触发 {@link #resume()} 继续执行，上下文保持不变。</p>
     */
    void waitForResume();

    /**
     * 退出当前流程。
     *
     * <p>节点调用后流程立即终止，后续节点不再执行。</p>
     */
    void exit();
}
