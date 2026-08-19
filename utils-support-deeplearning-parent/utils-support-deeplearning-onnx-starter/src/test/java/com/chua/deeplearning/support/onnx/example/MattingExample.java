package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.MattingService;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图像抠图（matting）能力示例。
 *
 * <p>通过 {@link MattingService#create(String)} 使用 DJL onnxruntime-engine 抠图模型（modnet），
 * OpenCV 预处理，输出透明背景 PNG。</p>
 *
 * <pre>{@code
 *   MattingExample modnet person.jpg out.png
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class MattingExample extends ExampleBase {

    /** 创建 MattingExample 实例 */
    private MattingExample() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : "modnet";
        String imagePath = args.length > 1 ? args[1] : null;
        String outPath = args.length > 2 ? args[2] : null;
        if (imagePath == null) {
            System.out.println("[matting] 需要图片路径");
            return;
        }
        byte[] img = Files.readAllBytes(Path.of(imagePath));
        MattingService service = MattingService.create(model);
        long t0 = System.currentTimeMillis();
        byte[] result = service.matte(img);
        System.out.println("[matting] model: " + model + " 图片: " + imagePath);
        System.out.println("       输出 PNG: " + result.length + " bytes");
        if (outPath != null) {
            Files.write(Path.of(outPath), result);
            System.out.println("       已保存: " + outPath);
        }
        printResult("matting", "onnx", model, t0);
    }
}
