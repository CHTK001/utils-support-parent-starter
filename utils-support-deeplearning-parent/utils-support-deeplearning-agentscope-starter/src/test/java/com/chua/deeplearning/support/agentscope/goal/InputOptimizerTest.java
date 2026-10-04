package com.chua.deeplearning.support.agentscope.goal;

import com.chua.common.support.ai.chat.ChatClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputOptimizer} 的改写、回退与提示词组装测试。
 *
 * @author CH
 */
class InputOptimizerTest {

    /**
     * 可编程的假 ChatClient：只实现 {@link ChatClient#chatSync(String)} 与两个无害的取值方法。
     *
     * <p>base url 指向回环地址，避免任何实现意外走到真实外网
     * （仓库有过单元测试打到 api.openai.com 的事故）。
     */
    static final class FakeChatClient implements ChatClient {

        final AtomicInteger calls = new AtomicInteger();

        String reply = "优化后的指令";

        RuntimeException failure;

        String lastPrompt;

        @Override
        public String chatSync(String prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            if (failure != null) {
                throw failure;
            }
            return reply;
        }

        @Override
        public String getBaseUrl() {
            return "http://127.0.0.1:1/v1";
        }

        @Override
        public String getApiKey() {
            return "test-key";
        }
    }

    /**
     * 构造一个目标。
     *
     * @return 目标
     */
    private static SessionGoal goal() {
        return SessionGoal.create("g1", "s1", "修复登录 403", "CORS 白名单要配好", 1000L);
    }

    @Test
    void returnsModelOutput() {
        FakeChatClient client = new FakeChatClient();
        client.reply = "请修复 /v2/user/login 的 CORS 403，并补一条回归用例";
        InputOptimizer optimizer = new InputOptimizer(client);

        String result = optimizer.optimize("登录坏了", goal(), List.of("用户: 页面 403"));
        assertEquals("请修复 /v2/user/login 的 CORS 403，并补一条回归用例", result);
        assertEquals(1, client.calls.get());
    }

    @Test
    void blankInputSkipsModelCall() {
        FakeChatClient client = new FakeChatClient();
        InputOptimizer optimizer = new InputOptimizer(client);

        assertEquals("   ", optimizer.optimize("   ", goal(), List.of()));
        assertEquals(0, client.calls.get(), "空白输入不应发起模型调用");
    }

    @Test
    void nullInputReturnsNull() {
        InputOptimizer optimizer = new InputOptimizer(new FakeChatClient());
        assertNull(optimizer.optimize(null, goal(), List.of()));
    }

    @Test
    void blankModelOutputFallsBackToRawInput() {
        FakeChatClient client = new FakeChatClient();
        client.reply = "   ";
        InputOptimizer optimizer = new InputOptimizer(client);

        assertEquals("登录坏了", optimizer.optimize("登录坏了", goal(), List.of()));
    }

    @Test
    void modelFailureFallsBackToRawInput() {
        FakeChatClient client = new FakeChatClient();
        client.failure = new IllegalStateException("上游不可达");
        InputOptimizer optimizer = new InputOptimizer(client);

        assertEquals("登录坏了", optimizer.optimize("登录坏了", goal(), List.of()));
        assertEquals(1, client.calls.get());
    }

    @Test
    void promptCarriesGoalContextAndRawInput() {
        String prompt = InputOptimizer.buildPrompt("登录坏了", goal(), List.of("用户: 页面 403", "助手: 已定位"));
        assertTrue(prompt.contains("修复登录 403"), prompt);
        assertTrue(prompt.contains("CORS 白名单要配好"), prompt);
        assertTrue(prompt.contains("用户: 页面 403"), prompt);
        assertTrue(prompt.contains("助手: 已定位"), prompt);
        assertTrue(prompt.contains("登录坏了"), prompt);
        assertTrue(prompt.contains("只输出改写后的指令本身"), prompt);
    }

    @Test
    void promptWorksWithoutGoal() {
        String prompt = InputOptimizer.buildPrompt("随便写点", null, null);
        assertTrue(prompt.contains("随便写点"), prompt);
        assertTrue(!prompt.contains("【当前目标】"), "无目标时不应出现目标段");
    }

    @Test
    void longContextIsTrimmedFromOldest() {
        List<String> context = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            context.add("第 " + i + " 条上下文 " + "x".repeat(50));
        }
        String prompt = InputOptimizer.buildPrompt("收尾", goal(), context);
        assertTrue(prompt.contains("第 199 条上下文"), "最新的上下文必须保留");
        assertTrue(!prompt.contains("第 0 条上下文"), "最旧的上下文应被裁掉");
    }
}
