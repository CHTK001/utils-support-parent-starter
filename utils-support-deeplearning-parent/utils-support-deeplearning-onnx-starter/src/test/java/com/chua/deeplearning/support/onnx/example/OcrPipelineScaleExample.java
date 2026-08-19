package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrPipeline;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证 OcrPipeline.enhancer("text-bsr:4") 的 scale 注入。
 *
 * <pre>{@code
 *   OcrPipelineScaleExample
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class OcrPipelineScaleExample {

    private OcrPipelineScaleExample() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\blurry_text.png";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        OcrPipeline p4 = OcrPipeline.builder().enhancer("text-bsr:4").build();
        OcrPipeline p2 = OcrPipeline.builder().enhancer("text-bsr").build();

        long t0 = System.currentTimeMillis();
        byte[] out4 = p4.enhance(img);
        long e4 = System.currentTimeMillis() - t0;
        java.awt.image.BufferedImage bi4 = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(out4));
        System.out.println("[scale-test] text-bsr:4 -> " + bi4.getWidth() + "x" + bi4.getHeight() + " 耗时=" + e4 + "ms");

        t0 = System.currentTimeMillis();
        byte[] out2 = p2.enhance(img);
        long e2 = System.currentTimeMillis() - t0;
        java.awt.image.BufferedImage bi2 = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(out2));
        System.out.println("[scale-test] text-bsr   -> " + bi2.getWidth() + "x" + bi2.getHeight() + " 耗时=" + e2 + "ms");
    }
}
