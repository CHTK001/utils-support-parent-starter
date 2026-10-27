package com.chua.deeplearning.support.pytorch.layout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WevisdocLayoutBridge 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WevisdocLayoutBridgeTest {

    @Test
    void testParseMarkdownElements() {
        String md = "# Title\n\nSome text here.\n\n- item 1\n- item 2\n";
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(md);
        assertFalse(elements.isEmpty());
        assertEquals("header", elements.get(0).type);
        assertEquals("paragraph_title", elements.get(0).label);
    }

    @Test
    void testParseTableElement() {
        String md = "| Name | Value |\n|------|-------|\n| A | 1 |\n";
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(md);
        assertTrue(elements.stream().anyMatch(e -> "table".equals(e.type) && "table".equals(e.label)));
    }

    @Test
    void testParseListElements() {
        String md = "- item one\n- item two\n- item three\n";
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(md);
        assertTrue(elements.stream().allMatch(e -> "list".equals(e.type)));
        assertEquals(3, elements.size());
    }

    @Test
    void testParseFigureElement() {
        String md = "![figure](image.png)\n";
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(md);
        assertTrue(elements.stream().anyMatch(e -> "figure".equals(e.type)));
    }

    @Test
    void testParseEquationElement() {
        String md = "$$E=mc^2$$\n";
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(md);
        assertTrue(elements.stream().anyMatch(e -> "equation".equals(e.type)));
    }

    @Test
    void testParseEmptyMarkdown() {
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements("");
        assertTrue(elements.isEmpty());
    }

    @Test
    void testParseNullMarkdown() {
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(null);
        assertTrue(elements.isEmpty());
    }

    @Test
    void testMergeAdjacent() {
        String md = "- item 1\n- item 2\n- item 3\n";
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(md);
        assertEquals(3, elements.size());

        List<WevisdocLayoutBridge.MarkdownElement> merged =
                WevisdocLayoutBridge.mergeAdjacent(elements);
        assertEquals(1, merged.size());
        assertEquals("list", merged.get(0).type);
    }

    @Test
    void testMergeAdjacentMixed() {
        String md = "- item 1\n- item 2\nSome text\n- item 3\n- item 4\n";
        List<WevisdocLayoutBridge.MarkdownElement> elements =
                WevisdocLayoutBridge.parseMarkdownElements(md);
        List<WevisdocLayoutBridge.MarkdownElement> merged =
                WevisdocLayoutBridge.mergeAdjacent(elements);
        // list + text + list = 3 merged groups
        assertEquals(3, merged.size());
    }

    @Test
    void testMatchElements() {
        // 2 个 Markdown 元素
        List<WevisdocLayoutBridge.MarkdownElement> elements = List.of(
                new WevisdocLayoutBridge.MarkdownElement("header", "Introduction", 0),
                new WevisdocLayoutBridge.MarkdownElement("text", "Some content", 1)
        );

        // 3 个 OCR 块
        List<WevisdocLayoutBridge.OcrBlock> ocrBlocks = List.of(
                new WevisdocLayoutBridge.OcrBlock("Introduction", 10, 10, 100, 30, 0.95f),
                new WevisdocLayoutBridge.OcrBlock("Some content here", 10, 40, 100, 60, 0.90f),
                new WevisdocLayoutBridge.OcrBlock("Unmatched", 200, 200, 300, 220, 0.80f)
        );

        List<WevisdocLayoutBridge.OcrBlock> results =
                WevisdocLayoutBridge.matchElements(elements, ocrBlocks, 0.5f);

        assertEquals(2, results.size());
        assertNotNull(results.get(0)); // "Introduction" should match
        assertNotNull(results.get(1)); // "Some content" should match
    }

    @Test
    void testOcrBlockIou() {
        WevisdocLayoutBridge.OcrBlock a = new WevisdocLayoutBridge.OcrBlock("A", 0, 0, 10, 10, 1.0f);
        WevisdocLayoutBridge.OcrBlock b = new WevisdocLayoutBridge.OcrBlock("B", 5, 5, 15, 15, 1.0f);
        float iou = a.iou(b);
        assertTrue(iou > 0 && iou < 1);
    }

    @Test
    void testOcrBlockMerge() {
        WevisdocLayoutBridge.OcrBlock a = new WevisdocLayoutBridge.OcrBlock("A", 0, 0, 10, 10, 1.0f);
        WevisdocLayoutBridge.OcrBlock b = new WevisdocLayoutBridge.OcrBlock("B", 20, 20, 30, 30, 1.0f);
        a.merge(b);
        assertEquals(0, a.x1);
        assertEquals(0, a.y1);
        assertEquals(30, a.x2);
        assertEquals(30, a.y2);
    }
}
