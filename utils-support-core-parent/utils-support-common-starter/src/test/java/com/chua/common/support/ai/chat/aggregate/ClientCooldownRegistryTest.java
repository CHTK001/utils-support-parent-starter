package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.aggregate.strategy.FailoverStrategy;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端冷却与自动恢复用例。
 *
 * <p>验证四件事：失败的 Key 会被排除一段时间；成功的 Key 立即恢复；
 * 全部 Key 都在冷却时降级为半开放行而不是让整体不可用；请求级错误
 * （400/参数非法）不误伤健康 Key。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
class ClientCooldownRegistryTest {

    /**
     * 每个用例开始前清空登记表，避免跨用例串味
     */
    @AfterEach
    void tearDown() {
        ClientCooldownRegistry.reset();
    }

    /**
     * 失败一次后进入冷却，冷却期内不会出现在可用列表
     */
    @Test
    void failureExcludesClientFromAvailable() {
        RouterStrategy.WeightedClient bad = wrap("k1", null, true);
        RouterStrategy.WeightedClient good = wrap("k2", null, false);

        ClientCooldownRegistry.recordFailure(bad, new RuntimeException("HTTP 429 Too Many Requests"));

        assertTrue(ClientCooldownRegistry.isCoolingDown(bad));
        assertTrue(ClientCooldownRegistry.remainingMillis(bad) > 0);
        List<RouterStrategy.WeightedClient> available =
                ClientCooldownRegistry.available(List.of(bad, good));
        assertEquals(1, available.size());
        assertEquals(good, available.getFirst());
    }

    /**
     * 唯一候选在冷却时降级为半开放行，避免整体不可用
     */
    @Test
    void allCoolingOpensHalfOpen() {
        RouterStrategy.WeightedClient a = wrap("k1", null, true);
        RouterStrategy.WeightedClient b = wrap("k2", null, true);
        ClientCooldownRegistry.recordFailure(a, new RuntimeException("429"));
        ClientCooldownRegistry.recordFailure(b, new RuntimeException("429"));

        assertEquals(2, ClientCooldownRegistry.available(List.of(a, b)).size());
        assertEquals(1, ClientCooldownRegistry.available(List.of(a)).size());
    }

    /**
     * 成功一次立即解除冷却，无须等计时到期
     */
    @Test
    void successClearsCooldownImmediately() {
        RouterStrategy.WeightedClient client = wrap("k1", null, false);
        ClientCooldownRegistry.recordFailure(client, new RuntimeException("429"));
        assertTrue(ClientCooldownRegistry.isCoolingDown(client));

        ClientCooldownRegistry.recordSuccess(client);

        assertFalse(ClientCooldownRegistry.isCoolingDown(client));
        assertEquals(0L, ClientCooldownRegistry.remainingMillis(client));
        assertEquals(1, ClientCooldownRegistry.available(List.of(client)).size());
    }

    /**
     * 冷却到期后自动恢复，无需任何外部触发
     */
    @Test
    void cooldownExpiresAndRecovers() throws Exception {
        ClientCooldownRegistry.configure(60L, 240L);
        RouterStrategy.WeightedClient client = wrap("k1", null, false);
        ClientCooldownRegistry.recordFailure(client, new RuntimeException("429"));
        assertTrue(ClientCooldownRegistry.isCoolingDown(client));

        Thread.sleep(120L);

        assertFalse(ClientCooldownRegistry.isCoolingDown(client));
        assertEquals(1, ClientCooldownRegistry.available(List.of(client)).size());
    }

    /**
     * 连续失败按 2 的幂次翻倍并封顶
     */
    @Test
    void consecutiveFailuresBackOffAndCap() {
        ClientCooldownRegistry.configure(1000L, 3000L);
        RouterStrategy.WeightedClient client = wrap("k1", null, false);

        ClientCooldownRegistry.recordFailure(client, new RuntimeException("429"));
        long first = ClientCooldownRegistry.remainingMillis(client);
        ClientCooldownRegistry.recordFailure(client, new RuntimeException("429"));
        long second = ClientCooldownRegistry.remainingMillis(client);
        ClientCooldownRegistry.recordFailure(client, new RuntimeException("429"));
        long third = ClientCooldownRegistry.remainingMillis(client);

        assertTrue(second > first, "第二次失败应比第一次冷却更久: " + first + " -> " + second);
        assertTrue(third <= 3000L, "冷却时长不得超过配置上限: " + third);
        assertTrue(third >= second, "第三次不应短于第二次: " + second + " -> " + third);
    }

