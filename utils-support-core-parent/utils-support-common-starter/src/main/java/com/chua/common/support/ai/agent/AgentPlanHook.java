package com.chua.common.support.ai.agent;


/**
 * Agent 规划 Hook
 *
 * <p>在 Plan 模式相关阶段回调：进入规划、写出计划、子任务推进、退出规划等。
 * 由实现方（如 AgentScope）在框架 Hook / PlanMode 点桥接回调。
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface AgentPlanHook {

    /**
     * 规划事件回调
     *
     * @param event 规划事件
     */
    void onPlan(AgentHookEvent event);
}
