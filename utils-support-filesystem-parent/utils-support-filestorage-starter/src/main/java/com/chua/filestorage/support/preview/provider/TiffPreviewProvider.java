package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TIFF/多页 TIFF 图像预览提供器。
 *
 * <p>SPI 类型：{@code preview-tiff}。浏览器**不原生支持 TIFF**（Chrome/Firefox 均无法直接显示），
 * 因此扫描件、传真、办公传送件里最常见的 {@code .tif} / {@code .tiff} 过去只能落到
 * 「暂不支持预览」提示页。本提供器用 JDK 自带的 ImageIO TIFF 插件在**服务端解码**，
 * 逐页转成 PNG 后以 data URI 内嵌，浏览器即可正常显示。</p>
 *
 * <p>三个刻意的边界（都是实测踩出来的）：</p>
 * <ul>
 *   <li><b>多页</b>：TIFF 常有多页（传真、扫描批量）。只读第一页会静默丢内容，
 *       故逐页解码并提供页导航。</li>
 *   <li><b>防 OOM</b>：单页像素数与页数都要设上限。一张 30000×30000 的 TIFF 展开后
 *       是 3.6 GB，直接 {@code ImageIO.read} 会把应用拖死；超限时明确告知而不是硬解。</li>
 *   <li><b>失败要给可执行建议</b>：解码失败时说明「文件可能损坏 / 是压缩变体（如 JPEG-in-TIFF）」
 *       并提示重新上传或本地打开，而不是一句「暂不支持」把用户堵死。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-tiff")
