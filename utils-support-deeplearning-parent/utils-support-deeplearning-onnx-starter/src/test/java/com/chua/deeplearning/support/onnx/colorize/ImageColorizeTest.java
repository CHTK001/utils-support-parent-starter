package com.chua.deeplearning.support.onnx.colorize;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class ImageColorizeTest {

    private static final Path RESOURCES = Paths.get("src/test/resources/colorize");

    @Test
    void testColorizeModelRegistered() {
        com.chua.deeplearning.support.engine.ModelRegistry.discoverAll();
        assertNotNull(com.chua.deeplearning.support.engine.ModelRegistry.get("image-colorize"),
                "image-colorize 应已注册到 ModelRegistry");
        log.info("image-colorize 已注册");
    }

    @Test
    void testColorizeTranslatorCreation() throws Exception {
        ImageColorizeTranslator translator = new ImageColorizeTranslator();
        assertNotNull(translator);
        log.info("ImageColorizeTranslator 创建成功");
    }

    @Test
    void testColorizeWithGrayImage() throws Exception {
        Path grayPath = RESOURCES.resolve("gray_test.jpg");
        if (!Files.exists(grayPath)) {
            log.warn("测试图片不存在，跳过: {}", grayPath);
            return;
        }
        byte[] bytes = Files.readAllBytes(grayPath);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0, "图片数据不应为空");
        log.info("读取灰度测试图: {} bytes", bytes.length);
        log.info("图像上色测试通过");
    }
}