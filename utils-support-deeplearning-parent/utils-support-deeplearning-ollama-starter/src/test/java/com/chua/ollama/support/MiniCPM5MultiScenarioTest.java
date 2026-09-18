package com.chua.ollama.support;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatSyncResponse;

/**
 * MiniCPM5-2B 多场景对话验证测试（非 JUnit，直接 main 运行）。
 *
 * <p>覆盖自我介绍、数学推理、代码生成、多轮记忆、长文摘要 5 个场景，
 * 校验 {@link OllamaChatClient} 在 Q4_K_M 量化下的实际表现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MiniCPM5MultiScenarioTest {

    /** MiniCPM5-2B 量化模型 名称 */
    private static final String MODEL = "hf.co/openbmb/MiniCPM5-2B-GGUF:Q4_K_M";

    /**
     * 执行多场景 验证。
     *
     * @param args 运行 参数（未 使用）
     */
    public static void main(String[] args) {
        ChatClient client = ChatClient.create("ollama", "");
        int passed = 0;
        int total = 5;

        // 场景 1: 自我介绍
        passed += check(client, "请 用 一 句话 介绍 你 自己。", "MiniCPM");

        // 场景 2: 数学 推理
        passed += check(client, "17 * 23 = ?  只 回答 数字。", "391");

        // 场景 3: 代码 生成
        passed += check(client, "用 Java 写 一 个 方法，判断 一 个 整数 是否 为 回文 数，只 给 方法 体。", "int");

        // 场景 4: 中文 多轮（历史 记忆）
        ChatClient multi = ChatClient.create("ollama", "");
        multi.model(MODEL).addUserHistory("我 叫 张 伟，喜欢 爬山。");
        String multiResp = multi.chatSync("你 还 记得 我 的 名字 吗？只 回答 名字。");
        boolean multiOk = multiResp != null && multiResp.replace(" ", "").contains("张 伟");
        System.out.printf("[多轮 记忆] %s -> %s%n", multiOk ? "PASS" : "FAIL", multiResp);
        multi.close();
        passed += multiOk ? 1 : 0;

        // 场景 5: 长文 摘要
        passed += check(client,
                "把 下面 这 段 话 压缩 成 一 句话：大 语言 模型 通过 自 监督 学习 在 海量 文本 上 预 训练，随后 通过 有 监督 微调 与 人类 偏好 对齐，使 其 能够 理解 并 生成 自然 语言，在 问答、代码、推理 等 任务 上 表现 优异。",
                "语言 模型");

        System.out.printf("%n结果: %d/%d 场景 通过%n", passed, total);
        client.close();
        System.exit(passed == total ? 0 : 1);
    }

    /**
     * 执行 单 个 场景 并 打印 结果 与 速度。
     *
     * @param client 客户端
     * @param prompt 提示词
     * @param expect 期望 包含 的 关键词（null 表示 仅 校验 非 空）
     * @return 通过 返回 1，否则 0
     */
    private static int check(ChatClient client, String prompt, String expect) {
        long t0 = System.currentTimeMillis();
        ChatSyncResponse resp = client.model(MODEL)
                .temperature(1.0)
                .topP(0.95)
                .maxTokens(256)
                .chatSyncWithResponse(prompt);
        long ms = System.currentTimeMillis() - t0;
        String text = resp != null ? resp.getText() : null;
        boolean ok = text != null && !text.isBlank()
                && (expect == null || text.replace(" ", "").contains(expect.replace(" ", "")));
        double tps = (resp != null && resp.getUsage() != null
                && resp.getUsage().getOutputTokens() != null && ms > 0)
                ? resp.getUsage().getOutputTokens() * 1000.0 / ms : -1;
        System.out.printf("[%s] %dms (%.1f tok/s) prompt=%s%n",
                ok ? "PASS" : "FAIL", ms, tps, prompt);
        System.out.printf("  期望 包含[%s] -> %s%n", expect, ok);
        System.out.printf("  实际: %s%n", text);
        return ok ? 1 : 0;
    }
}
