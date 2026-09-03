package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Photoshop (PSD) 图片预览提供器。
 * <p>SPI 类型：{@code preview-psd}。通过 TwelveMonkeys ImageIO 插件解码 PSD，
 * 转为 PNG 并以 data URI 内嵌展示。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-psd")
public class PsdPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("psd", "psb");
    private static final long MAX_FILE_SIZE = 128L * 1024 * 1024;

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(emptyHtml("图片为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(emptyHtml("图片过大（超过 128 MB），暂不支持预览")).build();
        }
        BufferedImage image;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            image = ImageIO.read(iis);
        }
        if (image == null) {
            return PreviewResult.builder().htmlContent(emptyHtml("PSD 解码失败，可能已损坏或格式不受支持")).build();
        }
        byte[] png = toPng(image);
        String base64 = Base64.getEncoder().encodeToString(png);
        int width = image.getWidth();
        int height = image.getHeight();

        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
                + "body{margin:0;background:#1a1a1a;color:#d4d4d4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                + ".header{padding:16px 24px;background:#fff;color:#1a1a2e;border-bottom:1px solid #e5e7eb}"
                + ".header h1{margin:0;font-size:16px;font-weight:600}"
                + ".header .meta{font-size:13px;color:#6b7280;margin-top:4px}"
                + ".canvas{display:flex;justify-content:center;align-items:flex-start;padding:24px;min-height:90vh}"
                + "img{max-width:100%;max-height:85vh;box-shadow:0 10px 30px rgba(0,0,0,.5);border-radius:4px}"
                + "</style></head><body>"
                + "<div class=\"header\"><h1>PSD 图片预览</h1><div class=\"meta\">"
                + width + " × " + height + " px · "
                + formatSize(png.length) + "</div></div>"
                + "<div class=\"canvas\"><img src=\"data:image/png;base64," + base64 + "\" alt=\"psd preview\"></div>"
                + "</body></html>";
        return PreviewResult.builder().htmlContent(html).build();
    }

    /**
     * 将 BufferedImage 编码为 PNG 字节。
     *
     * @param image 源图像
     * @return PNG 字节
     * @throws IOException 编码失败时抛出
     */
    private byte[] toPng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("PNG 编码器不可用");
            }
            return out.toByteArray();
        }
    }

    /**
     * 构建空结果页面。
     *
     * @param message 提示信息
     * @return 完整 HTML
     */
    private String emptyHtml(String message) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#666;display:flex;justify-content:center;align-items:center;min-height:100vh;font-family:sans-serif}"
                + ".box{text-align:center;padding:40px;border:2px dashed #ddd;border-radius:12px}"
                + "</style></head><body><div class=\"box\">" + escape(message) + "</div></body></html>";
    }

    /**
     * 格式化字节大小。
     *
     * @param bytes 字节数
     * @return 可读大小
     */
    private String formatSize(long bytes) {
        return com.chua.common.support.utils.FileUtils.readableFileSize(bytes);
    }

    /**
     * HTML 最小转义。
     *
     * @param text 原始文本
     * @return 转义后文本
     */
    private String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}