package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.feature.FeatureClient;

/**
 * 特征提取能力 SPI 示例。
 *
 * <p>通过 {@link FeatureClient#create(String, String)} 切换提供商（onnx/pytorch/llama/safetensors），
 * 通过 {@code .model(modelId)} 切换模型。支持文本与图像两种输入。</p>
 *
 * <pre>{@code
 *   FeatureClientExample list
 *   FeatureClientExample onnx clip-text-feature "a cat"
 *   FeatureClientExample onnx clip-image-feature <图片路径>
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class FeatureClientExample extends ExampleBase {

    private FeatureClientExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printModels("feature", "onnx", FeatureClient.create("onnx", "").models());
            printModels("feature", "pytorch", FeatureClient.create("pytorch", "").models());
            return;
        }
        String provider = args[0];
        String model = args.length > 1 ? args[1] : null;
        String input = args.length > 2 ? args[2] : "a cat";

        FeatureClient client = FeatureClient.create(provider, "");
        if (model == null) {
            printModels("feature", provider, client.models());
            client.close();
            return;
        }
        client.model(model);
        long t0 = System.currentTimeMillis();
        float[] vec;
        if (input.endsWith(".png") || input.endsWith(".jpg") || input.endsWith(".jpeg")) {
            byte[] img = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(input));
            vec = client.extractImage(img);
        } else {
            vec = client.extract(input);
        }
        System.out.println("[feature] input: " + input);
        System.out.println("       dim: " + vec.length);
        printResult("feature", provider, model, t0);
        client.close();
    }
}
