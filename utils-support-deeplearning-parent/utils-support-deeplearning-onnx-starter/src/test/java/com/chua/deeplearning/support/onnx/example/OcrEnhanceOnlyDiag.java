package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageEnhancer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文字高清修复对比：对三张"很不清楚"图，用 text-bsr 整图增强并落盘，
 * 与 G:\images 原图对照查看修复效果。
 *
 * @since 4.0.0.42
 */
public final class OcrEnhanceOnlyDiag {

    private OcrEnhanceOnlyDiag() {
    }

    public static void main(String[] args) throws Exception {
        ImageEnhancer enhancer = ImageEnhancer.create("text-bsr");
        String[] files = {
                "很不清楚的文字图片用于测试文字高清修复模型.png",
                "很不清楚的文字图片用于测试文字高清修复模型1.png",
                "很不清楚的文字图片用于测试文字高清修复模型2.png"
        };
        for (String name : files) {
            byte[] img = Files.readAllBytes(Path.of("G:\\images", name));
            byte[] enhanced = enhancer.enhance(img);
            String out = "G:\\images\\output\\enhanced_" + name.replace('.', '_') + ".png";
            Files.write(Path.of(out), enhanced);
            System.out.printf("原图=%s (%,dB) -> 修复=%s (%,dB)%n",
                    name, img.length, out, enhanced.length);
        }
    }
}