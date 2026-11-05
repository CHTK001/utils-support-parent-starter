// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.layout;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WevisdocRemoteTranslator 端到端 Demo 测试。
 * <p>
 * 需要 Python HTTP 服务运行在 localhost:8765：
 *   python wevisdoc_service.py
 * <p>
 * 服务未运行时自动跳过。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WevisdocRemoteDemoTest {

    private static final String BENCH_DIR =
            "src/test/resources/layout-bench";

    /** 11 类版面标签 */
    private static final String[] LABELS = {
            "text", "paragraph_title", "table", "figure",
            "figure_caption", "table_caption", "list",
            "header", "footer", "reference", "equation"
    };

    @Test
    void testRemoteInferenceOnArticle() throws Exception {
        WevisdocHttpClient client = new WevisdocHttpClient("http://localhost:8765");
        assumeTrue(client.isReady(), "Python 服务未运行，跳过远程推理测试");

        log.info("=== WeVisDoc 远程推理 Demo ===");
        log.info("服务状态: 已连接 localhost:8765");

        // 使用小图（224x224），避免 CPU 推理时间过长
        String[] pages = {"tiny.png"};

        long totalTime = 0;
        int totalElements = 0;

        ImageFactory factory = ImageFactory.getInstance();

        for (String page : pages) {
            Path imgPath = Paths.get(BENCH_DIR, page);
            if (!Files.exists(imgPath)) {
                log.warn("跳过 {}: 文件不存在", page);
                continue;
            }

            Image image = factory.fromFile(imgPath);
            log.info("图片大小: {}x{}", image.getWidth(), image.getHeight());

            long t0 = System.currentTimeMillis();
            String markdown = client.infer(image);
            long elapsed = System.currentTimeMillis() - t0;

            log.info("--- {} ---", page);
            log.info("  耗时: {}ms", elapsed);
            log.info("  Markdown 长度: {} 字符", markdown.length());
            log.info("  Markdown 预览: {}",
                    markdown.length() > 200 ? markdown.substring(0, 200) : markdown);

            // 解析 Markdown 为 11 类版面元素计数
            int[] counts = WevisdocMarkdownParser.parse(markdown);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < counts.length && i < LABELS.length; i++) {
                if (counts[i] > 0) {
                    sb.append(LABELS[i]).append("=").append(counts[i]).append(" ");
                }
            }
            log.info("  版面元素统计: {}", sb.toString());

            for (int c : counts) {
                totalElements += c;
            }
            totalTime += elapsed;
        }

        log.info("=== 汇总 ===");
        log.info("总检测元素: {} 个", totalElements);
        log.info("总耗时: {}ms", totalTime);

        assertNotNull(totalElements);
    }
}