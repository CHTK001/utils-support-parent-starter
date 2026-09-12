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
* @author CH
* @since 4.0.0.42
 */
@Spi("preview-pdf")
public class PdfViewerPreviewProvider implements FileStoragePreviewProvider {

    @Override
    /** 支持 */
    public boolean supports(String extension, String mimeType) {
        return "pdf".equalsIgnoreCase(extension)
                || "application/pdf".equals(mimeType);
    }

    @Override
    /** Preview */
    public PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException {
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
                + "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js\"></script>"
                + "<script>"
                + "pdfjsLib.GlobalWorkerOptions.workerSrc='https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';"
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
