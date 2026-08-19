package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageCaptioning;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图像描述能力示例。
 *
 * <p>通过 {@link ImageCaptioning#create(String)} 切换模型（vit-gpt2-captioning）。</p>
 *
 * <pre>{@code
 *   ImageCaptioningExample vit-gpt2-captioning photo.jpg
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class ImageCaptioningExample extends ExampleBase {

    /** 创建 ImageCaptioningExample 实例 */
    private ImageCaptioningExample() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: ImageCaptioningExample <图片路径> [模型名]");
            System.out.println("示例: ImageCaptioningExample photo.jpg");
            return;
        }
        String imagePath = args[0];
        String model = args.length > 1 ? args[1] : "vit-gpt2-captioning";
        if (imagePath == null) {
            System.out.println("[caption] 需要图片路径");
            return;
        }
        byte[] img = Files.readAllBytes(Path.of(imagePath));
        ImageCaptioning captioning = ImageCaptioning.create(model);
        long t0 = System.currentTimeMillis();
        String caption = captioning.describe(img);
        System.out.println("[caption] model: " + model + " 图片: " + imagePath);
        System.out.println("       caption: " + caption);
        printResult("caption", "onnx", model, t0);
    }
}
