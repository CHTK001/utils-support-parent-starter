package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 纯文本预览提供者：txt → HTML &lt;pre&gt; 包装。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-text")
public class TextPreviewProvider implements FileStoragePreviewProvider {

    @Override
    /** 支持 */
    public boolean supports(String extension, String mimeType) {
        return "txt".equalsIgnoreCase(extension)
                || "text/plain".equals(mimeType);
    }

    @Override
    /** Preview */
    public PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException {
        String text = new String(content, StandardCharsets.UTF_8);
        String css = "body{margin:0;padding:16px;font-family:'Georgia',serif;line-height:1.8;color:#333;max-width:720px;margin:0 auto}"
                + "pre{white-space:pre-wrap;word-wrap:break-word;font-family:inherit;margin:0}";
        String html = "<pre>" + escapeHtml(text) + "</pre>";
        return PreviewResult.builder().htmlContent(html).embeddedCss(css).build();
    }

    /**
     * escapehtml
     *
     * @param s s
     * @return escapeHtml的结果
     */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
