package com.chua.common.support.task.flow;

/**
* 流程执行轨迹记录。
*
* <p>描述一次流程运行中单个节点的执行痕迹，供回放、审计与前端展示使用。
* 记录节点执行时的输入数据（执行前当前数据）与输出数据（执行后当前数据），
* 重放时直接消费输入输出快照，无需重新执行节点逻辑。</p>
*
* @param nodeId    节点唯一标识
* @param input     节点执行前当前数据（输入快照）
* @param output    节点执行后当前数据（输出快照）
* @param timestamp 执行时间戳（毫秒）
* @author CH
* @since 4.0.0.42
 */
public record FlowTrace(
        String nodeId,
        Object input,
        Object output,
        long timestamp
) {

    /**
    * 创建执行轨迹记录。
    *
    * @param nodeId 节点唯一标识
    * @param input  节点执行前当前数据
    * @param output 节点执行后当前数据
    * @return 执行轨迹记录实例
     */
    public static FlowTrace of(String nodeId, Object input, Object output) {
        return new FlowTrace(nodeId, input, output, System.currentTimeMillis());
    }
}
