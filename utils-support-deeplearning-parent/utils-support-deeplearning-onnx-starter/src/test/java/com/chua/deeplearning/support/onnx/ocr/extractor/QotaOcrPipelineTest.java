package com.chua.deeplearning.support.onnx.ocr.extractor;

import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * qota-ocr 中文 OCR 管线验收：{@code paddleocrv6-det} 检测 + {@code qota-ocr} 识别。
 *
 * <p>覆盖管线全部公开入口：{@code recognize} / {@code recognizeDetail} /
 * {@code recognizeDetailWithImage}，以及 {@link OcrRecognizer} 的链式入口
 * （lang/modelPath/device/useGpu/threshold）。</p>
 */
class QotaOcrPipelineTest {

    /** 测试图三行原文（zh_page.png，雅黑 64px）。 */
    private static final String[] LINES = {
            "请在入口处领取中文导览手册",
            "开放时间上午九点至下午五点",
            "地址人民路一百二十号东侧入口"
    };

    /**
     * unclip 回归：检测框必须覆盖完整行高（雅黑 64px 字形实际约 64~70px）。
     *
     * <p>修复前概率图阈值把文字行上下切瘦，框高仅 ~31px，下游裁剪块
     * 被切掉字的上下半截；unclip 外扩后框高恢复 ~75px。同时断言上界，
     * 保证外扩没有把相邻两行合并成一个框。</p>
     */
    @Test
    void detectionBoxesCoverFullLineHeight() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_page.png");
        OcrPipeline pipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .build();
        var boxes = pipe.detector().detect(img);
        assertEquals(3, boxes.size(), "检测框数应等于行数");
        for (var b : boxes) {
            assertTrue(b.height() >= 50, "框高未恢复完整行高: " + b.height());
            assertTrue(b.height() <= 110, "框高越界（疑似合并邻行）: " + b.height());
        }
    }

    @Test
    void pipelineRecognizesChinesePage() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_page.png");
        OcrPipeline pipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .build();
        String text = pipe.recognize(img);
        assertNotNull(text);
        for (String line : LINES) {
            assertTrue(text.contains(line), "整页识别缺行[" + line + "]: " + text);
        }
        Files.write(Paths.get(System.getProperty("java.io.tmpdir"), "qota-pipe.txt"),
                text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pipelineRecognizeDetail() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_page.png");
        OcrPipeline pipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .build();
        List<OcrResult> results = pipe.recognizeDetail(img);
        assertNotNull(results);
        assertEquals(3, results.size(), "明细框数应等于行数");
        for (int i = 0; i < results.size(); i++) {
            assertEquals(LINES[i], results.get(i).text(), "第 " + (i + 1) + " 行识别不一致");
        }
    }

    @Test
    void pipelineRecognizeDetailWithImage() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_page.png");
        OcrPipeline pipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .build();
        OcrPipeline.OcrRecognizeResult out = pipe.recognizeDetailWithImage(img);
        assertNotNull(out);
        assertNotNull(out.image());
        assertTrue(out.image().length > 0);
        assertNotNull(out.results());
        assertEquals(3, out.results().size());
        assertEquals(LINES[0], out.results().get(0).text());
    }

    @Test
    void recognizerChainSetters() {
        OcrRecognizer r = OcrRecognizer.create("qota-ocr");
        assertNotNull(r);
        assertSame(r, r.lang("zh"));
        assertSame(r, r.device("cpu"));
        assertSame(r, r.useGpu(false));
        assertSame(r, r.threshold(0.5f));
        assertSame(r, r.modelPath(""));
    }

    @Test
    void recognizerSingleLine() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_white.png");
        OcrRecognizer r = OcrRecognizer.create("qota-ocr");
        String text = r.recognize(img);
        assertNotNull(text);
        assertTrue(text.contains("中文"), "单行识别缺关键字: " + text);
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream in = QotaOcrPipelineTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "测试图片缺失: " + name);
            return in.readAllBytes();
        }
    }
}
