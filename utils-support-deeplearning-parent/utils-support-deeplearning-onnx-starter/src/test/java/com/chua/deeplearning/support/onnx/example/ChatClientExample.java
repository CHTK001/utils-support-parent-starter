package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.chat.ChatClient;

/**
 * 文本对话能力 SPI 示例。
 *
 * <p>通过 {@link ChatClient#create(String, String)} 切换提供商（onnx/pytorch/llama/safetensors），
 * 通过 {@code .model(modelId)} 切换模型。</p>
 *
 * <pre>{@code
 *   ChatClientExample list                      # 列出各提供商模型
 *   ChatClientExample onnx minimind "你好"       # 对话
 *   ChatClientExample onnx bert-squad "什么是Java?"
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ChatClientExample extends ExampleBase {

    private ChatClientExample() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printModels("chat", "onnx", ChatClient.create("onnx", "").models());
            printModels("chat", "pytorch", ChatClient.create("pytorch", "").models());
            return;
        }
        String provider = args[0];
        String model = args.length > 1 ? args[1] : null;
        String prompt = args.length > 2 ? args[2] : "你好，请介绍一下自己。";

        ChatClient client = ChatClient.create(provider, "");
        if (model == null) {
            printModels("chat", provider, client.models());
            client.close();
            return;
        }
        client.model(model);
        long t0 = System.currentTimeMillis();
        String answer = client.chatSync(prompt);
        System.out.println("[chat] input: " + prompt);
        System.out.println("       output: " + answer);
        printResult("chat", provider, model, t0);
        client.close();
    }
}
