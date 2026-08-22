package com.chua.common.support.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImageProcessor API 基础测试
 */
class ImageProcessorApiTest {

    @Test
    void testGetProcessor() {
        ImageProcessor processor = ImageProcessors.getProcessor();
        assertNotNull(processor, "应找到默认的 ImageProcessor 实现");
    }

    @Test
    void testFluentApi() {
        // 验证 FluentProcessor API 存在且可链式调用
        // 使用占位数据，不实际处理图片
        byte[] placeholder = new byte[]{0x00, 0x01, 0x02};
        ImageProcessors.FluentProcessor fp = ImageProcessors.from(placeholder);
        assertNotNull(fp, "FluentProcessor 不应为 null");
    }

    @Test
    void testFluentChain() {
        byte[] placeholder = new byte[]{0x00, 0x01, 0x02};
        // 链式 API 应可正常调用（不抛出NoSuchMethod）
        ImageProcessors.FluentProcessor fp = ImageProcessors.from(placeholder);
        // 这些方法应该存在
        assertDoesNotThrow(() -> fp.getClass().getMethod("resize", int.class, int.class));
        assertDoesNotThrow(() -> fp.getClass().getMethod("grayscale"));
        assertDoesNotThrow(() -> fp.getClass().getMethod("rotate", int.class));
        assertDoesNotThrow(() -> fp.getClass().getMethod("blur", int.class));
        assertDoesNotThrow(() -> fp.getClass().getMethod("brightness", int.class));
        assertDoesNotThrow(() -> fp.getClass().getMethod("crop", int.class, int.class, int.class, int.class));
        assertDoesNotThrow(() -> fp.getClass().getMethod("flip", String.class));
    }
}
