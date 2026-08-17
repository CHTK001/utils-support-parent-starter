package com.chua.filestorage.support.filter;

import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.file.converter.ConvertSupport;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.cache.PreviewPdfCache;
import com.chua.filestorage.support.operation.FileOperationSetting;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import com.chua.filestorage.support.setting.FileStorageSetting;
import com.chua.filestorage.support.utils.MimeTypeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 文件存储预览过滤器。
 *
 * <p>拦截 {@code ?preview} flag 形式的请求。
 * 文件路径从 URL path 解析：{@code /{bucket}/{filepath}?preview&size=200x200}。
 * 当 {@link FileStorageSetting#isOpenPreview()} 为 false 时拒绝。</p>
 *
 * <p>处理优先级：</p>
 * <ol>
 *   <li>图片 / 音视频 —— 浏览器原生直接返回（可选应用滤镜）</li>
 *   <li>SPI 预览提供者 —— MD、CSV、代码、PDF 等转为 HTML+JS</li>
 *   <li>PDF 转换 —— Office 文档通过 {@link ConvertSupport} 转 PDF，经 PDF.js 渲染</li>
 *   <li>前端插件 —— Office 等返回 {@code X-FileStorage-Preview-Plugin} 头</li>
 *   <li>无法支持 —— 415</li>
 * </ol>
 *
 * @author CH
 * @since 2024/12/28
 */
@Slf4j
public class FileStorageViewServerFilter extends AbstractFileStorageServerFilter {

    private final List<FileStoragePreviewProvider> previewProviders;

    public FileStorageViewServerFilter(FileStorageSetting setting) {
        super(setting);
        this.previewProviders = ServiceProvider.of(FileStoragePreviewProvider.class).getNewExtensions(null);
    }

    public FileStorageViewServerFilter(FileStorageSetting setting, Path cacheDir) {
        super(setting, new PreviewPdfCache(cacheDir));
        this.previewProviders = ServiceProvider.of(FileStoragePreviewProvider.class).getNewExtensions(null);
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String preview = request.getParam("preview");
        if (preview == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!setting.isOpenPreview()) {
            response.setStatus(403).end("Preview disabled");
            return;
        }

        // 从 path 解析文件路径：/{bucket}/{filepath}
        String key = resolveFilepath(request);
        if (key == null || key.isEmpty()) {
            response.setStatus(400).end("Missing file path in URL");
            return;
        }

        String storageName = resolveBucket(request);
        FileStorage storage = getFileStorage(storageName);
        if (storage == null) {
            response.setStatus(404).end("Storage not found: " + storageName);
            return;
        }

        FileOperationSetting ops = fileSetting.parse(request);
        String ext = getExt(key);
        String mime = MimeTypeUtils.getMimeType(ext);

        // --- 1. Fast path: 图片/音视频（浏览器原生，可选滤镜） ---
        if (isMediaType(mime)) {
            byte[] processed = streamAndFilterImage(request, response, storage, key, ext, ops);
            if (processed != null) {
                response.setStatus(200)
                        .setContentType(ops != null && ops.getFormat() != null
                                ? MimeTypeUtils.getMimeType(ops.getFormat())
                                : mime)
                        .setHeader("X-FileStorage-Preview", "native")
                        .end(processed);
                return;
            }
            return;
        }

        // 读取文件内容
        byte[] content = readContent(storage, key);
        if (content == null) {
            response.setStatus(404).end("File not found");
            return;
        }

        // --- 2. SPI 预览提供者 ---
        for (FileStoragePreviewProvider provider : previewProviders) {
            if (provider.supports(ext, mime)) {
                try {
                    PreviewResult result = provider.preview(content, ext, mime);
                    String page = wrapPreviewPage(result);
                    response.setStatus(200)
                            .setContentType("text/html;charset=utf-8")
                            .setHeader("X-FileStorage-Preview", "spi:" + provider.getClass().getSimpleName())
                            .end(page.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return;
                } catch (Exception e) {
                    log.warn("预览提供者 {} 失败: {}", provider.getClass().getSimpleName(), e.getMessage());
                }
            }
        }

        // --- 3. PDF 转换（Office 等） → 经 PDF.js 渲染 ---
        if (MimeTypeUtils.isConvertableToPdf(ext)) {
            byte[] pdfBytes = convertAndCachePdf(storage, key, ext, ops);
            if (pdfBytes != null) {
                FileStoragePreviewProvider pdfProv = findProvider("pdf", "application/pdf");
                if (pdfProv != null) {
                    PreviewResult result = pdfProv.preview(pdfBytes, "pdf", "application/pdf");
                    String page = wrapPreviewPage(result);
                    response.setStatus(200)
                            .setContentType("text/html;charset=utf-8")
                            .setHeader("X-FileStorage-Converted", "true")
                            .end(page.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return;
                }
                // 降级：直接返回 PDF 字节
                response.setStatus(200)
                        .setContentType("application/pdf")
                        .setHeader("X-FileStorage-Converted", "true")
                        .end(pdfBytes);
                return;
            }
        }

        // --- 4. 前端插件 ---
        if (MimeTypeUtils.isPluginPreviewSupported(ext)) {
            response.setStatus(200)
                    .setContentType("text/plain;charset=utf-8")
                    .setHeader("X-FileStorage-Preview-Plugin", "true")
                    .setHeader("X-FileStorage-File-Extension", ext)
                    .end("Plugin preview required".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }

        // --- 5. 不支持 ---
        response.setStatus(415).end("Unsupported preview format: " + ext);
    }

    // ==================== 内部方法 ====================

    private boolean isMediaType(String mime) {
        if (mime == null) {
            return false;
        }
        return MimeTypeUtils.isBrowserPreviewSupported(mime)
                && (mime.startsWith("image/")
                || mime.startsWith("audio/")
                || mime.startsWith("video/"));
    }

    private byte[] streamAndFilterImage(ServerRequest request, ServerResponse response,
                                        FileStorage storage, String key, String ext, FileOperationSetting ops) throws Exception {
        var getResult = storage.getObject(key);
        if (getResult == null || getResult.getInputStream() == null) {
            response.setStatus(404).end("File not found");
            return null;
        }
        byte[] original = getResult.getInputStream().readAllBytes();
        return applyImageFilter(original, ops, key, ext);
    }

    private byte[] readContent(FileStorage storage, String key) throws Exception {
        var getResult = storage.getObject(key);
        if (getResult == null || getResult.getInputStream() == null) {
            return null;
        }
        return getResult.getInputStream().readAllBytes();
    }

    private byte[] convertAndCachePdf(FileStorage storage, String key, String ext, FileOperationSetting ops) {
        String cacheKey = key + buildOpsSuffix(ops);
        try {
            Path cached = getPdfCache().getCacheFile("default", cacheKey);
            if (cached != null) {
                return Files.readAllBytes(cached);
            }
            var getResult = storage.getObject(key);
            if (getResult == null || getResult.getInputStream() == null) {
                return null;
            }
            byte[] originalBytes = getResult.getInputStream().readAllBytes();
            Path tempPdf = Files.createTempFile("preview-", ".pdf");
            try (ByteArrayInputStream bais = new ByteArrayInputStream(originalBytes);
                 FileOutputStream fos = new FileOutputStream(tempPdf.toFile())) {
                FileSource source = FileSource.of(bais, ext);
                FileSource target = FileSource.of(fos, "pdf");
                ConvertSupport.convert(ext, "pdf")
                        .from(source).to(target).convert();
            }
            byte[] pdfBytes = Files.readAllBytes(tempPdf);
            getPdfCache().writeCache("default", cacheKey, pdfBytes);
            Files.deleteIfExists(tempPdf);
            return pdfBytes;
        } catch (Exception e) {
            log.warn("PDF 转换失败 ({}): {}", ext, e.getMessage());
            return null;
        }
    }

    private FileStoragePreviewProvider findProvider(String ext, String mime) {
        for (FileStoragePreviewProvider p : previewProviders) {
            if (p.supports(ext, mime)) {
                return p;
            }
        }
        return null;
    }

    private String wrapPreviewPage(PreviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        if (result.getEmbeddedCss() != null) {
            sb.append("<style>").append(result.getEmbeddedCss()).append("</style>");
        }
        if (result.getCssUrls() != null) {
            for (String url : result.getCssUrls()) {
                sb.append("<link rel=\"stylesheet\" href=\"").append(escapeAttr(url)).append("\">");
            }
        }
        sb.append("</head><body>");
        if (result.getHtmlContent() != null) {
            sb.append(result.getHtmlContent());
        }
        if (result.getEmbeddedJs() != null) {
            sb.append("<script>").append(result.getEmbeddedJs()).append("</script>");
        }
        if (result.getJsUrls() != null) {
            for (String url : result.getJsUrls()) {
                sb.append("<script src=\"").append(escapeAttr(url)).append("\"></script>");
            }
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static String getExt(String key) {
        if (key == null || !key.contains(".")) {
            return "";
        }
        return key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ENGLISH);
    }

    private static String buildOpsSuffix(FileOperationSetting ops) {
        if (ops == null || !ops.hasOperation()) {
            return "";
        }
        return "_" + ops.hashCode();
    }
}
