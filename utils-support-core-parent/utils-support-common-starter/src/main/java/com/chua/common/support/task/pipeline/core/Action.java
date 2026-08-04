package com.chua.common.support.task.pipeline.core;

import org.jspecify.annotations.NullUnmarked;

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
 *   <li><strong>EXIT</strong> — 终止当前流水线执行</li>
 *   <li><strong>REPLAY</strong> — 重播当前节点</li>
 *   <li><strong>WAIT</strong> — 挂起等待外部触发 {@link Pipeline#resume(PipelineContext)}</li>
 * </ul>
 *
 * @author CH
 */
@NullUnmarked
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
