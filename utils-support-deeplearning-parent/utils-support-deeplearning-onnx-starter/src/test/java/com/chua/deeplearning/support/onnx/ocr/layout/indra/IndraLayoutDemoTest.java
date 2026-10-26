package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * indra 版面分析的 JUnit 示例（把 demo 变成可复跑的测试）。
 *
 * <p>对英文页与中文页各跑一遍完整链路，断言结果并把真实产物写到
 * {@code target/indra-demo/}：</p>
 * <ul>
 *   <li>{@code <tag>.blocks.txt} —— 版面块（标签 / 外接框 / 置信度 / 块内文字）</li>
 *   <li>{@code <tag>.md} —— Markdown（含 HTML 表格）</li>
 * </ul>
 *
 * <p>运行：{@code mvn test -Dtest=IndraLayoutDemoTest}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IndraLayoutDemoTest {

    /** 产物输出目录。 */
    private static final Path OUT = Path.of("target", "indra-demo");

    private static byte[] latin;
    private static byte[] cjk;

    @BeforeAll
    static void load() throws Exception {
        latin = read("indra/indra_doc.png");
        cjk = read("indra/indra_doc_cjk.png");
    }

    private static byte[] read(String name) throws Exception {
        try (InputStream in = IndraLayoutDemoTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "测试图像缺失: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void demoEnglishPage() throws Exception {
        demo("english", latin, "Weather", "Region", "<th>Region</th>");
    }

    @Test
    void demoChinesePage() throws Exception {
        demo("chinese", cjk, "气象报告", "平均温度", "<th>地区</th>");
    }

    /**
     * 跑一页并断言关键内容，产物落盘。
     *
     * @param tag        产物文件名前缀
     * @param png        页面图像
     * @param titleToken 标题块应含的文字
     * @param cellToken  表格块应含的文字
     * @param thToken    Markdown 表头应含的片段
     */
    private void demo(String tag, byte[] png, String titleToken, String cellToken, String thToken)
            throws Exception {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            // 1) 管线接口：图像 → 版面块（标签 + 外接框）
            List<DetectionInfo> dets = ImageDetector.create("indra-layout").detect(png);
            assertFalse(dets.isEmpty(), "应检出至少一个版面块");

            // 2) 原生接口：块内文字
            List<IndraLayoutTranslator.Block> blocks = t.blocks(png);
            assertEquals(dets.size(), blocks.size(), "两条入口的块数应一致");

            // 3) Markdown（含 HTML 表格）
            String md = t.markdown(png);
            assertFalse(md.isBlank(), "Markdown 不应为空");

            StringBuilder txt = new StringBuilder();
            txt.append("# ").append(tag).append(": ").append(blocks.size()).append(" blocks\n");
            for (int i = 0; i < blocks.size(); i++) {
                IndraLayoutTranslator.Block b = blocks.get(i);
                txt.append(String.format("%-16s x=%7.1f y=%7.1f w=%7.1f h=%7.1f conf=%.3f%n    %s%n",
                        b.label(), b.x(), b.y(), b.width(), b.height(), b.confidence(),
                        b.text().replace("\n", " / ")));
            }
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve(tag + ".blocks.txt"), txt.toString(), StandardCharsets.UTF_8);
            Files.writeString(OUT.resolve(tag + ".md"), md, StandardCharsets.UTF_8);

            // 4) 内容断言
            assertTrue(blocks.get(0).text().contains(titleToken),
                    "标题块应含 " + titleToken + "，实际: " + blocks.get(0).text());
            assertTrue(blocks.get(4).text().contains(cellToken),
                    "表格块应含 " + cellToken + "，实际: " + blocks.get(4).text());
            assertTrue(md.contains(thToken), "Markdown 表头应含 " + thToken + "\n" + md);
            assertTrue(md.contains("<table>") && md.contains("</table>"), "应含 HTML 表格");
        } finally {
            t.close();
        }
    }
}
