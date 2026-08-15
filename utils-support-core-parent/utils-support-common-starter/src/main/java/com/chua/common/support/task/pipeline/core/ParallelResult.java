package com.chua.common.support.task.pipeline.core;

import java.util.*;

/**
 * 并行节点执行结果。
 *
 * <p>封装并行节点所有分支的输出数据，作为结构化结果存入父上下文的 {@code nodeOutputs}。</p>
 *
 * <p><strong>存储模型：</strong></p>
 * <pre>
 * nodeOutputs["parallel1"] = ParallelResult {
 *     nodeId: "parallel1",
 *     branches: {
 *         "branchA": dataA,    // 分支A的最终输出（currentData）
 *         "branchB": dataB     // 分支B的最终输出（currentData）
 *     },
 *     histories: {
 *         "branchA": ["a1", "a2"],  // 分支A的执行历史
 *         "branchB": ["b1", "b2"]   // 分支B的执行历史
 *     }
 * }
 * </pre>
 *
 * <p><strong>与之前扁平存储方式的对比：</strong></p>
 * <table>
 *   <tr><th>方式</th><th>存储key</th><th>获取方式</th></tr>
 *   <tr>
 *     <td>旧（扁平）</td>
 *     <td>{@code parallel:p1:branchA}, {@code parallel:p1:branchB}</td>
 *     <td>{@code ctx.getNodeOutput("parallel:p1:branchA")}</td>
 *   </tr>
 *   <tr>
 *     <td>新（结构化）</td>
 *     <td>{@code p1}</td>
 *     <td>{@code ctx.getData("p1", ParallelResult.class).getBranch("branchA")}</td>
 *   </tr>
 * </table>
 *
 * <p>结构化存储的优势：</p>
 * <ul>
 *   <li>key统一为nodeId，调用方不需要知道节点类型</li>
 *   <li>并行结果内聚在对象里，分支数据不散落</li>
 *   <li>支持unit能力：{@code ctx.getData("p1")} 即可获取完整结果</li>
 * </ul>
 *
 * @author CH
 * @see com.chua.common.support.task.pipeline.node.ParallelNode
 */
public class ParallelResult {

    /**
     * 并行节点 ID
     */
    private final String nodeId;

    /**
     * 分支输出数据：分支名称 → 分支的最终输出（currentData）
     */
    private final Map<String, Object> branches;

    /**
     * 分支执行历史：分支名称 → 分支已执行节点ID列表
     */
    private final Map<String, List<String>> histories;

    /**
     * 构造并行结果。
     *
     * @param nodeId   并行节点 ID
     * @param branches 分支输出数据
     * @param histories 分支执行历史
     */
    public ParallelResult(String nodeId, Map<String, Object> branches, Map<String, List<String>> histories) {
        this.nodeId = nodeId;
        this.branches = branches != null ? new LinkedHashMap<>(branches) : new LinkedHashMap<>();
        this.histories = histories != null ? new LinkedHashMap<>(histories) : new LinkedHashMap<>();
    }

    /**
     * 获取并行节点 ID。
     *
     * @return 节点 ID
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取所有分支输出数据。
     *
     * @return 分支名称 → 输出数据的不可变映射
     */
    public Map<String, Object> getBranches() {
        return Collections.unmodifiableMap(branches);
    }

    /**
     * 获取指定分支的输出数据。
     *
     * @param branchName 分支名称
     * @param <V>        数据值类型
     * @return 分支输出数据，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <V> V getBranch(String branchName) {
        return (V) branches.get(branchName);
    }

    /**
     * 获取指定分支的输出数据（带类型转换）。
     *
     * @param branchName 分支名称
     * @param type       期望的数据类型
     * @param <V>        数据值类型
     * @return 分支输出数据，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <V> V getBranch(String branchName, Class<V> type) {
        Object value = branches.get(branchName);
        return value != null ? (V) type.cast(value) : null;
    }

    /**
     * 获取所有分支的执行历史。
     *
     * @return 分支名称 → 执行历史节点ID列表的不可变映射
     */
    public Map<String, List<String>> getHistories() {
        return Collections.unmodifiableMap(histories);
    }

    /**
     * 获取指定分支的执行历史。
     *
     * @param branchName 分支名称
     * @return 执行历史节点ID列表，不存在时返回空列表
     */
    public List<String> getHistory(String branchName) {
        List<String> history = histories.get(branchName);
        return history != null ? Collections.unmodifiableList(history) : Collections.emptyList();
    }

    /**
     * 获取分支数量。
     *
     * @return 分支数量
     */
    public int getBranchCount() {
        return branches.size();
    }

    /**
     * 判断指定分支是否存在。
     *
     * @param branchName 分支名称
     * @return 存在时返回 true
     */
    public boolean hasBranch(String branchName) {
        return branches.containsKey(branchName);
    }

    @Override
    public String toString() {
        return "ParallelResult{" +
                "nodeId='" + nodeId + '\'' +
                ", branches=" + branches.keySet() +
                ", histories=" + histories.keySet() +
                '}';
    }
}