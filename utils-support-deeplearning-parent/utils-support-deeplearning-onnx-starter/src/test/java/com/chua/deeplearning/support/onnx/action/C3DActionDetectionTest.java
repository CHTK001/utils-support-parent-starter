package com.chua.deeplearning.support.onnx.action;

import com.chua.deeplearning.support.image.ActionDetector;
import com.chua.deeplearning.support.model.ActionDetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class C3DActionDetectionTest {

    @BeforeAll
    static void setup() {
        try {
            nu.pattern.OpenCV.loadShared();
        } catch (Exception e) {
            log.warn("OpenCV 加载失败: {}", e.getMessage());
        }
    }

    @Test
    void testActionDetectionWithTestVideo() throws Exception {
        C3DActionDetectionTranslator translator = C3DActionDetectionTranslator.getInstance();
        Path videoPath = Paths.get("src/test/resources/action/action_test.mp4");
        assertTrue(Files.exists(videoPath), "测试视频文件不存在");

        byte[] videoData = Files.readAllBytes(videoPath);
        assertTrue(videoData.length > 0, "视频数据为空");

        List<ActionDetectionResult> results = translator.translate(videoData);
        log.info("检测到 {} 个动作结果", results.size());
        for (ActionDetectionResult r : results) {
            log.info("  时间: {}s, 动作: {}, 置信度: {}, 位置: [{}, {}, {}, {}]",
                    r.timestamp(), r.label(), r.confidence(),
                    r.x(), r.y(), r.width(), r.height());
        }
        assertNotNull(results);
    }

    @Test
    void testActionDetectorFacade() throws Exception {
        ActionDetector detector = ActionDetector.create("c3d-action-detection");
        assertNotNull(detector);

        Path videoPath = Paths.get("src/test/resources/action/action_test.mp4");
        assertTrue(Files.exists(videoPath), "测试视频文件不存在");

        byte[] videoData = Files.readAllBytes(videoPath);
        List<ActionDetectionResult> results = detector.detect(videoData);
        log.info("Facade API 检测到 {} 个动作结果", results.size());
        for (ActionDetectionResult r : results) {
            log.info("  时间: {}s, 动作: {}, 置信度: {}",
                    r.timestamp(), r.label(), r.confidence());
        }
        assertNotNull(results);
    }

    @Test
    void testListActionModels() {
        List<String> models = ActionDetector.listModels();
        log.info("可用动作检测模型: {}", models);
        assertTrue(models.contains("c3d-action-detection"),
                "c3d-action-detection 应已注册到 ModelRegistry");
    }
}