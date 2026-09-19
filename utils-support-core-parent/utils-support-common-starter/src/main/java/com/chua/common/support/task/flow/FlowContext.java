package com.chua.common.support.task.flow;

import java.util.List;
import java.util.Map;

/**
 * 流程运行上下文接口。
 *
 * <p>一次流程运行期间节点间共享的数据载体，替代旧的"节点直接操作实例"模式。
 * 节点执行器通过上下文完成以下交互：</p>
 * <ul>
 *   <li>读写共享属性：{@link #getAttribute(String)}、{@link #setAttribute(String, Object)}</li>
 *   <li>读写当前数据：{@link #getData()}、{@link #setData(Object)}</li>
 *   <li>读取节点配置：{@link #currentNodeProps()}</li>
 *   <li>控制流程流向：{@link #setNextNodeId(String)}、{@link #waitForResume()}、{@link #exit()}</li>
 *   <li>执行轨迹：{@link #getExecutionTrace()}、{@link #getExecuteCount(String)}（回放/审计/防死循环）</li>
 * </ul>
 *
 * <p>上下文由 {@link FlowInstance} 持有，同一实例的多次运行共享同一上下文，
 * 保证"运行必定同一个上下文"的编排约束。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FlowContext {

    /**
     * 获取所属流程定义 标识。
     *
     * @return 流程 标识
     */
    String getFlowId();

    /**
     * 获取当前正在执行的节点 标识。
     *
     * @return 节点 标识
     */
    String getCurrentNodeId();

    /**
     * 设置当前正在执行的节点 标识。
     *
     * @param nodeId 节点 标识
     */
    void setCurrentNodeId(String nodeId);

    /**
     * 获取当前处理数据。
     *
     * @return 当前数据，可能为 空
     */
    Object getData();

    /**
     * 更新当前处理数据。
     *
     * <p>节点可写入处理后数据，供下游节点继续消费。</p>
     *
     * @param data 当前数据
     */
    void setData(Object data);

    /**
     * 获取上下文属性映射。
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
     * @return 属性值，不存在时返回 空
     */
    <T> T getAttribute(String key);

    /**
     * 获取当前正在执行节点的配置属性。
     *
     * <p>由引擎注入当前节点 ID 对应的属性，节点执行器据此读取参数。</p>
     *
     * @return 当前节点属性
     */
    FlowProps currentNodeProps();

    /**
     * 获取当前节点下一节点 标识。
     *
     * @return 下一节点 标识，未指定时返回 空
     */
    String getNextNodeId();

    /**
     * 指定下一节点 标识。
     *
     * <p>节点可手动指定下一节点覆盖默认顺序边的走向，
     * 用于实现跳转、循环等自定义流转。</p>
     *
     * @param nodeId 下一节点 标识
     */
    void setNextNodeId(String nodeId);

    /**
     * 挂起当前流程。
     *
     * <p>节点调用后流程进入 {@link FlowStatus#WAITED} 状态，
     * 等待外部触发恢复继续执行，上下文保持不变。</p>
     */
    void waitForResume();

    /**
     * 退出当前流程。
     *
     * <p>节点调用后流程立即终止，后续节点不再执行。
     * 通常配合循环防护使用，作为手动跳出流程的出口。</p>
     */
    void exit();

    /**
     * 获取执行轨迹。
     *
     * <p>按执行顺序记录全部已执行节点 ID，供回放、审计与前端展示使用。</p>
     *
     * @return 执行轨迹（节点 标识 序列）
     */
    List<String> getExecutionTrace();

    /**
     * 获取执行轨迹记录列表。
     *
     * <p>按执行顺序记录每个节点的输入输出快照（见 {@link FlowTrace}），
     * 重放时直接消费快照，无需重新执行节点逻辑。</p>
     *
     * @return 执行轨迹记录列表
     */
    List<FlowTrace> getTraces();

    /**
     * 获取指定节点的累计执行次数。
     *
     * <p>用于检测循环执行与死循环防护，结合 {@link #getMaxLoopCount()} 使用。</p>
     *
     * @param nodeId 节点 标识
     * @return 执行次数
     */
    int getExecuteCount(String nodeId);

    /**
     * 获取单节点最大执行次数上限。
     *
     * <p>节点执行次数达到该上限时，引擎判定死循环并终止流程，
     * 防止循环图导致流程无法结束。</p>
     *
     * @return 执行次数上限
     */
    int getMaxLoopCount();

    /**
     * 设置单节点最大执行次数上限。
     *
     * @param maxLoopCount 执行次数上限
     */
    void setMaxLoopCount(int maxLoopCount);

    /**
     * 获取挂起时待执行的后续节点 标识 快照。
     *
     * <p>供暂停/恢复时查看"当前节点之后的待执行目标"，
     * 结合 {@link #getCurrentNodeId()} 恢复精确续跑。</p>
     *
     * @return 待执行节点 标识 列表
     */
    List<String> getPendingNodeIds();
}
