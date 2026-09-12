package com.chua.common.support.task.flow;

/**
* 流程实例状态枚举。
*
* <p>描述流程实例从创建到结束的完整生命周期状态，
* 用于流程编排引擎判断实例当前所处阶段以及是否可以继续执行。</p>
*
* <ul>
*   <li><strong>NEW</strong> — 实例已创建，尚未开始执行，可接受首次运行</li>
*   <li><strong>RUNNING</strong> — 实例正在执行节点链路中</li>
*   <li><strong>WAITED</strong> — 实例已挂起，等待外部触发恢复（如异步任务完成回调）</li>
*   <li><strong>COMPLETED</strong> — 实例已执行完成，流程正常结束</li>
*   <li><strong>FAILED</strong> — 实例执行失败，节点抛出异常中断</li>
*   <li><strong>TERMINATED</strong> — 实例被外部主动终止</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public enum FlowStatus {

    /**
    * 实例已创建，尚未开始执行
     */
    NEW,

    /**
    * 实例正在执行节点链路中
     */
    RUNNING,

    /**
    * 实例已挂起，等待外部触发恢复
     */
    WAITED,

    /**
    * 实例已执行完成，流程正常结束
     */
    COMPLETED,

    /**
    * 实例执行失败，节点抛出异常中断
     */
    FAILED,

    /**
    * 实例被外部主动终止
     */
    TERMINATED
}
