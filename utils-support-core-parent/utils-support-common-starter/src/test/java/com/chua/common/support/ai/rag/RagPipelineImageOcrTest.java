package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.MemoryChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.splitter.SentenceTextSplitter;
import com.chua.common.support.ai.splitter.TextChunk;
import com.chua.common.support.file.txtractor.TextExtractResult;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.vector.MemoryVectorStorage;
import com.chua.common.support.vector.VectorCompareAlgorithm;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * RagPipeline 图片 OCR 分支冒烟测试。
 *
 * <p>用内存组件（MemoryEmbeddingClient + MemoryVectorStorage + MemoryChatClient）
 * 验证图片文件经 RagPipeline 上传时的 OCR 分支路由与降级行为，不依赖真实 OCR 模型：</p>
 * <ol>
 *   <li>注入固定文本的 OCR 提取器：图片上传 → 分块 → 嵌入 → 检索命中</li>
 *   <li>未注入 OCR 提取器：图片上传 → 降级为 UTF-8 字节读取 → 正常入管道</li>
 *   <li>超过 1MB 无提取器文件：跳过 UTF-8 解码避免 OOM</li>
 *   <li>分块器防死循环回归（高频句界乱码 + 任意 overlap）</li>
 * </ol>
 *
 * <p>真实 PaddleOCRv6 ONNX 的端到端识别已在临时目录的 OcrImageTest 验证。</p>
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RagPipelineImageOcrTest {

    /**
     * 固定文本 OCR 提取器（测试桩）：返回预设文本，用于验证 RagPipeline 图片分支路由，不依赖真实 OCR 模型
    */
    private static final class FakeOcrTextExtractor implements TextExtractor {

        /**
         * 预设识别文本
        */
        private final String fixedText;

        /**
         * 创建固定文本提取器。
         *
         * @param fixedText 预设识别结果文本
         */
        private FakeOcrTextExtractor(String fixedText) {
            this.fixedText = fixedText;
        }

        @Override
        public List<TextExtractResult> extractText(java.io.File file) {
            return List.of(new TextExtractResult(fixedText, "ocr"));
        }

        @Override
        public String type() {
            return "ocr";
        }
    }

    /**
     * 通过断言计数
    */
    private static int passed = 0;
    /**
     * 失败断言计数
    */
    private static int failed = 0;
    /**
     * 测试临时目录根（统一输出到 test-output/，避免散落系统临时目录）
    */
    private static final String TEST_OUTPUT_ROOT = Paths.get(System.getProperty("java.io.tmpdir"),
            "test-output", "rag-image-ocr").toAbsolutePath().toString();

    /**
     * 测试入口：顺序执行全部图片 OCR 分支用例并汇总结果。
     *
     * @param args 命令行参数（本测试未使用，保留占位）
     * @throws Exception 文件读写或图片渲染失败时抛出
     */
    public static void main(String[] args) throws Exception {
        testImageUploadWithOcrExtractor();
        testImageUploadWithoutOcrFallsBack();
        testLargeBinaryWithoutExtractorSkipped();
        testSplitterNoInfiniteLoopOnRandomText();

        System.out.println("");
        System.out.println("[RESULT] passed=" + passed + " failed=" + failed);
        System.out.println(failed == 0 ? "ALL-PASSED" : "HAS-FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * 图片上传 + OCR 提取器：文本被分块嵌入，检索可命中 OCR 内容。
     *
     * @throws Exception 临时目录创建或文件读写失败时抛出
     */
    static void testImageUploadWithOcrExtractor() throws Exception {
        Path dir = Files.createDirectories(Paths.get(TEST_OUTPUT_ROOT, "with-ocr-" + System.nanoTime()));
        RagPipeline pipeline = newPipeline(dir, new FakeOcrTextExtractor(
                "scanned invoice 2024 total 1250 yuan payment due"));

        byte[] pngBytes = renderTestPng();
        RagDocument doc = pipeline.uploadDocument("invoice.png", pngBytes);
        check("READY".equals(doc.status()), "image upload via OCR extractor returns READY");
        check(doc.chunkCount() >= 1, "OCR text is split into chunks, count=" + doc.chunkCount());

        RagResponse response = pipeline.query("payment due", 5, 0.0);
        check(response.sources().stream().anyMatch(s -> s.documentId().equals(doc.id())),
                "query hits the image document by OCR content");
        check(response.answer() != null && !response.answer().isBlank(), "answer generated for OCR image doc");
        pipeline.close();
    }

    /**
     * 图片上传未注入 OCR 提取器：图片走 UTF-8 降级读取（字节乱码但非空），管道不中断。
     * 验证的是路由正确性，而非 OCR 识别质量。
     *
     * @throws Exception 临时目录创建或文件读写失败时抛出
     */
    static void testImageUploadWithoutOcrFallsBack() throws Exception {
        Path dir = Files.createDirectories(Paths.get(TEST_OUTPUT_ROOT, "fallback-" + System.nanoTime()));
        RagPipeline pipeline = newPipeline(dir, null);

        byte[] pngBytes = renderTestPng();
        RagDocument doc = pipeline.uploadDocument("tiny.png", pngBytes);
        check(doc != null, "image upload without OCR extractor still returns document");
        pipeline.close();
    }

    /**
     * 超过 1MB 的未知格式（.bin）无匹配提取器：跳过 UTF-8 解码避免 OOM。
     *
     * @throws Exception 临时目录创建或文件读写失败时抛出
     */
    static void testLargeBinaryWithoutExtractorSkipped() throws Exception {
        Path dir = Files.createDirectories(Paths.get(TEST_OUTPUT_ROOT, "large-bin-" + System.nanoTime()));
        RagPipeline pipeline = newPipeline(dir, null);

        byte[] bigData = new byte[2 * 1024 * 1024];
        for (int i = 0; i < bigData.length; i++) {
            bigData[i] = (byte) (i % 251);
        }
        // .bin 无匹配提取器且 > 1MB → extractText 返回 EMPTY → 分块为空 → FAILED
        RagDocument doc = pipeline.uploadDocument("big.bin", bigData);
        check(doc != null, "large binary upload returns document");
        check("FAILED".equals(doc.status()) || doc.chunkCount() == 0,
                "large binary without extractor skipped, status=" + doc.status() + " chunks=" + doc.chunkCount());
        pipeline.close();
    }

    /**
     * SentenceTextSplitter 防死循环回归：
     * 高频句界字符乱码 + 任意 overlap，分块必须有限且 startOffset 单调。
     */
    static void testSplitterNoInfiniteLoopOnRandomText() {
        // 模拟 PNG 乱码：高频 "\n" + "\n\n" 交替，触发句界紧贴 cursor 的边界场景
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            if (i % 3 == 0) {
                sb.append("\n\n");
            } else if (i % 3 == 1) {
                sb.append("\n");
            } else {
                sb.append((char) ('A' + i % 26));
            }
        }
        String text = sb.toString();

        SentenceTextSplitter splitter = new SentenceTextSplitter(100, 20);
        List<TextChunk> chunks = splitter.split(text);
        check(chunks.size() > 0, "splitter produces chunks, count=" + chunks.size());
        // 所有 chunk 的 startOffset 必须单调不减（防死循环/倒退回归）
        for (int i = 1; i < chunks.size(); i++) {
            check(chunks.get(i).startOffset() >= chunks.get(i - 1).startOffset(),
                    "chunk startOffset monotonic at i=" + i);
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────

    /**
     * 渲染测试用 PNG 图片（白底黑字 INVOICE 2024），不依赖外部图片文件。
     *
     * @return PNG 图片字节数组
     * @throws Exception 图片编码失败时抛出
     */
    static byte[] renderTestPng() throws Exception {
        BufferedImage img = new BufferedImage(400, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 120);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.drawString("INVOICE 2024", 20, 70);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /**
     * 构建内存组件管道（不依赖真实模型与外部服务）。
     *
     * @param dir           上传目录
     * @param textExtractor 图片 OCR 提取器，可为 null（验证降级路径）
     * @return 配置完成的 RagPipeline 实例
     */
    static RagPipeline newPipeline(Path dir, TextExtractor textExtractor) {
        MemoryVectorStorage storage = new MemoryVectorStorage(1536, VectorCompareAlgorithm.cosine());
        EmbeddingClient emb = EmbeddingClient.create(EmbeddingClientSetting.builder().provider("memory").build())
                .dimensions(1536);
        ChatClient chat = new MemoryChatClient();
        return RagPipeline.builder()
                .chatClient(chat)
                .embeddingClient(emb)
                .textExtractor(textExtractor)
                .textSplitter(new SentenceTextSplitter(100, 20))
                .vectorStorage(storage)
                .uploadDir(dir.toString())
                .topK(5)
                .similarityThreshold(0.0)
                .build();
    }

    /**
     * 断言并计数。
     *
     * @param ok      断言条件
     * @param message 断言说明（[PASS]/[FAIL] 输出内容）
     */
    static void check(boolean ok, String message) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + message);
        } else {
            failed++;
            System.out.println("[FAIL] " + message);
        }
    }
}
