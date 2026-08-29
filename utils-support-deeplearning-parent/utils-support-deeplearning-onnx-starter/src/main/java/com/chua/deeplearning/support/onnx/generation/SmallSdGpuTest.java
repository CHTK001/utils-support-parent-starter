package com.chua.deeplearning.support.onnx.generation;

import java.awt.image.BufferedImage;

public class SmallSdGpuTest {
    public static void main(String[] args) throws Exception {
        String prompt = args.length > 0 ? args[0] : "a corgi wearing sunglasses on the beach";
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        System.setProperty("small.sd.steps", String.valueOf(steps));
        System.setProperty("deeplearning.device", "gpu");

        SmallStableDiffusionCombinedTranslator t = new SmallStableDiffusionCombinedTranslator();
        System.out.println("[TEST] 开始推理 (GPU 模式)...");
        long t0 = System.currentTimeMillis();
        BufferedImage img = t.translate(prompt);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[TEST] 完成! 耗时: " + elapsed + "ms, 尺寸: " + img.getWidth() + "x" + img.getHeight());
        javax.imageio.ImageIO.write(img, "png", new java.io.File("D:\\ch\\project\\gpu-test-out.png"));
        System.out.println("[TEST] 已保存: D:\\ch\\project\\gpu-test-out.png");
    }
}
