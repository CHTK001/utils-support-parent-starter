// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WevisdocMarkdownParser 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WevisdocMarkdownParserTest {

    @Test
    void testParseEmptyMarkdown() {
        int[] counts = WevisdocMarkdownParser.parse("");
        assertEquals(0, counts[0]); // text
        assertEquals(0, counts[1]); // paragraph_title
        assertEquals(11, counts.length);
    }

    @Test
    void testParseNullMarkdown() {
        int[] counts = WevisdocMarkdownParser.parse(null);
        assertEquals(0, counts[0]);
        assertEquals(11, counts.length);
    }

    @Test
    void testParseHeaders() {
        String md = "# Main Title\n## Section 1\n### Subsection\n";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertEquals(3, counts[1]); // paragraph_title
    }

    @Test
    void testParseTable() {
        String md = "| Name | Value |\n|------|-------|\n| A | 1 |\n| B | 2 |\n";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertEquals(1, counts[2]); // table
    }

    @Test
    void testParseList() {
        String md = "- item 1\n- item 2\n* item 3\n1. step 1\n2. step 2\n";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertEquals(5, counts[6]); // list
    }

    @Test
    void testParseFigure() {
        String md = "Here is an image:\n\n![figure 1](image.png)\n\nSome text.\n";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertEquals(1, counts[3]); // figure
    }

    @Test
    void testParseEquation() {
        String md = "The formula is $E=mc^2$ and display math:\n\n$$\\int_0^1 x dx$$\n";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertTrue(counts[10] >= 1); // equation
    }

    @Test
    void testParseHtmlTable() {
        String md = "<table><tr><td>A</td><td>1</td></tr></table>";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertEquals(1, counts[2]); // table
    }

    @Test
    void testParseHtmlFigureCaption() {
        String md = "<figure><img src=\"a.png\"><figcaption>Figure 1: test</figcaption></figure>";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertEquals(1, counts[4]); // figure_caption
    }

    @Test
    void testParseHtmlTableCaption() {
        String md = "<table><caption>Table 1: data</caption><tr><td>x</td></tr></table>";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertEquals(1, counts[5]); // table_caption
    }

    @Test
    void testParseText() {
        String md = "This is a normal paragraph of text.\nAnother line here.";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertTrue(counts[0] >= 1); // text
    }

    @Test
    void testParseMixedDocument() {
        String md = "# Title\n\n"
                + "Some intro text.\n\n"
                + "| Col1 | Col2 |\n|------|------|\n| A | B |\n\n"
                + "## Section\n\n"
                + "- list item 1\n- list item 2\n\n"
                + "![img](pic.png)\n\n"
                + "$$E=mc^2$$\n";
        int[] counts = WevisdocMarkdownParser.parse(md);
        assertTrue(counts[0] >= 1, "should have text");
        assertTrue(counts[1] >= 1, "should have headers");
        assertTrue(counts[2] >= 1, "should have table");
        assertTrue(counts[3] >= 1, "should have figure");
        assertTrue(counts[6] >= 1, "should have list");
        assertTrue(counts[10] >= 1, "should have equation");
    }

    @Test
    void testMapToLabel() {
        assertEquals("paragraph_title", WevisdocMarkdownParser.mapToLabel("header"));
        assertEquals("table", WevisdocMarkdownParser.mapToLabel("table"));
        assertEquals("list", WevisdocMarkdownParser.mapToLabel("list"));
        assertEquals("figure", WevisdocMarkdownParser.mapToLabel("figure"));
        assertEquals("equation", WevisdocMarkdownParser.mapToLabel("equation"));
        assertEquals("text", WevisdocMarkdownParser.mapToLabel("text"));
        assertEquals("text", WevisdocMarkdownParser.mapToLabel("unknown"));
    }
}