public class TiffPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的扩展名（小写）。
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of("tif", "tiff");

    /**
     * 允许解码的最大文件字节数（超过则不解码，避免把内存拖死）。
     */
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;

    /**
     * 单页最大像素数（宽 × 高）。30000×30000 展开是 3.6 GB，必须挡在解码之前。
     */
    private static final long MAX_PIXELS_PER_PAGE = 40_000_000L;

    /**
     * 最多转码的页数（超出只展示前 N 页并如实告知总页数）。
     */
    private static final int MAX_PAGES = 50;

    /**
     * 全部页转 PNG 后的总字节上限（data URI 会放大 4/3，故留出余量）。
     */
    private static final long MAX_TOTAL_PNG_BYTES = 32L * 1024 * 1024;

    /**
     * 判断是否支持指定扩展名的文件预览。
     *
     * @param ext  文件扩展名
     * @param mime MIME 类型（当前忽略）
     * @return true 表示支持 TIFF 预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 生成 TIFF 预览页面。
     *
     * @param content 原始字节
     * @param ext     扩展名
     * @param mime    MIME 类型（当前忽略）
     * @return 预览结果，含内嵌 PNG 的 HTML
     * @throws IOException 读取流失败时抛出
     */
    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content == null || content.length == 0) {
            return PreviewResult.builder().htmlContent(messageHtml("文件为空", "该 TIFF 文件大小为 0 字节，请重新上传。")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder()
                    .htmlContent(messageHtml("文件过大", "该 TIFF 超过 " + formatSize(MAX_FILE_SIZE) + "，为避免服务端内存占用过高，暂不在线解码，请下载后本地查看。"))
                    .build();
        }

        List<String> pages = Wraps.list();
        List<Integer> widths = Wraps.list();
        List<Integer> heights = Wraps.list();
        int total;
        long totalPng = 0;
        String pageError = null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return PreviewResult.builder()
                        .htmlContent(messageHtml("无法解码该 TIFF",
                                "服务端 ImageIO 没有可用的 TIFF 解码器，或该文件其实不是 TIFF（例如只是把别的格式改了扩展名）。"
                                        + "请确认文件内容，或下载后用图片查看器打开。"))
                        .build();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false, true);
                total = safePageCount(reader);
                int limit = Math.min(total, MAX_PAGES);
                for (int i = 0; i < limit; i++) {
                    BufferedImage image;
                    try {
                        // 先取尺寸做上限判断，再真正解码：ImageIO 读取前不可能知道像素数，
                        // 但 getWidth/getHeight 通常只解析头信息，比整图解码便宜得多。
                        int w = reader.getWidth(i);
                        int h = reader.getHeight(i);
                        if ((long) w * h > MAX_PIXELS_PER_PAGE) {
                            pageError = "第 " + (i + 1) + " 页为 " + w + " × " + h
                                    + "，超过单页 " + MAX_PIXELS_PER_PAGE + " 像素的解码上限，已跳过。";
                            break;
                        }
                        image = reader.read(i);
                    } catch (IOException | RuntimeException ex) {
                        pageError = "第 " + (i + 1) + " 页解码失败：" + brief(ex);
                        break;
                    }
                    if (image == null) {
                        pageError = "第 " + (i + 1) + " 页解码返回空。";
                        break;
                    }
                    byte[] png = toPng(image);
                    totalPng += png.length;
                    if (totalPng > MAX_TOTAL_PNG_BYTES) {
                        pageError = "前 " + pages.size() + " 页转码后已超过 " + formatSize(MAX_TOTAL_PNG_BYTES) + "，后续页未转码。";
                        break;
                    }
                    pages.add(Base64.getEncoder().encodeToString(png));
                    widths.add(image.getWidth());
                    heights.add(image.getHeight());
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            return PreviewResult.builder()
                    .htmlContent(messageHtml("TIFF 读取失败", "读取过程中出错：" + brief(ex) + "。文件可能已损坏，请重新上传。"))
                    .build();
        }

        if (pages.isEmpty()) {
            return PreviewResult.builder()
                    .htmlContent(messageHtml("TIFF 解码失败",
                            (pageError == null ? "" : pageError + " ") + "文件可能已损坏，或使用了在线预览不支持的压缩变体，请下载后用图片查看器打开。"))
                    .build();
        }
        return PreviewResult.builder().htmlContent(buildHtml(pages, widths, heights, total, pageError)).build();
    }

    /**
     * 安全地取页数，读取失败时退化为 1 页而不是抛异常中断整个预览。
     *
     * @param reader 已 setInput 的 ImageReader
     * @return 页数（至少 1）
     */
    private int safePageCount(ImageReader reader) {
        try {
            int n = reader.getNumImages(true);
            return n > 0 ? n : 1;
        } catch (IOException | RuntimeException ex) {
            return 1;
        }
    }

    /**
     * 构建预览 HTML。
     *
     * <p>注意：这里所有状态都通过参数传入，**不用实例字段**。SPI 提供器是单例，
     * 实例字段在并发预览下会互相覆盖（不同请求的页尺寸会串）。</p>
     *
     * @param pages     各页 PNG 的 base64
     * @param widths    各页宽（与 pages 同序）
     * @param heights   各页高（与 pages 同序）
     * @param total     文件实际总页数
     * @param pageError 转码过程中的告警（可为 null）
     * @return 完整 HTML
     */
    private String buildHtml(List<String> pages, List<Integer> widths, List<Integer> heights, int total, String pageError) {
        StringBuilder sb = new StringBuilder(1024 + pages.size() * 4096);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("html,body{margin:0;height:100%}")
                .append("body{background:#f5f7fa;color:#303133;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;display:flex;flex-direction:column}")
                .append(".hdr{padding:10px 16px;background:#fff;border-bottom:1px solid #e4e7ed;flex-shrink:0}")
                .append(".hdr h1{margin:0;font-size:15px;font-weight:600}")
                .append(".hdr .meta{font-size:12px;color:#909399;margin-top:3px}")
                .append(".warn{margin-top:6px;font-size:12px;color:#b88230;background:#fdf6ec;border:1px solid #f5dab1;border-radius:4px;padding:5px 8px}")
                .append(".stage{flex:1;overflow:auto;display:flex;align-items:flex-start;justify-content:center;padding:16px}")
                .append(".stage img{max-width:100%;height:auto;background:#fff;box-shadow:0 1px 6px rgba(0,0,0,.10);border:1px solid #e4e7ed;border-radius:3px}")
                .append(".pages{flex-shrink:0;display:flex;gap:6px;overflow-x:auto;padding:8px 16px;background:#fff;border-top:1px solid #e4e7ed}")
                .append(".pages button{min-width:34px;height:28px;border:1px solid #dcdfe6;background:#fff;border-radius:4px;cursor:pointer;font-size:12px;color:#606266}")
                .append(".pages button.on{background:#409eff;border-color:#409eff;color:#fff}")
                .append("</style></head><body>");

        sb.append("<div class=\"hdr\"><h1>TIFF 图像预览</h1><div class=\"meta\">")
                .append(pages.size()).append(" / ").append(total).append(" 页");
        if (!widths.isEmpty()) {
            sb.append(" · 首页 ").append(widths.get(0)).append(" × ").append(heights.get(0)).append(" px");
        }
        sb.append("</div>");
        if (pageError != null) {
            sb.append("<div class=\"warn\">").append(escapeHtml(pageError)).append("</div>");
        }
        if (total > pages.size()) {
            sb.append("<div class=\"warn\">该文件共 ").append(total).append(" 页，本页仅展示前 ").append(pages.size()).append(" 页。</div>");
        }
        sb.append("</div>");

        sb.append("<div class=\"stage\"><img id=\"v\" alt=\"tiff page\" src=\"data:image/png;base64,")
                .append(pages.get(0)).append("\"></div>");

        if (pages.size() > 1) {
            sb.append("<div class=\"pages\">");
            for (int i = 0; i < pages.size(); i++) {
                sb.append("<button class=\"").append(i == 0 ? "on" : "").append("\" data-i=\"").append(i).append("\">")
                        .append(i + 1).append("</button>");
            }
            sb.append("</div>");
        }

        sb.append("<script>var P=[");
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("'data:image/png;base64,").append(pages.get(i)).append("'");
        }
        sb.append("];var v=document.getElementById('v');var bs=document.querySelectorAll('.pages button');")
                .append("for(var i=0;i<bs.length;i++){bs[i].onclick=function(){var k=+this.getAttribute('data-i');v.src=P[k];")
                .append("for(var j=0;j<bs.length;j++){bs[j].className=(j===k?'on':'');}};}")
                .append("</script></body></html>");
        return sb.toString();
    }

    /**
     * 构建一条明确的说明页（用于无法解码/超限等情况）。
     *
     * @param title 标题
     * @param detail 具体说明
     * @return HTML
     */
    private String messageHtml(String title, String detail) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><style>"
                + "html,body{margin:0;height:100%}body{display:flex;align-items:center;justify-content:center;"
                + "background:#f5f7fa;color:#303133;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                + ".box{max-width:520px;padding:24px 28px;background:#fff;border:1px solid #e4e7ed;border-radius:6px;text-align:center}"
                + ".box h1{margin:0 0 10px;font-size:16px}.box p{margin:0;font-size:13px;line-height:1.7;color:#606266}"
                + "</style></head><body><div class=\"box\"><h1>" + escapeHtml(title) + "</h1><p>"
                + escapeHtml(detail) + "</p></div></body></html>";
    }

    /**
     * BufferedImage 转 PNG 字节。
     *
     * @param image 源图
     * @return PNG 字节
     * @throws IOException 编码失败
     */
    private byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        if (!ImageIO.write(image, "PNG", bos)) {
            throw new IOException("PNG 编码器不可用");
        }
        return bos.toByteArray();
    }

    /**
     * 取异常的简短描述，避免把整段堆栈塞进页面。
     *
     * @param ex 异常
     * @return 简短描述
     */
    private String brief(Throwable ex) {
        String m = ex.getMessage();
        if (m == null || m.isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        m = m.replace('\n', ' ').replace('\r', ' ');
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }

    /**
     * 转义 HTML 文本。
     *
     * @param s 原文
     * @return 转义结果
     */
    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 人类可读的字节大小。
     *
     * @param bytes 字节数
     * @return 形如 {@code 1.2 MB}
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
