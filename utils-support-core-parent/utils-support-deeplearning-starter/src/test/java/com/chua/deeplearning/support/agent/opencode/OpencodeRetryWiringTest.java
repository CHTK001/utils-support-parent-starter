package com.chua.deeplearning.support.agent.opencode;

import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentRetryConfig;
import com.chua.common.support.ai.memory.MemoryConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * opencode 适配层的重试面测试。
 *
 * <p>opencode 在独立进程内自管重试与退避，适配层无法插入重试钩子，因此：
 * 「不启用重试」的取值（{@code maxRetries == 0}、{@code null}、{@code baseDelay <= 0}）
 * 正常接受；一旦要求真正重试就必须显式失败并给出替代做法，
 * 不能出现「存下来但从不生效」的静默 no-op。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class OpencodeRetryWiringTest {

    /**
     * 启用重试必须显式失败，且给出外部重试的替代做法。
     */
    @Test
    void maxRetriesFailsExplicitlyWhenRetryRequested() {
        OpencodeServerAgent agent = new OpencodeServerAgent();

        UnsupportedOperationException positive = assertThrows(UnsupportedOperationException.class,
                () -> agent.maxRetries(3));
        assertTrue(positive.getMessage().contains("RetryFlow"),
                "必须指向外部重试替代做法: " + positive.getMessage());

        assertThrows(UnsupportedOperationException.class, () -> agent.maxRetries(-1),
                "无限重试同样无法在 opencode 进程内生效");
    }

    /**
     * 不启用重试的取值必须被接受，保持链式调用。
     */
    @Test
    void maxRetriesAcceptsZero() {
        OpencodeServerAgent agent = new OpencodeServerAgent();
        assertSame(agent, agent.maxRetries(0));
    }

    /**
     * 退避策略无法在 opencode 进程内生效，设置非 null 值必须显式失败。
     */
    @Test
    void retryBackoffFailsExplicitly() {
        OpencodeServerAgent agent = new OpencodeServerAgent();

        UnsupportedOperationException fixed = assertThrows(UnsupportedOperationException.class,
                () -> agent.retryBackoff(AgentRetryConfig.BackoffStrategy.FIXED));
        assertTrue(fixed.getMessage().contains("RetryFlow"),
                "必须指向外部重试替代做法: " + fixed.getMessage());

        assertSame(agent, agent.retryBackoff(null));
    }

    /**
     * 退避基础延迟无法在 opencode 进程内生效，正值必须显式失败。
     */
    @Test
    void retryBaseDelayFailsExplicitly() {
        OpencodeServerAgent agent = new OpencodeServerAgent();

        UnsupportedOperationException positive = assertThrows(UnsupportedOperationException.class,
                () -> agent.retryBaseDelay(1000L));
        assertTrue(positive.getMessage().contains("RetryFlow"),
                "必须指向外部重试替代做法: " + positive.getMessage());

        assertSame(agent, agent.retryBaseDelay(0L));
    }

    /**
     * 完整重试配置：maxRetries 非 0 必须显式失败，0 与 null 被接受。
     */
    @Test
    void retryConfigFailsExplicitlyOnActiveRetry() {
        OpencodeServerAgent agent = new OpencodeServerAgent();

        AgentRetryConfig retry = AgentRetryConfig.builder()
                .maxRetries(2)
                .baseDelayMillis(500L)
                .backoffStrategy(AgentRetryConfig.BackoffStrategy.LINEAR)
                .build();
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> agent.retryConfig(retry));
        assertTrue(error.getMessage().contains("RetryFlow"),
                "必须指向外部重试替代做法: " + error.getMessage());

        assertSame(agent, agent.retryConfig(null));
        assertSame(agent, agent.retryConfig(AgentRetryConfig.builder().maxRetries(0).build()));
    }

    /**
     * AgentDefinition 携带有效重试配置时同样必须在装配阶段失败。
     */
    @Test
    void definitionWithRetryConfigFailsExplicitly() {
        AgentDefinition definition = AgentDefinition.builder()
                .id("retry-agent")
                .name("retry-agent")
                .retryConfig(AgentRetryConfig.builder().maxRetries(3).build())
                .build();

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> new OpencodeServerAgent().definition(definition));
        assertTrue(error.getMessage().contains("retryConfig"),
                "必须点明 retryConfig 不被接受: " + error.getMessage());
    }

    /**
     * opencode 自管会话记忆：进程内没有 MemoryManager，MemoryConfig 必须显式失败。
     */
    @Test
    void memoryConfigFailsExplicitlyAndMemoryManagerIsNull() {
        OpencodeServerAgent agent = new OpencodeServerAgent();

        assertNull(agent.getMemoryManager(),
                "opencode 自管会话记忆，不应暴露进程内 MemoryManager");

        MemoryConfig config = MemoryConfig.builder().build();
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> agent.memoryConfig(config));
        assertTrue(error.getMessage().contains("AGENTS.md"),
                "必须指向项目级记忆的替代做法: " + error.getMessage());
    }
}
