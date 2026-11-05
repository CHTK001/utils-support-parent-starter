// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.layout;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WevisdocTranslator 单元测试。
 * <p>
 * 测试 parseOutput 静态方法（不依赖真实权重，纯逻辑验证）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
class WevisdocTranslatorTest {

    /**
     * 通过反射获取 boundingBoxes 私有字段。
     *
     * @param obj DetectedObjects
     * @return boundingBoxes 列表
     */
    @SuppressWarnings("unchecked")
    private List<BoundingBox> getBoxes(DetectedObjects obj) {
        try {
            Field field = DetectedObjects.class.getDeclaredField("boundingBoxes");
            field.setAccessible(true);
            return (List<BoundingBox>) field.get(obj);
        } catch (Exception e) {
            log.error("反射获取 boundingBoxes 失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 测试正常解析：3 个框，1 个低置信度被过滤。
     */
    @Test
    void testParseOutput() {
        NDManager manager = NDManager.newBaseManager();
        float[] data = {
                0.1f, 0.2f, 0.3f, 0.4f, 0.0f, 0.9f,  // text
                0.5f, 0.6f, 0.7f, 0.8f, 2.0f, 0.8f,  // table
                0.1f, 0.2f, 0.3f, 0.4f, 0.0f, 0.1f,  // text (低置信度，应过滤)
        };
        NDArray array = manager.create(data).reshape(3, 6);

        DetectedObjects result = WevisdocTranslator.parseOutput(array, 1000, 2000, 0.3f);

        assertEquals(2, result.getNumberOfObjects());
        assertEquals("text", result.getClassNames().get(0));
        assertEquals("table", result.getClassNames().get(1));
        assertEquals(0.9, result.getProbabilities().get(0), 0.001);
        assertEquals(0.8, result.getProbabilities().get(1), 0.001);
    }

    /**
     * 测试空输出。
     */
    @Test
    void testParseOutputEmpty() {
        NDManager manager = NDManager.newBaseManager();
        NDArray array = manager.create(new float[0]).reshape(0, 6);

        DetectedObjects result = WevisdocTranslator.parseOutput(array, 1000, 2000, 0.3f);

        assertEquals(0, result.getNumberOfObjects());
    }

    /**
     * 测试标签列表（11 类）。
     */
    @Test
    void testGetLayoutLabels() {
        assertEquals(11, WevisdocTranslator.getLayoutLabels().size());
        assertTrue(WevisdocTranslator.getLayoutLabels().contains("text"));
        assertTrue(WevisdocTranslator.getLayoutLabels().contains("table"));
        assertTrue(WevisdocTranslator.getLayoutLabels().contains("figure"));
        assertTrue(WevisdocTranslator.getLayoutLabels().contains("equation"));
        assertTrue(WevisdocTranslator.getLayoutLabels().contains("header"));
        assertTrue(WevisdocTranslator.getLayoutLabels().contains("footer"));
    }

    /**
     * 测试越界 class_id 被过滤。
     */
    @Test
    void testParseOutputInvalidClass() {
        NDManager manager = NDManager.newBaseManager();
        float[] data = {
                0.1f, 0.2f, 0.3f, 0.4f, 99.0f, 0.9f,  // 越界 class_id，应过滤
                0.5f, 0.6f, 0.7f, 0.8f, 0.0f, 0.9f,  // text
        };
        NDArray array = manager.create(data).reshape(2, 6);

        DetectedObjects result = WevisdocTranslator.parseOutput(array, 1000, 2000, 0.3f);

        assertEquals(1, result.getNumberOfObjects());
        assertEquals("text", result.getClassNames().get(0));
    }

    /**
     * 测试负坐标被裁剪到 0。
     */
    @Test
    void testParseOutputNegativeCoords() {
        NDManager manager = NDManager.newBaseManager();
        float[] data = {
                -0.1f, -0.2f, 0.3f, 0.4f, 0.0f, 0.9f,  // 负坐标，应裁剪到 0
        };
        NDArray array = manager.create(data).reshape(1, 6);

        DetectedObjects result = WevisdocTranslator.parseOutput(array, 1000, 2000, 0.3f);

        assertEquals(1, result.getNumberOfObjects());
        assertEquals("text", result.getClassNames().get(0));
        List<BoundingBox> boxes = getBoxes(result);
        assertEquals(0.0, boxes.get(0).getBounds().getX(), 0.001);
        assertEquals(0.0, boxes.get(0).getBounds().getY(), 0.001);
    }

    /**
     * 测试列数不足 6 被过滤。
     */
    @Test
    void testParseOutputInsufficientCols() {
        NDManager manager = NDManager.newBaseManager();
        float[] data = {0.1f, 0.2f, 0.3f, 0.4f, 0.0f};  // 只有 5 列
        NDArray array = manager.create(data).reshape(1, 5);

        DetectedObjects result = WevisdocTranslator.parseOutput(array, 1000, 2000, 0.3f);

        assertEquals(0, result.getNumberOfObjects());
    }
}