package com.chua.deeplearning.support.llama;

import com.chua.common.support.ai.chat.ChatClient;

public final class Qwen2ChatVerify {

    private Qwen2ChatVerify() {
    }

    public static void main(String[] args) throws Exception {
        ChatClient client = ChatClient.create("llama", "")
                .model("qwen2-1.5b");
        System.out.println("[Qwen2] 开始对话测试...");
        long t0 = System.currentTimeMillis();
        String reply = client.chatSync("你好，请用一句话介绍你自己。");
        long cost = System.currentTimeMillis() - t0;
        System.out.println("[Qwen2] 回复: " + reply);
        System.out.println("[Qwen2] 耗时: " + cost + "ms");
        if (reply != null && !reply.isEmpty()) {
            System.out.println("[Qwen2ChatVerify] ALL PASS");
        } else {
            System.out.println("[Qwen2ChatVerify] FAIL");
            System.exit(1);
        }
        client.close();
    }
}