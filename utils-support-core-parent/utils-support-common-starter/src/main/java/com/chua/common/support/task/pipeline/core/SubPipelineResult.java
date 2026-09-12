package com.chua.common.support.task.pipeline.core;

import java.util.*;

/**
 * 子流水线节点执行结果。
 *
 * <p>封装子流水线的输出数据，作为结构化结果存入父上下文的 {@code nodeOutputs}。</p>
 *
 * <p><strong>存储模型：</strong></p>
 * <pre>
 * nodeOutputs["subStep"] = SubPipelineResult {
 *     nodeId: "subStep",
 *     output: data,               // 子流水线的最终输出（currentData）
 *     history: ["subA", "subB"],  // 子流水线的执行历史
 *     pipelineId: "sub"           // 子流水线 ID
 * }
 * </pre>
 *
 * <p><strong>与之前存储方式的对比：</strong></p>
 * <table>
 *   <tr><th>方式</th><th>数据获取</th><th>历史获取</th></tr>
 *   <tr>
 *     <td>旧</td>
 *     <td>{@code ctx.getCurrentData()}（直接写回）</td>
 *     <td>{@code ctx.getAttribute("subPipelineHistory")}</td>
 *   </tr>
 *   <tr>
 *     <td>新</td>
 *     <td>{@code ctx.getData("subStep", SubPipelineResult.class).getOutput()}</td>
 *     <td>{@code ctx.getData("subStep", SubPipelineResult.class).getHistory()}</td>
 *   </tr>
 * </table>
 *
 * <p>结构化存储的优势：</p>
 * <ul>
 *   <li>key统一为nodeId，与普通节点/并行节点一致</li>
 *   <li>子流程结果内聚在对象里，不散落在currentData和attributes</li>
 *   <li>支持unit能力：{@code ctx.getData("subStep")} 即可获取完整结果</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see com.chua.common.support.task.pipeline.node.SubPipelineNode
 */
public class SubPipelineResult {

    /**
      * 子流水线节点 标识
     */
    private final String nodeId;

    /**
      * 子流水线的最终输出数据（子上下文的 当前数据）
     */
    private final Object output;

    /**
      * 子流水线的执行历史（子上下文的 历史）
     */
    private final List<String> history;

    /**
      * 子流水线 标识
     */
    private final String pipelineId;

    /**
     * 构造子流水线结果。
     *
     * @param nodeId     子流水线节点 标识
     * @param output     子流水线的最终输出数据
     * @param history    子流水线的执行历史
     * @param pipelineId 子流水线 标识
     */
    public SubPipelineResult(String nodeId, Object output, List<String> history, String pipelineId) {
        this.nodeId = nodeId;
        this.output = output;
        this.history = history != null ? new ArrayList<>(history) : new ArrayList<>();
        this.pipelineId = pipelineId;
    }

    /**
      * 获取子流水线节点 标识。
     *
     * @return 节点 标识
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取子流水线的最终输出数据。
     *
     * @param <V> 数据值类型
     * @return 输出数据
     */
    @SuppressWarnings("unchecked")
    public <V> V getOutput() {
        return (V) output;
    }

    /**
     * 获取子流水线的最终输出数据（带类型转换）。
     *
     * @param type 期望的数据类型
     * @param <V>  数据值类型
     * @return 输出数据，不存在时返回 空
     */
    @SuppressWarnings("unchecked")
    public <V> V getOutput(Class<V> type) {
        return output != null ? (V) type.cast(output) : null;
    }

    /**
     * 获取子流水线的执行历史。
     *
     * @return 执行历史节点ID列表的不可变视图
     */
    public List<String> getHistory() {
        return Collections.unmodifiableList(history);
    }

    /**
      * 获取子流水线 标识。
     *
     * @return 子流水线 标识
     */
    public String getPipelineId() {
        return pipelineId;
    }

    /** 返回含子流水线执行信息的调试字符串。 */
    @Override
    public String toString() {
        return "SubPipelineResult{" +
                "nodeId='" + nodeId + '\'' +
                ", pipelineId='" + pipelineId + '\'' +
                ", history=" + history +
                '}';
    }
}
