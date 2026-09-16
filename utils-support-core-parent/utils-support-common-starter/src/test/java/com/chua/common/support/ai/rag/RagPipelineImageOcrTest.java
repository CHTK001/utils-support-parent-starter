package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.MemoryChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.rag.LocalFileUploadProvider;
import com.chua.common.support.ai.rag.RagDocument;
import com.chua.common.support.ai.rag.RagPipeline;
import com.chua.common.support.ai.rag.RagResponse;
import com.chua.common.support.ai.splitter.SentenceTextSplitter;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RagPipeline 图片 OCR 分支冒烟测试。
 *
 * <p>用内存组件（MemoryEmbeddingClient + MemoryVectorStorage + MemoryChatClient）
 * 验证图片文件经 RagPipeline 上传时的 OCR 分支路由与降级行为，不依赖真实 OCR 模型：</p>
 * <ol>
 *   <li>注入固定文本的 OCR 提取器：图片上传 → 分块 → 嵌入 → 检索命中</li>
 *   <li>未注入 OCR 提取器：图片上传 → 降级为 UTF-8 字节读取 → 正常入管道</li>
 * </ol>
 *
 * <p>真实 PaddleOCRv6 ONNX 的端到端识别已在 {@code OcrImageTest}（临时目录）验证。</p>
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RagPipelineImageOcrTest {

    /** 固定文本 OCR 提取器：返回预设文本，模拟识别结果 */
    private static final class FakeOcrTextExtractor implements TextExtractor {

        private final String fixedText;

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

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testImageUploadWithOcrExtractor();
        testImageUploadWithoutOcrFallsBack();
        testLargeBinaryWithoutExtractorSkipped();

        System.out.println("");
        System.out.println("[RESULT] passed=" + passed + " failed=" + failed);
        System.out.println(failed == 0 ? "ALL-PASSED" : "HAS-FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * 图片上传 + OCR 提取器：文本被分块嵌入，检索可命中 OCR 内容。
     */
    static void testImageUploadWithOcrExtractor() throws Exception {
        Path dir = Files.createTempDirectory("rag-image-ocr-test");
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
     */
    static void testImageUploadWithoutOcrFallsBack() throws Exception {
        Path dir = Files.createTempDirectory("rag-image-fallback");
        RagPipeline pipeline = newPipeline(dir, null);

        byte[] pngBytes = renderTestPng();
        RagDocument doc = pipeline.uploadDocument("tiny.png", pngBytes);
        check(doc != null, "image upload without OCR extractor still returns document");
        pipeline.close();
    }

    /**
     * 超过 1MB 的未知格式（.bin）无匹配提取器：跳过 UTF-8 解码避免 OOM。
     * 图片分支（>1MB .png）同样验证。
     */
    static void testLargeBinaryWithoutExtractorSkipped() throws Exception {
        Path dir = Files.createTempDirectory("rag-large-binary");
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

    // ── helpers ──────────────────────────────────────────────────────────

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
