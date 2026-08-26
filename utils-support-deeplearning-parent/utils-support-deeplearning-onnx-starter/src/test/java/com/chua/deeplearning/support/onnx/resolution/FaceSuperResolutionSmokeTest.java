package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GFPGAN / CodeFormer 人脸修复模型端到端冒烟测试。
 *
 * <p>验证 ONNX 模型在 CPU 环境下的推理能力，输出图片落盘供人工复核。</p>
 */
class FaceSuperResolutionSmokeTest {

    private static final String TEST_IMAGE = "Z:/temp/opencode/nudedetector/testimg/lena.jpg";
    private static final String OUT_DIR = "Z:/temp/opencode/nudedetector/testimg";

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("com.chua.deeplearning.support.onnx.OnnxModelRegistrar");
    }

    @Test
    @DisplayName("GFPGAN v1.3_clean 端到端推理并落盘")
    void testGfpgan() throws Exception {
        if (!Files.exists(Path.of(TEST_IMAGE))) {
            Assertions.fail("测试图像不存在: " + TEST_IMAGE);
        }

        long start = System.currentTimeMillis();
        AbstractIdentificationEngine engine = AbstractIdentificationEngine.getInstance();
        ITranslator<Image, Image> translator = engine.get("onnx-gfpgan", ITranslator.class);

        Image input = ImageFactory.getInstance().fromFile(new File(TEST_IMAGE));
        Image output = translator.translate(input);

        long cost = System.currentTimeMillis() - start;
        Assertions.assertNotNull(output, "GFPGAN 输出不应为 null");

        Path outPath = Path.of(OUT_DIR, "gfpgan_v13_java_test.png");
        BufferedImage bi = output.toBufferedImage();
        ImageIO.write(bi, "png", outPath.toFile());
        System.out.printf("[GFPGAN] 完成: %dx%d, 耗时 %dms, 输出 %s%n",
                bi.getWidth(), bi.getHeight(), cost, outPath.toAbsolutePath());
    }

    @Test
    @DisplayName("CodeFormer 端到端推理并落盘")
    void testCodeFormer() throws Exception {
        if (!Files.exists(Path.of(TEST_IMAGE))) {
            Assertions.fail("测试图像不存在: " + TEST_IMAGE);
        }

        long start = System.currentTimeMillis();
        AbstractIdentificationEngine engine = AbstractIdentificationEngine.getInstance();
        ITranslator<Image, Image> translator = engine.get("codeformer", ITranslator.class);

        Image input = ImageFactory.getInstance().fromFile(new File(TEST_IMAGE));
        Image output = translator.translate(input);

        long cost = System.currentTimeMillis() - start;
        Assertions.assertNotNull(output, "CodeFormer 输出不应为 null");

        Path outPath = Path.of(OUT_DIR, "codeformer_java_test.png");
        BufferedImage bi = output.toBufferedImage();
        ImageIO.write(bi, "png", outPath.toFile());
        System.out.printf("[CodeFormer] 完成: %dx%d, 耗时 %dms, 输出 %s%n",
                bi.getWidth(), bi.getHeight(), cost, outPath.toAbsolutePath());
    }
}
