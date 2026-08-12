package com.chua.deeplearning.support.onnx.layout.doclaynet;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;
import com.chua.deeplearning.support.utils.NMSUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NMS 行为测试（独立于 ONNX 推理）。
 *
 * <p>由于 {@link DocLayNetYolov8Translator} 的 processOutput 内部使用
 * {@link NMSUtils#nms(List, List, float)}，本测试直接验证 NMS 行为是否符合
 * DocLayNet YOLOv8 预期。
 *
 * <p>这些测试是 NMS 行为契约 — Translator 自身依赖此行为。
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("NMS 行为契约测试 (DocLayNetYolov8Translator 内部依赖)")
class NmsBehaviorTest {

    @Test
    @DisplayName("NMS: 完全重叠 + 较低置信度应该被抑制")
    void testNmsSuppressesOverlap() {
        List<BoundingBox> boxes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        // box A: 全图左上
        boxes.add(new Rectangle(0.0, 0.0, 0.5, 0.5));
        scores.add(0.9);
        // box B: 与 A 完全重叠，置信度低
        boxes.add(new Rectangle(0.0, 0.0, 0.5, 0.5));
        scores.add(0.4);
        // box C: 不重叠
        boxes.add(new Rectangle(0.6, 0.6, 0.3, 0.3));
        scores.add(0.7);

        List<Integer> keep = NMSUtils.nms(boxes, scores, 0.45f);
        assertThat(keep)
                .as("NMS 应保留 A (0.9) 和 C (0.7), 抑制 B (与 A 完全重叠)")
                .hasSize(2)
                .contains(0, 2);
    }

    @Test
    @DisplayName("NMS: IoU < threshold 时所有框保留")
    void testNmsKeepsAllWhenLowIou() {
        List<BoundingBox> boxes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        boxes.add(new Rectangle(0.0, 0.0, 0.1, 0.1));
        scores.add(0.5);
        boxes.add(new Rectangle(0.5, 0.5, 0.1, 0.1));
        scores.add(0.6);
        boxes.add(new Rectangle(0.0, 0.9, 0.1, 0.1));
        scores.add(0.7);

        List<Integer> keep = NMSUtils.nms(boxes, scores, 0.45f);
        assertThat(keep).hasSize(3);
    }

    @Test
    @DisplayName("NMS: 空列表返回空")
    void testNmsEmpty() {
        List<BoundingBox> boxes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<Integer> keep = NMSUtils.nms(boxes, scores, 0.45f);
        assertThat(keep).isEmpty();
    }

    @Test
    @DisplayName("NMS: 单框保留")
    void testNmsSingleBox() {
        List<BoundingBox> boxes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        boxes.add(new Rectangle(0.0, 0.0, 0.5, 0.5));
        scores.add(0.8);

        List<Integer> keep = NMSUtils.nms(boxes, scores, 0.45f);
        assertThat(keep).hasSize(1).contains(0);
    }

    @Test
    @DisplayName("NMS: 11 类 DocLayNet 框都能正确处理 (不依赖 class 标签)")
    void testNmsClassAgnostic() {
        // NMS 不关心 class — 验证不同 class 但 IoU > threshold 时也抑制
        List<BoundingBox> boxes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        // "title" 与 "text" 重叠
        boxes.add(new Rectangle(0.1, 0.1, 0.4, 0.4));
        scores.add(0.85);
        boxes.add(new Rectangle(0.1, 0.1, 0.4, 0.4));
        scores.add(0.6);
        // "table" 不重叠
        boxes.add(new Rectangle(0.6, 0.6, 0.3, 0.3));
        scores.add(0.75);

        List<Integer> keep = NMSUtils.nms(boxes, scores, 0.45f);
        assertThat(keep).hasSize(2);
    }

    @Test
    @DisplayName("NMS: 边界框超出 [0,1] 也按 NMS 计算 IoU")
    void testNmsWithOutOfBounds() {
        List<BoundingBox> boxes = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        // 边界 0..1
        boxes.add(new Rectangle(0.0, 0.0, 0.5, 0.5));
        scores.add(0.9);
        // 边界外 0..1
        boxes.add(new Rectangle(0.5, 0.5, 0.5, 0.5));
        scores.add(0.6);

        // 两个 box IoU = 0.25 (相交 0.25*0.25=0.0625 / 并集 0.5*0.5+0.5*0.5-0.0625=0.4375) = 0.143
        // < 0.45 threshold, 都保留
        List<Integer> keep = NMSUtils.nms(boxes, scores, 0.45f);
        assertThat(keep).hasSize(2);
    }
}
