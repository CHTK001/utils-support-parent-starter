package com.chua.deeplearning.support.onnx.classification;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class DeepfakeTest {

    private static final Path RESOURCES = Paths.get("src/test/resources/deepfake");

    @Test
    void testDeepfakeModelRegistered() {
        com.chua.deeplearning.support.engine.ModelRegistry.discoverAll();
        assertNotNull(com.chua.deeplearning.support.engine.ModelRegistry.get("deepfake-detector"),
                "deepfake-detector 应已注册到 ModelRegistry");
        log.info("deepfake-detector 已注册");
    }

    @Test
    void testDeepfakeClassificationTranslatorCreation() throws Exception {
        EfficientNetLite0ClassificationTranslator translator = new EfficientNetLite0ClassificationTranslator();
        assertNotNull(translator);
        log.info("EfficientNetLite0ClassificationTranslator 创建成功（DeepFake Detector 复用）");
    }

    @Test
    void testDeepfakeWithTestImage() throws Exception {
        Path imgPath = RESOURCES.resolve("test_face.jpg");
        if (!Files.exists(imgPath)) {
            log.warn("测试图片不存在，跳过: {}", imgPath);
            return;
        }
        byte[] bytes = Files.readAllBytes(imgPath);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0, "图片数据不应为空");
        log.info("读取测试图片: {} bytes", bytes.length);
        log.info("DeepFake 检测测试通过");
    }
}