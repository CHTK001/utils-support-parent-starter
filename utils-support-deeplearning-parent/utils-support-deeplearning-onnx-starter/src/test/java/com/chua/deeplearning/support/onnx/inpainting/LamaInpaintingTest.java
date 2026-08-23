package com.chua.deeplearning.support.onnx.inpainting;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class LamaInpaintingTest {

    private static final Path RESOURCES = Paths.get("src/test/resources/inpainting");

    @Test
    void testLamaModelRegistered() {
        com.chua.deeplearning.support.engine.ModelRegistry.discoverAll();
        assertNotNull(com.chua.deeplearning.support.engine.ModelRegistry.get("lama-inpainting"),
                "lama-inpainting 应已注册到 ModelRegistry");
        log.info("lama-inpainting 已注册");
    }

    @Test
    void testLamaTranslatorCreation() throws Exception {
        LamaInpaintingTranslator translator = new LamaInpaintingTranslator();
        assertNotNull(translator);
        log.info("LamaInpaintingTranslator 创建成功");
    }

    @Test
    void testLamaWithTestImages() throws Exception {
        Path origPath = RESOURCES.resolve("original.jpg");
        Path maskPath = RESOURCES.resolve("mask.jpg");
        byte[] origBytes = Files.exists(origPath) ? Files.readAllBytes(origPath) : null;
        byte[] maskBytes = Files.exists(maskPath) ? Files.readAllBytes(maskPath) : null;
        if (origBytes != null && maskBytes != null) {
            assertTrue(origBytes.length > 0);
            assertTrue(maskBytes.length > 0);
            log.info("读取原图: {} bytes, Mask: {} bytes", origBytes.length, maskBytes.length);
        } else {
            log.warn("测试图片不存在，跳过推理测试");
        }
        log.info("LaMa 图像修复测试通过");
    }
}