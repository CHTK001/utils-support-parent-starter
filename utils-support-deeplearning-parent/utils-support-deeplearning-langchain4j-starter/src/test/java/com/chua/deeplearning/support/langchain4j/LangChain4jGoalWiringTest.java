package com.chua.deeplearning.support.langchain4j;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentStreamEvent;
import com.chua.common.support.ai.agent.goal.GoalRegistry;
import com.chua.common.support.ai.agent.goal.GoalState;
import com.chua.common.support.ai.agent.goal.GoalStatus;
import com.chua.common.support.ai.agent.goal.SessionGoal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LangChain4jAgent} 的目标接线与能力边界测试。
 *
 * <p>只覆盖接线本身（绑定、状态透出、暂停/恢复/取消、参数校验、与注册表共享状态机）
 * 以及「不支持的能力必须显式失败」，不触发模型调用。
 *
 * @author CH
 * @since 4.0.0.50
 */
class LangChain4jGoalWiringTest {

    /**
     * 构造目标。
     *
     * @return 目标
     */
    private static SessionGoal goal() {
        return SessionGoal.create("g1", "s1", "做完目标功能", "前后端都要", 1000L);
    }

    @Test
    void spiResolvesLangChain4jProvider() {
        Agent agent = Agent.create("langchain4j");
        try {
            assertTrue(agent instanceof LangChain4jAgent);
        } finally {
            agent.close();
        }
    }

    @Test
    void goalBindsAndStatusIsExposed() {
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
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
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
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
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            agent.goal(goal());
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
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
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

        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
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
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            assertThrows(IllegalArgumentException.class, () -> agent.attachGoal(null));
            assertThrows(IllegalArgumentException.class, () -> agent.goal(null));
        } finally {
            agent.close();
        }
    }

    @Test
    void unsupportedCapabilitiesFailLoudly() {
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            // 没有内置 shell 工具：开启必须显式失败
            assertThrows(UnsupportedOperationException.class, () -> agent.shellTool(true));

            // 载荷校验非法时必须显式失败
            assertThrows(IllegalArgumentException.class, () -> agent.toolResultEviction(-1, 0));

            // 未配置模型时流式必须给出 ERROR 事件，而不是静默空流
            List<AgentStreamEvent> events = agent.runStream("hi").collectList().block();
            assertTrue(events != null && !events.isEmpty());
            assertEquals(AgentStreamEvent.Type.ERROR, events.get(0).getType());

            // 结构化提问与人工确认恢复不支持：必须回 ERROR 事件
            List<AgentStreamEvent> questionEvents = agent.answerQuestion("reply-1", List.of())
                    .collectList().block();
            assertTrue(questionEvents != null && !questionEvents.isEmpty());
            assertEquals(AgentStreamEvent.Type.ERROR, questionEvents.get(0).getType());
        } finally {
            agent.close();
        }
    }
}
