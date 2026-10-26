package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.layout.LayoutDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.recognition.LayoutPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * indra 版面分析翻译器验收测试。
 *
 * <p>基准取自 {@code indra_ocr}（Python + onnxruntime）在同一张合成文档页上的输出：
 * 7 个版面块，标签依次为 Section-Header / Section-Header / Text / Section-Header / Table /
 * Section-Header / Text，外接框逐值吻合（float 精度内）。Java 端须复现同一分块与阅读顺序。</p>
 *
 * <p>中文页基准同样为 7 块，并验证 {@code script.onnx} 把中文行路由到 {@code rec_cjk.onnx}
 * （基础识别器读不出汉字）以及 CTC 贪婪折叠（{@code temperature} 而非 {@code temmperature}）。</p>
 *
 * <p>测试依赖 {@code utils-support-models-onnx-indra-layout} 内嵌权重
 * （det/rec/rec_cjk/order/script/meta.json）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IndraLayoutTranslatorTest {

    /** Python 参考实现输出的块标签（阅读顺序）。 */
    private static final String[] BASE_LABELS = {
            "Section-Header", "Section-Header", "Text", "Section-Header",
            "Table", "Section-Header", "Text"
    };

    /** Python 参考实现输出的块外接框 {x0,y0,x1,y1}（像素）。 */
    private static final double[][] BASE_BOX = {
            {79.4, 78.2, 620.6, 113.7},
            {81.8, 156.3, 240.8, 175.8},
            {78.9, 203.6, 678.5, 276.0},
            {78.2, 366.0, 271.8, 391.3},
            {89.9, 433.1, 740.7, 538.9},
            {79.3, 627.0, 188.7, 645.4},
            {78.9, 673.5, 708.5, 720.3},
    };

    /** 框位置允许误差（像素）。 */
    private static final double TOL = 3.0;

    /** 中文页 Python 参考实现输出的块标签（阅读顺序）。 */
    private static final String[] CJK_LABELS = {
            "Section-Header", "Section-Header", "Text", "Section-Header",
            "Table", "Section-Header", "Text"
    };

    /** 中文页 Python 参考实现输出的块外接框 {x0,y0,x1,y1}（像素）。 */
    private static final double[][] CJK_BOX = {
            {85.7, 79.0, 462.2, 123.6},
            {87.2, 197.6, 222.1, 231.8},
            {88.1, 254.8, 932.4, 283.4},
            {89.5, 347.9, 286.5, 381.5},
            {96.9, 413.7, 822.9, 582.4},
            {89.3, 687.3, 220.1, 722.2},
            {88.2, 745.4, 854.9, 773.0},
    };

    private static byte[] png;
    private static byte[] pngCjk;

    @BeforeAll
    static void loadFixture() throws Exception {
        png = readFixture("indra/indra_doc.png");
        pngCjk = readFixture("indra/indra_doc_cjk.png");
        ModelRegistry.discoverAll();
    }

    private static byte[] readFixture(String name) throws Exception {
        try (InputStream in = IndraLayoutTranslatorTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull(in, "测试图像 " + name + " 缺失");
            return in.readAllBytes();
        }
    }

    @Test
    void blocksMatchPythonBaseline() {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            List<IndraLayoutTranslator.Block> blocks = t.blocks(png);
            assertEquals(BASE_LABELS.length, blocks.size(), "版面块数量应与 Python 基准一致");

            for (int i = 0; i < blocks.size(); i++) {
                IndraLayoutTranslator.Block b = blocks.get(i);
                assertEquals(BASE_LABELS[i], b.label(), "第 " + i + " 块标签");
                double x0 = b.x();
                double y0 = b.y();
                double x1 = b.x() + b.width();
                double y1 = b.y() + b.height();
                assertEquals(BASE_BOX[i][0], x0, TOL, "第 " + i + " 块 x0");
                assertEquals(BASE_BOX[i][1], y0, TOL, "第 " + i + " 块 y0");
                assertEquals(BASE_BOX[i][2], x1, TOL, "第 " + i + " 块 x1");
                assertEquals(BASE_BOX[i][3], y1, TOL, "第 " + i + " 块 y1");
                assertTrue(b.confidence() > 0f && b.confidence() <= 1f, "置信度应在 (0,1]");
                assertFalse(b.text() == null || b.text().isBlank(), "块内文字不应为空");
            }

            // 阅读顺序：块顶边自上而下单调不减
            for (int i = 1; i < blocks.size(); i++) {
                assertTrue(blocks.get(i).y() >= blocks.get(i - 1).y() - 1.0,
                        "阅读顺序应自上而下");
            }

            // 关键内容抽查（OCR 大小写/空格可能有细微差异，仅校验关键字）
            assertTrue(blocks.get(0).text().contains("Weather"), "标题块应含 Weather");
            assertTrue(blocks.get(2).text().toLowerCase().contains("report"), "正文块应含 report");
            assertTrue(blocks.get(4).text().contains("Region"), "表格块应含表头 Region");
        } finally {
            t.close();
        }
    }

    @Test
    void markdownRendersHeadersAndTable() {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            String md = t.markdown(png);
            assertTrue(md.contains("## "), "Markdown 应含二级标题");
            assertTrue(md.contains("Weather"), "Markdown 应含标题正文");
            assertTrue(md.contains("Overview"), "Markdown 应含小节标题");

            // 表格块应渲染为 HTML table（表头用 <th>，单元格用 <td>）
            assertTrue(md.contains("<table>") && md.contains("</table>"), "应含 HTML 表格");
            assertTrue(md.contains("<th>Region</th>"), "表头应以 <th> 渲染: " + md);
            assertTrue(md.contains("<td>12.4 C</td>"), "单元格应以 <td> 渲染");
            assertTrue(md.contains("<td>South</td>"), "单元格应以 <td> 渲染");
            assertEquals(3, countOccurrences(md, "<tr>"), "表格应为 3 行（1 表头 + 2 数据行）");
        } finally {
            t.close();
        }
    }

    @Test
    void markdownMatchesPythonReference() {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            String md = t.markdown(png);
            // 与 Python page_markdown 参考输出（indra_md_ref.txt）结构一致：
            // 4 个二级标题（Annual / 1. / 2. / 3.）+ 1 个 3 行 HTML 表格
            int headings = countOccurrences(md, "\n## ") + (md.startsWith("## ") ? 1 : 0);
            assertEquals(4, headings, "应含 4 个二级标题\n" + md);
            assertTrue(md.contains("<th>Region</th>"), "表头应与参考一致");
            assertTrue(md.contains("<th>Rainfall</th>"), "表头应与参考一致");
            assertTrue(md.contains("<td>820") && md.contains("<td>1450"), "数据单元格应与参考一致");
        } finally {
            t.close();
        }
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int from = 0;
        while (true) {
            int i = s.indexOf(sub, from);
            if (i < 0) {
                break;
            }
            count++;
            from = i + sub.length();
        }
        return count;
    }

    @Test
    void cjkPageMatchesPythonBaseline() {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            List<IndraLayoutTranslator.Block> blocks = t.blocks(pngCjk);
            assertEquals(CJK_LABELS.length, blocks.size(), "中文页版面块数量应与 Python 基准一致");
            for (int i = 0; i < blocks.size(); i++) {
                IndraLayoutTranslator.Block b = blocks.get(i);
                assertEquals(CJK_LABELS[i], b.label(), "中文页第 " + i + " 块标签");
                assertEquals(CJK_BOX[i][0], b.x(), TOL, "中文页第 " + i + " 块 x0");
                assertEquals(CJK_BOX[i][1], b.y(), TOL, "中文页第 " + i + " 块 y0");
                assertEquals(CJK_BOX[i][2], b.x() + b.width(), TOL, "中文页第 " + i + " 块 x1");
                assertEquals(CJK_BOX[i][3], b.y() + b.height(), TOL, "中文页第 " + i + " 块 y1");
            }
            // 中文行由 script.onnx 路由到 rec_cjk.onnx 后才会有正确汉字
            assertTrue(blocks.get(0).text().contains("气象报告"),
                    "标题块应识别出中文: " + blocks.get(0).text());
            assertTrue(blocks.get(4).text().contains("平均温度"), "表格块应含中文表头");
        } finally {
            t.close();
        }
    }

    @Test
    void cjkMarkdownRendersChineseTable() {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            String md = t.markdown(pngCjk);
            int headings = countOccurrences(md, "\n## ") + (md.startsWith("## ") ? 1 : 0);
            assertEquals(4, headings, "中文页应含 4 个二级标题\n" + md);
            assertTrue(md.contains("<th>地区</th>"), "中文表头应以 <th> 渲染: " + md);
            assertTrue(md.contains("<td>北方</td>") && md.contains("<td>南方</td>"),
                    "中文单元格应以 <td> 渲染");
            assertTrue(md.contains("南方站点夜间偏暖"), "正文段落应识别出中文");
        } finally {
            t.close();
        }
    }

    @Test
    void ctcCollapseProducesCleanWords() {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            String md = t.markdown(png);
            assertTrue(md.contains("temperature"), "CTC 贪婪折叠应还原 temperature\n" + md);
            assertFalse(md.contains("temmperature"), "不应出现重复字符");
            assertTrue(md.contains("summarizes"), "CTC 贪婪折叠应还原 summarizes");
        } finally {
            t.close();
        }
    }

    @Test
    void imageDetectorConsumesModel() {
        List<DetectionInfo> dets = ImageDetector.create("indra-layout").detect(png);
        assertEquals(BASE_LABELS.length, dets.size(), "ImageDetector 应返回同数量版面块");
        for (int i = 0; i < dets.size(); i++) {
            assertEquals(BASE_LABELS[i], dets.get(i).label(), "ImageDetector 第 " + i + " 块标签");
        }
    }

    @Test
    void layoutPipelineConsumesModel() {
        Object result = LayoutPipeline.builder().model("indra-layout").build().recognizeSingle(png);
        assertNotNull(result, "LayoutPipeline 结果不应为 空");
        assertTrue(result instanceof List<?>, "LayoutPipeline 结果应为列表");
        assertEquals(BASE_LABELS.length, ((List<?>) result).size(), "LayoutPipeline 应返回同数量版面块");
    }

    @Test
    void registryListsModel() {
        List<String> models = LayoutDetector.listModels();
        assertTrue(models.contains("indra-layout"), "注册表应列出 indra-layout，实际: " + models);
    }

    @Test
    void emptyInputReturnsEmpty() {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            assertTrue(t.translate(new byte[0]).isEmpty(), "空输入应返回空列表");
            assertTrue(t.translate(null).isEmpty(), "null 输入应返回空列表");
        } finally {
            t.close();
        }
    }
}
