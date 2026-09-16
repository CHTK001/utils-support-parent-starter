package com.chua.trae.support;

import com.chua.trae.support.model.ChatRequest;
import com.chua.trae.support.model.ChatResponse;
import com.chua.trae.support.model.ModelConfig;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
* 真实 IO 运行器，调用 Trae 后端打印原始请求体与 SSE 事件流。
*
* @author CH
* @since 4.0.0.42
 */
public class RealIoRunner {

    /**
    * 主入口，执行真实聊天请求并打印输入输出。
    *
    * @param args 运行参数
    * @throws Exception 当认证失败或网络异常时
     */
    public static void main(String[] args) throws Exception {
        String model = "glm-5.2";
        for (String arg : args) {
            if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
            }
        }

        Optional<ModelConfig> configOpt = ModelConfigLoader.loadDefault();
        ModelConfig config = configOpt.orElse(null);

        ChatClient client = ChatClient.builder()
            .edition("cn")
            .dataDir(System.getProperty("user.home") + "\\AppData\\Roaming\\Trae CN")
            .modelConfig(config)
            .defaultModel(model)
            .maxRetries(3)
            .build();

        try {
            ChatRequest request = ChatRequest.builder()
                .model(model)
                .messages(List.of(new ChatRequest.UserMessage("用一句话介绍 Java")))
                .temperature(0.7)
                .maxTokens(1024)
                .build();

            System.out.println("========================================");
            System.out.println(" [INPUT] 请求参数");
            System.out.println("========================================");
            System.out.println("model=" + model + " temperature=0.7 max_tokens=1024");
            System.out.println("messages: [{role: user, content: 用一句话介绍 Java}]");
            System.out.println();

            System.out.println("========================================");
            System.out.println(" [OUTPUT] SSE 流式事件（实时打印）");
            System.out.println("========================================");

            CountDownLatch latch = new CountDownLatch(1);
            StringBuilder fullText = new StringBuilder();
            client.chatStream(request,
                text -> {
                    System.out.print(text);
                    fullText.append(text);
                },
                reasoning -> System.out.println("\n[reasoning] " + reasoning),
                resp -> {
                    System.out.println("\n[done] finish=" + resp.choices().getFirst().finishReason());
                    if (resp.usage() != null) {
                        System.out.println("[usage] prompt=" + resp.usage().promptTokens()
                            + " completion=" + resp.usage().completionTokens());
                    }
                    latch.countDown();
                },
                err -> {
                    System.err.println("\n[error] " + err.getMessage() + " (http=" + err.statusCode() + ")");
                    latch.countDown();
                });

            boolean finished = latch.await(120, TimeUnit.SECONDS);
            if (!finished) {
                System.err.println("[timeout] 120s 未完成");
            }

            System.out.println("========================================");
            System.out.println(" [OUTPUT] 完整文本");
            System.out.println("========================================");
            System.out.println(fullText.toString());
        } finally {
            client.close();
        }
    }
}
