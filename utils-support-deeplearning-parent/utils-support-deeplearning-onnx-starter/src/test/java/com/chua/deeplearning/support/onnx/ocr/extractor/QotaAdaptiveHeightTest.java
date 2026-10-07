package com.chua.deeplearning.support.onnx.ocr.extractor;

import com.chua.deeplearning.support.ocr.OcrPipeline;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unclip 自适应行高回归：检测框高度必须随字号自动缩放，不得依赖固定 padding。
 *
 * <p>同一句中文分别以雅黑 28/48/96px 渲染成三张图。unclip 外扩距离按
 * {@code 面积 * unclipRatio / 周长} 计算，本身随轮廓尺寸缩放，因此框高/字号
 * 比例应稳定在一个区间（字形上下留白余量），而非固定像素。此用例锁死该性质，
 * 若有人改回固定 cropPadding 补丁，三种字号的比例会离散，用例即失败。</p>
 */
class QotaAdaptiveHeightTest {

    private static final String LINE = "开放时间上午九点至下午五点";

    /** 字号 → 期望框高/字号 比例下界（unclip 后含字形留白，实测 1.22~1.31）。 */
    private static final double MIN_RATIO = 1.0;

    /** 比例上界：外扩过多会吞并邻行，实测不超过 1.35，留余量取 1.6。 */
    private static final double MAX_RATIO = 1.6;

    @Test
    void boxHeightScalesWithFontSize() throws Exception {
        OcrPipeline pipe = OcrPipeline.builder()
                .detector("paddleocrv6-det")
                .recognizer("qota-ocr")
                .build();
        String[] files = {"zh_s28.png", "zh_m48.png", "zh_l96.png"};
        int[] fontPx = {28, 48, 96};
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < files.length; i++) {
            byte[] img = resourceBytes(files[i]);
            var boxes = pipe.detector().detect(img);
            assertEquals(1, boxes.size(), files[i] + " 应只检出一行");
            double h = boxes.get(0).height();
            double ratio = h / fontPx[i];
            report.append(String.format("font=%dpx h=%.1f ratio=%.2f%n", fontPx[i], h, ratio));
            assertTrue(ratio >= MIN_RATIO && ratio <= MAX_RATIO,
                    files[i] + " 框高未随字号自适应（font=" + fontPx[i]
                            + " h=" + h + " ratio=" + ratio + "），需人工固定 padding 才能识别: " + report);
            // 框高自适应的最终目的：识别正确
            String text = pipe.recognize(img);
            assertEquals(LINE, text, files[i] + " 识别不一致: " + text);
        }
        Files.write(Paths.get(System.getProperty("java.io.tmpdir"), "qota-adaptive.txt"),
                report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream in = QotaAdaptiveHeightTest.class.getClassLoader()
                .getResourceAsStream("ocr/qota-size/" + name)) {
            assertNotNull(in, "测试图片缺失: " + name);
            return in.readAllBytes();
        }
    }
}
