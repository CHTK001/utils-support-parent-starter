package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * PDF 预览提供者：返回内嵌 PDF.js 的 HTML+JS 页面，浏览器无需插件即可渲染 PDF。
 *
 * <p>PDF 字节以 base64 嵌入 HTML，适合中小文件（&lt;10MB）。
 * 大文件场景建议配合分块加载。</p>
 *
 * <p>PDF.js 主库与 Worker 由宿主服务从同源路径 {@code /preview-vendor/pdf/}
 * 提供，不依赖公网 CDN，保证内网部署可用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-pdf")
public class PdfViewerPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 本地预览资源根路径（由宿主服务以 classpath:/static/preview-vendor 同源提供，避免依赖公网 CDN）
     */
    private static final String VENDOR_BASE = "/preview-vendor/pdf/";

    /**
     * PDF.js 主库（UMD）
     */
    private static final String PDF_JS = VENDOR_BASE + "pdf.min.js";

    /**
     * PDF.js  Worker 脚本（UMD）
     */
    private static final String PDF_WORKER_JS = VENDOR_BASE + "pdf.worker.min.js";

    /**
     * Base64 内嵌 PDF 预览允许的最大字节数（约 10 MB，避免生成超大 HTML 页面拖垮浏览器）
     */
    private static final long MAX_PDF_PREVIEW_BYTES = 10L * 1024 * 1024;

    @Override
    /**
     * 支持
    */
    public boolean supports(String extension, String mimeType) {
        return "pdf".equalsIgnoreCase(extension)
                || "application/pdf".equals(mimeType);
    }

    @Override
    /**
     * Preview
    */
    public PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException {
        if (content.length > MAX_PDF_PREVIEW_BYTES) {
            return PreviewResult.builder()
                    .htmlContent("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"></head>"
                            + "<body style=\"display:flex;justify-content:center;align-items:center;"
                            + "min-height:100vh;font-family:sans-serif;color:#666\">"
                            + "<div>PDF 文件过大（超过 10 MB），暂不支持内嵌预览，请下载后查看</div>"
                            + "</body></html>")
                    .build();
        }
        String b64 = Base64.getEncoder().encodeToString(content);
        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>PDF 预览</title>"
                + "<style>"
                + "*{margin:0;padding:0;box-sizing:border-box}"
                + "body{background:#525659;font-family:sans-serif}"
                + "#pdf-container{width:100%;min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:8px 0}"
                + ".page{background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.3);margin:4px auto;"
                + "min-height:600px;width:100%;max-width:816px;padding:48px 56px;position:relative}"
                + ".page canvas{width:100%!important;height:auto!important}"
                + ".loading{position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);color:#fff;font-size:18px}"
                + "</style></head><body>"
                + "<div id=\"pdf-container\"><div class=\"loading\">正在加载 PDF...</div></div>"
                + "<script src=\"" + PDF_JS + "\"></script>"
                + "<script>"
                + "pdfjsLib.GlobalWorkerOptions.workerSrc='" + PDF_WORKER_JS + "';"
                + "const pdfData=atob('" + b64 + "'.replace(/\\s/g,''));"
                + "const loadingTask=pdfjsLib.getDocument({data:pdfData});"
                + "loadingTask.promise.then(function(pdf){"
                + "var container=document.getElementById('pdf-container');"
                + "container.innerHTML='';"
                + "for(var i=1;i<=pdf.numPages;i++){"
                + "(function(pageNum){"
                + "pdf.getPage(pageNum).then(function(page){"
                + "var viewport=page.getViewport({scale:1.5});"
                + "var pageDiv=document.createElement('div');pageDiv.className='page';"
                + "var canvas=document.createElement('canvas');canvas.height=viewport.height;canvas.width=viewport.width;"
                + "var ctx=canvas.getContext('2d');"
                + "page.render({canvasContext:ctx,viewport:viewport});"
                + "pageDiv.appendChild(canvas);container.appendChild(pageDiv);"
                + "})"
                + "})(i)}"
                + "});"
                + "</script></body></html>";

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }
}
