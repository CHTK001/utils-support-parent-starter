package com.chua.deeplearning.support.onnx.generation;

import java.awt.image.BufferedImage;

public class SmallSdGpuTest {
    public static void main(String[] args) throws Exception {
        String prompt = "a corgi wearing sunglasses on the beach";
        int steps = 20;
        for (int i = 0; i < args.length; i++) {
            if ("--prompt".equals(args[i]) && i + 1 < args.length) prompt = args[++i];
            else if ("--steps".equals(args[i]) && i + 1 < args.length) steps = Integer.parseInt(args[++i]);
            else if (!args[i].startsWith("--")) prompt = args[i];
        }
        System.setProperty("small.sd.steps", String.valueOf(steps));
        System.setProperty("deeplearning.device", "gpu");

        SmallStableDiffusionCombinedTranslator t = new SmallStableDiffusionCombinedTranslator();
        System.out.println("[TEST] 开始推理 (GPU 模式)...");
        long t0 = System.currentTimeMillis();
        BufferedImage img = (BufferedImage) t.translate(prompt);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[TEST] 完成! 耗时: " + elapsed + "ms, 尺寸: " + img.getWidth() + "x" + img.getHeight());
        javax.imageio.ImageIO.write(img, "png", new java.io.File("D:\\ch\\project\\gpu-test-out.png"));
        System.out.println("[TEST] 已保存: D:\\ch\\project\\gpu-test-out.png");
    }
}
