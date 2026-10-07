package com.chua.deeplearning.support.onnx.ocr;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.translator.ITranslator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OcrPipeline 中文纠错（macbert4csc）验收：corrector 为可选非必填后处理，
 * 仅中文用例，端到端渲染错别字图片验证纠错生效且不影响原管线。
 */
class OcrPipelineCorrectorTest {

    /** zh_page.png 三行原文（雅黑 64px）。 */
    private static final String[] LINES = {
            "请在入口处领取中文导览手册",
            "开放时间上午九点至下午五点",
            "地址人民路一百二十号东侧入口"
    };

    /** pycorrector 官方示例错别字文本。 */
    private static final String TYPED_SAMPLE = "少先队员因该为老人让坐";

    private static byte[] zhPage;
    private static OcrPipeline rawPipe;
    private static OcrPipeline fixedPipe;
    private static ImageDetector detector;
    private static OcrRecognizer recognizer;

    @BeforeAll
    static void setUp() throws Exception {
        ModelRegistry.discoverAll();
        zhPage = resourceBytes("ocr/qota/zh_page.png");
        rawPipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .build();
        fixedPipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .corrector("macbert4csc")
                .build();
        detector = rawPipe.detector();
        recognizer = rawPipe.recognizer();
    }

    @Test
    void correctorOptionalAndBehaviorUnchanged() throws Exception {
        assertNull(rawPipe.corrector(), "未配置 corrector 时应为 空");
        String text = rawPipe.recognize(zhPage);
        for (String line : LINES) {
            assertTrue(text.contains(line), "原文识别缺行[" + line + "]: " + text);
        }
    }

    @Test
    void unknownModelIdDegradesGracefully() throws Exception {
        OcrPipeline pipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .corrector("not-registered-model")
                .build();
        assertNull(pipe.corrector(), "未注册 modelId 应优雅降级为不纠错");
        String text = pipe.recognize(zhPage);
        for (String line : LINES) {
            assertTrue(text.contains(line), "降级后识别结果应不变[" + line + "]: " + text);
        }
    }

    @Test
    void correctorFixesRenderedTypo() throws Exception {
        byte[] img = renderText(TYPED_SAMPLE);
        String raw = rawPipe.recognize(img);
        String fixed = fixedPipe.recognize(img);
        assertNotNull(fixedPipe.corrector(), "已配置 corrector 时应非空");
        assertTrue(raw.contains("因该"), "原文识别应含错别字: " + raw);
        assertTrue(fixed.contains("应该"), "错别字 因该 应被纠正为 应该: " + fixed);
        assertFalse(fixed.contains("因该"), "错别字未被修复: " + fixed);
    }

    @Test
    void correctorKeepsCorrectTextUnchanged() throws Exception {
        byte[] img = renderText(LINES[0]);
        String text = fixedPipe.recognize(img);
        assertTrue(text.contains(LINES[0]), "正确文本不应被纠错破坏: " + text);
    }

    @Test
    void correctorKeepsZhPageLinesUnchanged() {
        List<OcrResult> results = fixedPipe.recognizeDetail(zhPage);
        assertEquals(3, results.size(), "明细框数应等于行数");
        for (int i = 0; i < results.size(); i++) {
            assertEquals(LINES[i], results.get(i).text(), "第 " + (i + 1) + " 行不应被误改");
        }
    }

    @Test
    void listModelsContainsCorrectorGroup() {
        Map<String, List<String>> models = rawPipe.listModels();
        assertTrue(models.getOrDefault("corrector", List.of()).contains("macbert4csc"),
                "listModels 应含 corrector 分组: " + models);
        assertFalse(models.getOrDefault("detector", List.of()).isEmpty(),
                "detector 分组应非空: " + models);
    }

    @Test
    void onnxPipelineDualConstructors() throws Exception {
        OnnxOcrPipeline plain = new OnnxOcrPipeline(detector, recognizer, null, null,
                false, true, 0f, 2, 40, 25f, false, 100f, false, false);
        assertNull(plain.corrector(), "旧构造器应不启用纠错");
        byte[] img = renderText(TYPED_SAMPLE);
        assertTrue(plain.recognize(img).contains("因该"), "旧构造器行为应保持不变");

        ITranslator<Object, Object> corrector = fixedPipe.corrector();
        assertNotNull(corrector);
        OnnxOcrPipeline withFix = new OnnxOcrPipeline(detector, recognizer, null, null, corrector,
                false, true, 0f, 2, 40, 25f, false, 100f, false, false);
        assertSame(corrector, withFix.corrector(), "新构造器应透传 corrector");
        assertTrue(withFix.recognize(img).contains("应该"), "新构造器应可纠错: " + withFix.recognize(img));
    }

    /**
     * 运行时渲染单行中文图片（雅黑 64px，白底黑字）。
     *
     * @param text 文本
     * @return PNG 字节
     * @throws Exception 渲染或编码失败
     */
    private static byte[] renderText(String text) throws Exception {
        int width = 1400;
        int height = 140;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 64));
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 30, (height - fm.getHeight()) / 2 + fm.getAscent());
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream in = OcrPipelineCorrectorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "测试图片缺失: " + name);
            return in.readAllBytes();
        }
    }
}
