package com.chua.deeplearning.support.onnx.yolo.v8;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.translate.Translator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import com.chua.deeplearning.support.onnx.yolo.v8.translator.Yolo8IdCardDetectTranslator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YOLOv8 ID Card Detection Tests
 */
@DisplayName("ID Card Detection Tests")
class Yolo8IdCardDetectTest {

    private static final int INPUT_SIZE = 640;
    private static final float CONF_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.45f;

    @Test
    @DisplayName("Translator 实例化成功")
    void translator_instantiation_succeeds() {
        Translator<Image, DetectedObjects> translator = new Yolo8IdCardDetectTranslator();
        assertNotNull(translator, "translator 应成功实例化");
    }

    @Test
    @DisplayName("合成测试图预处理后 shape 正确")
    void preprocess_shape_correct() throws Exception {
        BufferedImage img = createTestImage(640, 480);
        byte[] bytes = toByteArray(img);
        try (var manager = ai.djl.ndarray.NDManager.newBaseManager()) {
            Image image = ai.djl.modality.cv.ImageFactory.getInstance().fromInputStream(new ByteArrayInputStream(bytes));
            var array = image.toNDArray(manager, ai.djl.modality.cv.Image.Flag.COLOR);
            assertEquals(480, array.getShape().get(0), "height");
            assertEquals(640, array.getShape().get(1), "width");
            assertEquals(3, array.getShape().get(2), "channels");
        }
    }

    @Test
    @DisplayName("检测参数合理性验证")
    void detection_params_reasonable() {
        var translator = new Yolo8IdCardDetectTranslator(INPUT_SIZE, CONF_THRESHOLD, IOU_THRESHOLD, 100);
        assertNotNull(translator, "translator 应创建成功");
    }

    private byte[] toByteArray(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private BufferedImage createTestImage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillRect(width / 4, height / 4, width / 2, height / 3);
        g.setColor(Color.BLUE);
        g.drawRect(width / 4, height / 4, width / 2, height / 3);
        g.dispose();
        return img;
    }
}
