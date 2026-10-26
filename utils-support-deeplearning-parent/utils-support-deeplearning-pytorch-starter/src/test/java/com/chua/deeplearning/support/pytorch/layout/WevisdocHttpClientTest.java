package com.chua.deeplearning.support.pytorch.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WevisdocHttpClient 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WevisdocHttpClientTest {

    @Test
    void testParseMarkdownSimple() {
        String json = "{\"markdown\":\"# Title\\n\\nSome text\",\"elapsed_ms\":12345}";
        String md = WevisdocHttpClient.parseMarkdown(json);
        assertEquals("# Title\n\nSome text", md);
    }

    @Test
    void testParseMarkdownWithQuotes() {
        String json = "{\"markdown\":\"# Heading\\n\\n\\\"quoted text\\\"\\n\\n- item 1\\n- item 2\",\"elapsed_ms\":5000}";
        String md = WevisdocHttpClient.parseMarkdown(json);
        assertTrue(md.contains("# Heading"));
        assertTrue(md.contains("item 1"));
    }

    @Test
    void testParseMarkdownWithHtml() {
        String json = "{\"markdown\":\"<table><tr><td>A</td></tr></table>\\n\\nSome text\",\"elapsed_ms\":100}";
        String md = WevisdocHttpClient.parseMarkdown(json);
        assertTrue(md.contains("<table>"));
        assertTrue(md.contains("Some text"));
    }

    @Test
    void testParseMarkdownWithLatex() {
        String json = "{\"markdown\":\"The formula is $E=mc^2$\\n\\n$$\\\\int_0^1 x dx$$\",\"elapsed_ms\":200}";
        String md = WevisdocHttpClient.parseMarkdown(json);
        assertTrue(md.contains("E=mc^2"));
    }

    @Test
    void testParseMarkdownEmpty() {
        String json = "{\"markdown\":\"\",\"elapsed_ms\":0}";
        String md = WevisdocHttpClient.parseMarkdown(json);
        assertEquals("", md);
    }

    @Test
    void testParseMarkdownEscapedNewlines() {
        String json = "{\"markdown\":\"Line1\\\\nLine2\\\\n\\\\n# Header\\\\n\\\\nText\",\"elapsed_ms\":100}";
        String md = WevisdocHttpClient.parseMarkdown(json);
        assertTrue(md.contains("Line1"));
        assertTrue(md.contains("Header"));
    }

    @Test
    void testConstructorDefault() {
        WevisdocHttpClient client = new WevisdocHttpClient();
        assertNotNull(client);
    }

    @Test
    void testConstructorWithUrl() {
        WevisdocHttpClient client = new WevisdocHttpClient("http://localhost:9999");
        assertNotNull(client);
    }

    @Test
    void testIsReadyNotRunning() {
        // 服务未运行时应返回 false（不抛异常）
        WevisdocHttpClient client = new WevisdocHttpClient("http://localhost:9999");
        boolean ready = client.isReady();
        assertFalse(ready);
    }
}
