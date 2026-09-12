package com.chua.common.support.task.pipeline.core;


/**
* 流水线动作枚举。
*
* <p>用于控制流水线的执行流程，每个节点执行完毕后可通过 {@link PipelineContext#setAction(Action)}
* 指定下一步行为，引擎根据动作类型决定后续执行逻辑。</p>
*
* <ul>
*   <li><strong>NEXT</strong> — 按默认顺序执行下一个节点</li>
*   <li><strong>PREV</strong> — 回退到上一个已执行节点</li>
*   <li><strong>JUMP</strong> — 跳转到 {@link PipelineContext#setNextNodeId(String)} 指定的节点</li>
*   <li><strong>BREAK</strong> — 中断当前分支，跳转到当前分支之后的节点继续执行</li>
*   <li><strong>EXIT</strong> — 终止当前流水线执行</li>
*   <li><strong>REPLAY</strong> — 重播当前节点</li>
*   <li><strong>WAIT</strong> — 挂起等待外部触发 {@link Pipeline#resume(PipelineContext)}</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public enum Action {

    /**
    * 按默认顺序执行下一个节点
     */
    NEXT,

    /**
    * 回退到上一个已执行节点
     */
    PREV,

    /**
    * 跳转到指定节点，需同时调用 {@link PipelineContext#setNextNodeId(String)}
     */
    JUMP,

    /**
    * 中断当前分支，跳转到当前分支之后的节点继续执行。
    *
    * <p>与 {@link #EXIT} 的区别：EXIT 终止整个流水线，BREAK 仅中断当前分支，
    * 流水线继续执行分支之后的节点。适用于分支内的提前退出场景。</p>
    *
    * <p>使用场景：在 decision 分支中，某个条件满足后无需继续执行分支内后续节点，
    * 但仍需执行分支外的公共收尾逻辑。</p>
     */
    BREAK,

    /**
    * 终止当前流水线执行
     */
    EXIT,

    /**
    * 重播当前节点
     */
    REPLAY,

    /**
    * 挂起等待外部触发恢复
     */
    WAIT
}
