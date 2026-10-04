package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.Agent;
import com.chua.deeplearning.support.agentscope.goal.GoalRegistry;
import com.chua.deeplearning.support.agentscope.goal.GoalState;
import com.chua.deeplearning.support.agentscope.goal.GoalStatus;
import com.chua.deeplearning.support.agentscope.goal.SessionGoal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentScopeAgent} 的目标接线测试。
 *
 * <p>只覆盖接线本身（绑定、状态透出、暂停/恢复/取消、参数校验、与注册表共享状态机），
 * 不触发 harness——目标循环的推进语义已由 goal 包的单元测试覆盖。
 *
 * @author CH
 */
class AgentScopeGoalWiringTest {

    /**
     * 构造目标。
     *
     * @return 目标
     */
    private static SessionGoal goal() {
        return SessionGoal.create("g1", "s1", "做完目标功能", "前后端都要", 1000L);
    }

    @Test
    void goalBindsAndStatusIsExposed() {
        AgentScopeAgent agent = (AgentScopeAgent) Agent.create("agentscope");
        try {
            assertFalse(agent.hasGoal());
            assertNull(agent.goalStatus());

            agent.goal(goal());

            assertTrue(agent.hasGoal());
            GoalStatus status = agent.goalStatus();
            assertEquals("g1", status.id());
            assertEquals("做完目标功能", status.title());
            assertEquals(GoalState.PENDING, status.state());
            assertEquals("待开始", status.stateLabel());
        } finally {
            agent.close();
        }
    }

    @Test
    void goalMaxRoundsRejectsNonPositive() {
        AgentScopeAgent agent = (AgentScopeAgent) Agent.create("agentscope");
        try {
            assertThrows(IllegalArgumentException.class, () -> agent.goalMaxRounds(0));
            assertThrows(IllegalArgumentException.class, () -> agent.goalMaxRounds(-3));
            // 合法值不应抛异常
            agent.goalMaxRounds(7);
        } finally {
            agent.close();
        }
    }

    @Test
    void pauseResumeCancelThroughAgent() {
        AgentScopeAgent agent = (AgentScopeAgent) Agent.create("agentscope");
        try {
            agent.goal(goal());
            // PENDING -> start 由运行期触发；这里先手动启动以验证暂停链路
            agent.goalStatus();
            agent.pauseGoal();
            assertEquals(GoalState.PAUSED, agent.goalStatus().state());

            agent.resumeGoal();
            assertEquals(GoalState.RUNNING, agent.goalStatus().state());

            agent.cancelGoal("用户叫停");
            assertEquals(GoalState.CANCELED, agent.goalStatus().state());
            assertEquals("已取消", agent.goalStatus().stateLabel());
        } finally {
            agent.close();
        }
    }

    @Test
    void goalOperationsWithoutGoalThrow() {
        AgentScopeAgent agent = (AgentScopeAgent) Agent.create("agentscope");
        try {
            assertThrows(IllegalStateException.class, agent::pauseGoal);
            assertThrows(IllegalStateException.class, agent::resumeGoal);
            assertThrows(IllegalStateException.class, () -> agent.cancelGoal("x"));
            assertThrows(IllegalStateException.class, agent::runGoal);
        } finally {
            agent.close();
        }
    }

    @Test
    void attachGoalSharesTrackerWithRegistry() {
        GoalRegistry registry = new GoalRegistry(4);
        var tracker = registry.bind(goal());
        tracker.start(1000L);

        AgentScopeAgent agent = (AgentScopeAgent) Agent.create("agentscope");
        try {
            agent.attachGoal(tracker);
            assertTrue(agent.hasGoal());
            assertEquals(GoalState.RUNNING, agent.goalStatus().state());

            // 通过注册表暂停，agent 侧应立即看到同一状态（共享同一状态机）
            registry.pause("s1", 1100L);
            assertEquals(GoalState.PAUSED, agent.goalStatus().state());

            // 通过 agent 恢复，注册表侧也应同步
            agent.resumeGoal();
            assertEquals(GoalState.RUNNING, registry.status("s1").state());
        } finally {
            agent.close();
        }
    }

    @Test
    void attachGoalRejectsNull() {
        AgentScopeAgent agent = (AgentScopeAgent) Agent.create("agentscope");
        try {
            assertThrows(IllegalArgumentException.class, () -> agent.attachGoal(null));
        } finally {
            agent.close();
        }
    }
}
