package com.chua.deeplearning.support.pytorch.layout;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WeVisDoc 版面桥接器：将 Markdown 结构 + OCR 文字位置映射为版面框。
 * <p>
 * WeVisDoc（Qwen3-VL-2B）输出结构化 Markdown（含 HTML 表格、LaTeX 公式），
 * 本类将 Markdown 元素与 OCR 文字位置匹配，产出 11 类版面框。
 * </p>
 * <p>
 * 管线：Image → WeVisDoc → Markdown（结构）+ OCR → 文字位置（坐标）
 * → 匹配 → 11 类版面框（DetectedObjects）
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WevisdocLayoutBridge {

    /**
     * Markdown 标题行：# ~ ###### + 空格 + 文本
     */
    private static final Pattern MARKDOWN_HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /**
     * Markdown 列表项：- / * / + / 数字序号 + 空格 + 文本
     */
    private static final Pattern MARKDOWN_LIST_PATTERN = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s+(.+)$");

    /**
     * Markdown 图片：![alt](url)
     */
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]+\\)");

    /**
     * Markdown 元素行：记录元素类型、文本内容、行号。
     */
    public static class MarkdownElement {
        public String type;        // header/table/list/figure/equation/text
        public String text;        // 元素文本内容
        public int line;           // 在 Markdown 中的行号
        public String label;       // 映射的版面标签

        public MarkdownElement(String type, String text, int line) {
            this.type = type;
            this.text = text;
            this.line = line;
            this.label = WevisdocMarkdownParser.mapToLabel(type);
        }
    }

    /**
     * OCR 文字块：记录文本内容、边界框坐标。
     */
    public static class OcrBlock {
        public String text;
        public float x1, y1, x2, y2;
        public float confidence;

        public OcrBlock(String text, float x1, float y1, float x2, float y2, float confidence) {
            this.text = text;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.confidence = confidence;
        }

        /**
         * 计算与另一个块的 IoU。
         */
        public float iou(OcrBlock other) {
            float ix1 = Math.max(x1, other.x1);
            float iy1 = Math.max(y1, other.y1);
            float ix2 = Math.min(x2, other.x2);
            float iy2 = Math.min(y2, other.y2);
            float iw = Math.max(0, ix2 - ix1);
            float ih = Math.max(0, iy2 - iy1);
            float inter = iw * ih;
            float union = (x2 - x1) * (y2 - y1) + (other.x2 - other.x1) * (other.y2 - other.y1) - inter;
            return union > 0 ? inter / union : 0;
        }

        /**
         * 合并另一个块的坐标（扩展当前块）。
         */
        public void merge(OcrBlock other) {
            this.x1 = Math.min(x1, other.x1);
            this.y1 = Math.min(y1, other.y1);
            this.x2 = Math.max(x2, other.x2);
            this.y2 = Math.max(y2, other.y2);
        }
    }

    /**
     * 解析 Markdown 为元素列表。
     *
     * @param markdown Markdown 文本
     * @return 元素列表
     */
    public static List<MarkdownElement> parseMarkdownElements(String markdown) {
        List<MarkdownElement> elements = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return elements;
        }

        String[] lines = markdown.split("\n");
        int lineNo = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                lineNo++;
                continue;
            }

            // 标题
            Matcher header = MARKDOWN_HEADER_PATTERN.matcher(trimmed);
            if (header.find()) {
                elements.add(new MarkdownElement("header", header.group(2), lineNo));
                lineNo++;
                continue;
            }

            // 表格行
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                elements.add(new MarkdownElement("table", trimmed, lineNo));
                lineNo++;
                continue;
            }

            // 列表项
            Matcher list = MARKDOWN_LIST_PATTERN.matcher(trimmed);
            if (list.find()) {
                elements.add(new MarkdownElement("list", list.group(1), lineNo));
                lineNo++;
                continue;
            }

            // 图片
            Matcher figure = MARKDOWN_IMAGE_PATTERN.matcher(trimmed);
            if (figure.find()) {
                elements.add(new MarkdownElement("figure", figure.group(1), lineNo));
                lineNo++;
                continue;
            }

            // 公式
            if (trimmed.startsWith("$$") || trimmed.startsWith("$")) {
                elements.add(new MarkdownElement("equation", trimmed, lineNo));
                lineNo++;
                continue;
            }

            // 普通文本
            elements.add(new MarkdownElement("text", trimmed, lineNo));
            lineNo++;
        }

        return elements;
    }

    /**
     * 将 Markdown 元素与 OCR 文字块匹配，产出版面框。
     *
     * @param elements Markdown 元素列表
     * @param ocrBlocks OCR 文字块列表
     * @param iouThreshold IoU 匹配阈值
     * @return 匹配结果：每个元素对应的 OCR 块（可能为 null）
     */
    public static List<OcrBlock> matchElements(
            List<MarkdownElement> elements,
            List<OcrBlock> ocrBlocks,
            float iouThreshold) {

        List<OcrBlock> results = new ArrayList<>();
        List<Boolean> used = new ArrayList<>();
        for (int i = 0; i < ocrBlocks.size(); i++) {
            used.add(false);
        }

        for (MarkdownElement elem : elements) {
            OcrBlock bestMatch = null;
            float bestScore = 0;

            for (int i = 0; i < ocrBlocks.size(); i++) {
                if (used.get(i)) {
                    continue;
                }
                OcrBlock block = ocrBlocks.get(i);
                // 文本相似度
                float textSim = textSimilarity(elem.text, block.text);
                // IoU（如果之前有合并块的近似坐标）
                float iou = 0; // 没有坐标信息时跳过 IoU
                float score = textSim;

                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = block;
                }
            }

            if (bestMatch != null && bestScore >= iouThreshold) {
                results.add(bestMatch);
                used.set(ocrBlocks.indexOf(bestMatch), true);
            } else {
                results.add(null);
            }
        }

        return results;
    }

    /**
     * 文本相似度（基于字符重叠的简单度量）。
     */
    private static float textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        String aLower = a.toLowerCase().trim();
        String bLower = b.toLowerCase().trim();

        // 精确匹配
        if (aLower.equals(bLower)) {
            return 1.0f;
        }

        // 包含关系
        if (aLower.contains(bLower) || bLower.contains(aLower)) {
            return 0.7f;
        }

        // 字符级 Jaccard
        java.util.Set<Character> setA = new java.util.HashSet<>();
        java.util.Set<Character> setB = new java.util.HashSet<>();
        for (char c : aLower.toCharArray()) {
            setA.add(c);
        }
        for (char c : bLower.toCharArray()) {
            setB.add(c);
        }
        java.util.Set<Character> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);
        java.util.Set<Character> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0 : intersection.size() / (float) union.size();
    }

    /**
     * 合并相邻同类型元素（如列表项合并为一个 list 块）。
     *
     * @param elements 元素列表
     * @return 合并后的元素列表
     */
    public static List<MarkdownElement> mergeAdjacent(List<MarkdownElement> elements) {
        List<MarkdownElement> merged = new ArrayList<>();
        if (elements.isEmpty()) {
            return merged;
        }

        MarkdownElement current = elements.get(0);
        for (int i = 1; i < elements.size(); i++) {
            MarkdownElement next = elements.get(i);
            if (current.type.equals(next.type)) {
                // 同类型相邻元素合并
                current = new MarkdownElement(
                        current.type,
                        current.text + " " + next.text,
                        current.line
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
