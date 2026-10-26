package com.chua.deeplearning.support.pytorch.layout;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.TranslatorContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WevisdocRemoteTranslator 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WevisdocRemoteTranslatorTest {

    /**
     * 简单 TranslatorContext mock。
     */
    static class MockContext implements TranslatorContext {
        private final Map<String, Object> attachments = new HashMap<>();

        @Override
        public ai.djl.Model getModel() {
            return null;
        }

        @Override
        public NDManager getNDManager() {
            return null;
        }

        @Override
        public NDManager getPredictorManager() {
            return null;
        }

        @Override
        public ai.djl.nn.Block getBlock() {
            return null;
        }

        @Override
        public ai.djl.metric.Metrics getMetrics() {
            return null;
        }

        @Override
        public Object getAttachment(String key) {
            return attachments.get(key);
        }

        @Override
        public void setAttachment(String key, Object value) {
            attachments.put(key, value);
        }

        @Override
        public void close() {
        }
    }

    @Test
    void testProcessOutputWithHeaders() {
        String markdown = "# Title\n## Section 1\n### Subsection\n";
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();

        MockContext ctx = new MockContext();
        ctx.setAttachment("wevisdoc_markdown", markdown);

        DetectedObjects result = translator.processOutput(ctx, new NDList());

        assertEquals(3, result.getNumberOfObjects());
    }

    @Test
    void testProcessOutputWithTable() {
        String markdown = "# Title\n\n| Name | Value |\n|------|-------|\n| A | 1 |\n| B | 2 |\n\nSome text.\n";
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();

        MockContext ctx = new MockContext();
        ctx.setAttachment("wevisdoc_markdown", markdown);

        DetectedObjects result = translator.processOutput(ctx, new NDList());

        assertTrue(result.getNumberOfObjects() >= 2);
    }

    @Test
    void testProcessOutputWithEmptyMarkdown() {
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();

        MockContext ctx = new MockContext();
        ctx.setAttachment("wevisdoc_markdown", "");

        DetectedObjects result = translator.processOutput(ctx, new NDList());

        assertEquals(0, result.getNumberOfObjects());
    }

    @Test
    void testProcessOutputWithNullMarkdown() {
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();

        MockContext ctx = new MockContext();
        ctx.setAttachment("wevisdoc_markdown", null);

        DetectedObjects result = translator.processOutput(ctx, new NDList());

        assertEquals(0, result.getNumberOfObjects());
    }

    @Test
    void testProcessOutputWithNoAttachment() {
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();

        MockContext ctx = new MockContext();
        // 不设置 attachment

        DetectedObjects result = translator.processOutput(ctx, new NDList());

        assertEquals(0, result.getNumberOfObjects());
    }

    @Test
    void testProcessOutputWithMixedElements() {
        String markdown = "# Title\n\n"
                + "Some text.\n\n"
                + "- item 1\n- item 2\n\n"
                + "![figure](img.png)\n\n"
                + "$$E=mc^2$$\n";
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();

        MockContext ctx = new MockContext();
        ctx.setAttachment("wevisdoc_markdown", markdown);

        DetectedObjects result = translator.processOutput(ctx, new NDList());

        assertTrue(result.getNumberOfObjects() >= 4);

        // 验证至少有 header, list, figure, equation 类别
        String[] labels = result.getClassNames().toArray(new String[0]);
        boolean hasHeader = false;
        boolean hasList = false;
        boolean hasFigure = false;
        boolean hasEquation = false;
        for (String label : labels) {
            if ("paragraph_title".equals(label)) hasHeader = true;
            if ("list".equals(label)) hasList = true;
            if ("figure".equals(label)) hasFigure = true;
            if ("equation".equals(label)) hasEquation = true;
        }
        assertTrue(hasHeader, "should have paragraph_title");
        assertTrue(hasList, "should have list");
        assertTrue(hasFigure, "should have figure");
        assertTrue(hasEquation, "should have equation");
    }

    @Test
    void testConstructorDefault() {
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();
        assertNotNull(translator);
    }

    @Test
    void testConstructorWithClient() {
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator(new WevisdocHttpClient("http://localhost:9999"));
        assertNotNull(translator);
    }
}
