// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.layout;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * WeVisDoc 远程翻译器：通过 HTTP 调用 Python 推理服务，将 Markdown 解析结果
 * 映射为 11 类版面框。
 * <p>
 * 管线：Image → HTTP → WeVisDoc (Python) → Markdown → 解析 → DetectedObjects
 * <p>
 * 边界框策略：根据 Markdown 元素数量将图像垂直等分为对应区域，
 * 每个区域对应一个版面元素。后续可结合 OCR 文字位置精确定位。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WevisdocRemoteTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 上下文附件 key（存储 Markdown 文本）。
     */
    private static final String MD_ATTACHMENT = "wevisdoc_markdown";

    /**
     * HTTP 客户端。
     */
    private final WevisdocHttpClient httpClient;

    /**
     * 默认构造器（localhost:8765）。
     */
    public WevisdocRemoteTranslator() {
        this(new WevisdocHttpClient());
    }

    /**
     * 构造器。
     *
     * @param httpClient HTTP 客户端
     */
    public WevisdocRemoteTranslator(WevisdocHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        // 调用 Python 服务获取 Markdown
        String markdown = httpClient.infer(input);

        // 存储到上下文附件
        ctx.setAttachment(MD_ATTACHMENT, markdown);

        // 返回空 NDList（远程推理不需要模型输入）
        return new NDList();
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        // 从上下文附件获取 Markdown
        Object mdObj = ctx.getAttachment(MD_ATTACHMENT);
        String markdown = (String) mdObj;
        if (markdown == null || markdown.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        // 解析 Markdown 元素
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(markdown);

        if (elements.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        // 生成近似边界框：按元素数量垂直等分图像
        int imgH = 1000; // 归一化高度
        int n = elements.size();
        int regionH = imgH / n;

        List<String> classNames = new ArrayList<>();
        List<BoundingBox> rects = new ArrayList<>();
        List<Double> probs = new ArrayList<>();

        for (int i = 0; i < elements.size(); i++) {
            WevisdocLayoutBridge.MarkdownElement elem = elements.get(i);

            // 垂直区域
            int y1 = i * regionH;
            int y2 = (i == n - 1) ? imgH : (i + 1) * regionH;

            // 文本截断（避免过长标签）
            String text = elem.text;
            if (text.length() > 50) {
                text = text.substring(0, 50) + "...";
            }

            Rectangle rect = new Rectangle(0, y1, 1000, y2 - y1);
            classNames.add(elem.label);
            rects.add(rect);
            probs.add(0.85); // 近似置信度
        }

        log.debug("WeVisDoc 远程翻译: {} 个版面元素", elements.size());
        return new DetectedObjects(classNames, probs, rects);
    }
}