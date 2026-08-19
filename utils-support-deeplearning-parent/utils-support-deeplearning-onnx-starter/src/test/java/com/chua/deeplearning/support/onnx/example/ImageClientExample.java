package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.image.ImageClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * 文生图能力 SPI 示例。
 *
 * <p>通过 {@link ImageClient#create(String, String)} 切换提供商（onnx/pytorch），
 * 通过 {@code .model(modelId)} 切换模型。</p>
 *
 * <pre>{@code
 *   ImageClientExample list
 *   ImageClientExample onnx small-stable-diffusion-combined "一只柴犬在樱花树下" out.png
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class ImageClientExample extends ExampleBase {

    private ImageClientExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printModels("image", "onnx", ImageClient.create("onnx", "").models());
            printModels("image", "pytorch", ImageClient.create("pytorch", "").models());
            return;
        }
        String provider = args[0];
        String model = args.length > 1 ? args[1] : null;
        String prompt = args.length > 2 ? args[2] : "一只柴犬在樱花树下";
        String outPath = args.length > 3 ? args[3] : null;

        ImageClient client = ImageClient.create(provider, "");
        if (model == null) {
            printModels("image", provider, client.models());
            client.close();
            return;
        }
        client.model(model);
        long t0 = System.currentTimeMillis();
        BufferedImage image = client.generate(prompt);
        System.out.println("[image] prompt: " + prompt);
        System.out.println("       size: " + image.getWidth() + "x" + image.getHeight());
        if (outPath != null) {
            ImageIO.write(image, "png", Path.of(outPath).toFile());
            System.out.println("       saved: " + outPath);
        }
        printResult("image", provider, model, t0);
        client.close();
    }
}
