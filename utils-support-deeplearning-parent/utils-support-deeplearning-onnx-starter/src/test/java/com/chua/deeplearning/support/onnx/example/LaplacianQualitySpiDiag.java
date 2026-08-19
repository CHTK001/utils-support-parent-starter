package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.model.ImageQualityInfo;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证 LaplacianImageQualityAssessor 可通过 SPI(provider=laplacian) 创建并评估。
 *
 * @since 4.0.0.42
 */
public final class LaplacianQualitySpiDiag {

    private LaplacianQualitySpiDiag() {
    }

    public static void main(String[] args) throws Exception {
        ImageQualityAssessor assessor = ImageQualityAssessor.create("laplacian", (String) null);
        System.out.println("SPI 创建成功: " + assessor.getClass().getSimpleName());

        String[] files = {
                "很不清楚的文字图片用于测试文字高清修复模型1.png",
                "很不清楚的文字图片用于测试文字高清修复模型2.png"
        };
        for (String name : files) {
            byte[] img = Files.readAllBytes(Path.of("G:\\images", name));
            ImageQualityInfo info = assessor.assess(img);
            System.out.printf("%-20s blur=%.1f bright=%.1f contrast=%.1f sharpOk=%s brightOk=%s score=%.2f %s%n",
                    name, info.blurScore(), info.brightness(), info.contrast(),
                    info.sharpnessOk(), info.brightnessOk(), info.overallScore(), info.message());
        }
    }
}
