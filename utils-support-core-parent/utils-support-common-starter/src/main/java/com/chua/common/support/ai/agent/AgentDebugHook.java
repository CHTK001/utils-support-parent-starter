package com.chua.common.support.ai.agent;


/**
* Agent 调试 Hook
*
* <p>在 Agent 执行过程中接收调试事件（调用前后、推理、工具执行、错误等）。
* 由实现方（如 AgentScope）在框架 Hook 点桥接回调。
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface AgentDebugHook {

    /**
    * 调试事件回调
    *
    * @param event 调试事件
    */
    void onDebug(AgentHookEvent event);
}