    /**
     * 请求级错误（400/参数非法）不进入冷却：换 Key 也救不回来，排除只是误伤
     */
    @Test
    void requestLevelErrorDoesNotCoolDown() {
        RouterStrategy.WeightedClient client = wrap("k1", null, false);
        ClientCooldownRegistry.recordFailure(client,
                new RuntimeException("400 Bad Request: invalid parameter 'temperature'"));

        assertFalse(ClientCooldownRegistry.isCoolingDown(client));
        assertEquals(0, ClientCooldownRegistry.size());
    }

    /**
     * 冷却键按 API Key 而非实例计算，客户端重建后仍命中同一条记录
     */
    @Test
    void cooldownKeyedByApiKeyNotInstance() {
        RouterStrategy.WeightedClient first = wrap("shared-key", null, false);
        ClientCooldownRegistry.recordFailure(first, new RuntimeException("429"));

        RouterStrategy.WeightedClient rebuilt = wrap("shared-key", null, false);
        assertTrue(ClientCooldownRegistry.isCoolingDown(rebuilt),
                "重建客户端后应按 API Key 命中同一冷却记录");

        RouterStrategy.WeightedClient other = wrap("another-key", null, false);
        assertFalse(ClientCooldownRegistry.isCoolingDown(other));
    }

    /**
     * FailoverTemplate 在重试循环中跳过冷却客户端，成功后解除冷却
     */
    @Test
    void failoverTemplateSkipsCoolingClient() throws Exception {
        AtomicInteger badCalls = new AtomicInteger();
        AtomicInteger goodCalls = new AtomicInteger();
        RouterStrategy.WeightedClient bad = wrap("bad-key", badCalls, true);
        RouterStrategy.WeightedClient good = wrap("good-key", goodCalls, false);

        String first = FailoverTemplate.executeSync(new FailoverStrategy(), List.of(bad, good), "hi", null);
        assertEquals("SUCCESS", first);
        assertEquals(1, badCalls.get());
        assertTrue(ClientCooldownRegistry.isCoolingDown(bad));

        // 第二次应直接命中 good，不再触碰冷却中的 bad
        String second = FailoverTemplate.executeSync(new FailoverStrategy(), List.of(bad, good), "hi", null);
        assertEquals("SUCCESS", second);
        assertEquals(1, badCalls.get(), "冷却中的客户端不应被再次调用");
        assertEquals(2, goodCalls.get());
    }

    /**
     * 全部客户端失败时抛出，并全部进入冷却
     */
    @Test
    void failoverTemplateCoolsDownAllFailedClients() {
        RouterStrategy.WeightedClient a = wrap("a-key", null, true);
        RouterStrategy.WeightedClient b = wrap("b-key", null, true);

        assertThrows(RuntimeException.class,
                () -> FailoverTemplate.executeSync(new FailoverStrategy(), List.of(a, b), "hi", null));

        assertTrue(ClientCooldownRegistry.isCoolingDown(a));
        assertTrue(ClientCooldownRegistry.isCoolingDown(b));
    }

    /**
     * 构造候选客户端
     *
     * @param apiKey API Key，用于冷却键
     * @param calls  调用计数，可为 null
     * @param fail   是否总是抛 429
     * @return 候选客户端
     */
    private static RouterStrategy.WeightedClient wrap(String apiKey, AtomicInteger calls, boolean fail) {
        return new RouterStrategy.WeightedClient("p", "m", 1, stub(apiKey, calls, fail));
    }

    /**
     * 构造可计数、可失败的底层客户端
     *
     * @param apiKey API Key
     * @param calls  调用计数，可为 null
     * @param fail   是否总是抛 429
     * @return 底层客户端
     */
    private static ChatClient stub(String apiKey, AtomicInteger calls, boolean fail) {
        return new ChatClient() {
            /**
             * 固定返回的 API Key，用于冷却键计算
             * @return API Key
             */
            @Override
            public String getApiKey() {
                return apiKey;
            }

            /**
             * 计数并同步对话
             * @param prompt 用户输入
             * @return 响应文本
             */
            @Override
            public String chatSync(String prompt) {
                if (calls != null) {
                    calls.incrementAndGet();
                }
                if (fail) {
                    throw new RuntimeException("HTTP 429 Too Many Requests");
                }
                return "SUCCESS";
            }

            /**
             * 带用量的同步对话
             * @param prompt 用户输入
             * @return 同步响应
             */
            @Override
            public ChatSyncResponse chatSyncWithResponse(String prompt) {
                return ChatSyncResponse.builder().text(chatSync(prompt)).build();
            }
        };
    }
}
