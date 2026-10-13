package com.chua.deeplearning.support.agentscope;

import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待批工具块缓存的回归测试。
 *
 * <p>对应缺陷：{@code PENDING_BLOCKS} 是 static Map，写入后<b>从不移除</b>，
 * 每次人工确认都把完整工具入参永久留在堆里，是长驻服务的确定性内存泄漏。
 * 本类锁定「消费即逐出」与「超限有界」两条性质。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class PendingBlocksCacheTest {

    /**
     * 构造一条待批工具块。
     *
     * @param id   调用标识
     * @param name 工具名
     * @return 工具块
     */
    private static ToolUseBlock block(String id, String name) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .content("{\"path\":\"/tmp/x\"}")
                .build();
    }

    /**
     * 每个用例前清空静态缓存，避免相互污染。
     */
    @BeforeEach
    void reset() {
        AgentScopeAgent.clearPendingBlocks();
    }

    @Test
    @DisplayName("取走待批块后缓存即释放")
    void takeEvictsEntry() {
        Map<String, ToolUseBlock> blocks = new LinkedHashMap<>();
        blocks.put("call-1", block("call-1", "read"));
        AgentScopeAgent.cachePendingBlocksForTest("reply-1", blocks);
        assertEquals(1, AgentScopeAgent.pendingBlocksSize());

        Map<String, ToolUseBlock> taken = AgentScopeAgent.takePendingBlocks("reply-1");

        assertNotNull(taken, "应能取到缓存内容");
        assertSame(blocks, taken);
        assertEquals(0, AgentScopeAgent.pendingBlocksSize(),
                "取走后必须逐出，否则每次确认都会永久占用内存");
    }

    @Test
    @DisplayName("同一 replyId 重复取用只成功一次")
    void takeIsOneShot() {
        Map<String, ToolUseBlock> blocks = new LinkedHashMap<>();
        blocks.put("call-1", block("call-1", "read"));
        AgentScopeAgent.cachePendingBlocksForTest("reply-2", blocks);

        assertNotNull(AgentScopeAgent.takePendingBlocks("reply-2"));
        assertNull(AgentScopeAgent.takePendingBlocks("reply-2"),
                "确认决策只回传一次，重复取用不应再命中");
    }

    @Test
    @DisplayName("未缓存的 replyId 安全返回 null")
    void takeMissingIsNull() {
        assertNull(AgentScopeAgent.takePendingBlocks("nope"));
        assertNull(AgentScopeAgent.takePendingBlocks(null));
    }

    @Test
    @DisplayName("大量写入后缓存条目数保持有界")
    void cacheIsBounded() {
        int writes = 1000;
        for (int i = 0; i < writes; i++) {
            Map<String, ToolUseBlock> blocks = new LinkedHashMap<>();
            blocks.put("call-" + i, block("call-" + i, "read"));
            AgentScopeAgent.cachePendingBlocksForTest("reply-" + i, blocks);
        }

        int size = AgentScopeAgent.pendingBlocksSize();
        assertTrue(size <= 256,
                "缓存必须有上限，实际条目数=" + size + "（写入 " + writes + " 次）");
    }
}
