package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.embedding.EmbeddingClient;

/**
 * 文本嵌入能力 SPI 示例。
 *
 * <p>通过 {@link EmbeddingClient#create(String, String)} 切换提供商（onnx/minilm/bge/pytorch/llama），
 * 通过 {@code .model(modelId)} 切换模型。</p>
 *
 * <pre>{@code
 *   EmbeddingClientExample list
 *   EmbeddingClientExample onnx bge-small-zh "你好世界"
 *   EmbeddingClientExample minilm minilm "hello world"
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class EmbeddingClientExample extends ExampleBase {

    private EmbeddingClientExample() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printModels("embedding", "onnx", EmbeddingClient.create("onnx", "").models());
            printModels("embedding", "minilm", EmbeddingClient.create("minilm", "").models());
            printModels("embedding", "bge", EmbeddingClient.create("bge", "").models());
            return;
        }
        String provider = args[0];
        String model = args.length > 1 ? args[1] : null;
        String text = args.length > 2 ? args[2] : "你好世界";

        EmbeddingClient client = EmbeddingClient.create(provider, "");
        if (model == null) {
            printModels("embedding", provider, client.models());
            client.close();
            return;
        }
        client.model(model);
        long t0 = System.currentTimeMillis();
        float[] vec = client.embedding(text);
        System.out.println("[embedding] text: " + text);
        System.out.println("       dim: " + vec.length);
        System.out.println("       head: " + java.util.Arrays.toString(java.util.Arrays.copyOf(vec, 5)));
        printResult("embedding", provider, model, t0);
        client.close();
    }
}
