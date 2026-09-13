package com.chua.filestorage.support.filter;

import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.file.converter.ConvertSupport;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.result.GetObjectResult;
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
import java.util.Set;

/**
* 文件存储预览过滤器。
*
* <p>拦截 {@code ?preview} flag 形式的请求。
* 文件路径从 URL 路径 解析：{@code /{bucket}/{filepath}?preview&大小=200x200}。
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

    /** 复合扩展名列表（需优先于单扩展名识别） */
    private static final Set<String> COMPOUND_EXTS = Set.of(
            "tar.gz", "tar.bz2", "tar.xz", "tar.zst", "tar.lz4", "tar.lzma", "tar.sz");

    /** 预览请求允许读取的最大内容字节数（防止超大文件拖垮内存与转换线程） */
    private static final long MAX_PREVIEW_CONTENT_BYTES = 512L * 1024 * 1024;

    /**
     * 文件预览提供者列表
     */
    private final List<FileStoragePreviewProvider> previewProviders;

    /**
    * 创建 文件storageview服务端过滤器 实例。
    *
    * @param setting  文件存储配置，不能为 空
     */
    public FileStorageViewServerFilter(FileStorageSetting setting) {
        super(setting);
        this.previewProviders = ServiceProvider.of(FileStoragePreviewProvider.class).getNewExtensions(null);
    }

    /**
    * 创建 文件storageview服务端过滤器 实例，指定 PDF 缓存目录。
    *
    * @param setting  文件存储配置，不能为 空
    * @param cacheDir PDF 缓存目录；为 空 时不启用 PDF 缓存
     */
    public FileStorageViewServerFilter(FileStorageSetting setting, Path cacheDir) {
        super(setting, new PreviewPdfCache(cacheDir));
        this.previewProviders = ServiceProvider.of(FileStoragePreviewProvider.class).getNewExtensions(null);
    }

    /**
    * 执行过滤逻辑，按优先级处理预览请求。
    *
    * @param request  请求
    * @param response 响应
    * @param chain    过滤链
    * @throws Exception 处理失败
     */
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

 // 从 路径 解析文件路径：/{bucket}/{filepath}
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

 // --- 1. Fast 路径: 图片/音视频（浏览器原生，可选滤镜） ---
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
        boolean spiHit = false;
        for (FileStoragePreviewProvider provider : previewProviders) {
            if (!provider.supports(ext, mime)) {
                continue;
            }
            spiHit = true;
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
        if (spiHit) {
            response.setStatus(502)
                    .setContentType("text/plain;charset=utf-8")
                    .end("All preview providers failed for: " + ext);
            return;
        }

 // --- 3. PDF 转换（办公室 等） → 经 PDF.js 渲染 ---
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

    /**
    * 判断是否为浏览器原生可预览的图片 / 音视频 MIME 类型。
    *
    * @param mime MIME 类型
    * @return true 表示属于图片 / 音视频
     */
    private boolean isMediaType(String mime) {
        if (mime == null) {
            return false;
        }
        return MimeTypeUtils.isBrowserPreviewSupported(mime)
                && (mime.startsWith("image/")
                || mime.startsWith("audio/")
                || mime.startsWith("video/"));
    }

    /**
    * 从存储读取图片字节并应用可选滤镜（缩放/裁剪/格式转换）。
    *
    * @param request  当前请求，用于读取滤镜参数
    * @param response 响应对象，文件不存在时返回 404
    * @param storage  文件存储
    * @param key      对象键（存储内的文件路径）
    * @param ext      文件扩展名（小写，用于滤镜推断）
    * @param ops      文件操作设置，可为 空；包含尺寸、格式等滤镜参数
    * @return 处理后的图片字节；文件不存在或处理失败时返回 空
    * @throws Exception 读取或过滤失败
     */
    private byte[] streamAndFilterImage(ServerRequest request, ServerResponse response,
                                        FileStorage storage, String key, String ext, FileOperationSetting ops) throws Exception {
        var getResult = storage.getObject(key);
        if (getResult == null || getResult.getInputStream() == null) {
            response.setStatus(404).end("File not found");
            return null;
        }
        byte[] original = readWithLimit(getResult);
        if (original == null) {
            response.setStatus(413).end("File too large for preview");
            return null;
        }
        return applyImageFilter(original, ops, key, ext);
    }

    /**
    * 读取存储对象的内容字节，超过最大预览内容上限时拒绝。
    *
    * @param storage 文件存储
    * @param key     对象键
    * @return 内容字节；对象不存在返回 空；超过上限返回 空
    * @throws Exception 读取失败
     */
    private byte[] readContent(FileStorage storage, String key) throws Exception {
        var getResult = storage.getObject(key);
        if (getResult == null || getResult.getInputStream() == null) {
            return null;
        }
        return readWithLimit(getResult);
    }

    /**
    * 带大小上限地读取对象内容字节。
    *
    * <p>先通过 {@link com.chua.common.support.storage.metadata.Metadata#getSize()}
    * 预检文件元数据大小，超过上限直接拒绝；再对实际流读取做二次校验，
    * 防止元数据缺失或谎报导致的超大读取。</p>
    *
    * @param getResult 存储对象读取结果
    * @return 内容字节；超过上限或文件不存在返回 空
    * @throws IOException 读取失败
     */
    private byte[] readWithLimit(GetObjectResult getResult)
            throws IOException {
        if (getResult.getMetadata() != null
                && getResult.getMetadata().getSize() > MAX_PREVIEW_CONTENT_BYTES) {
            log.warn("预览拒绝：文件大小 {} 字节超过上限 {} 字节",
                    getResult.getMetadata().getSize(), MAX_PREVIEW_CONTENT_BYTES);
            return null;
        }
        byte[] bytes = getResult.getInputStream().readNBytes((int) (MAX_PREVIEW_CONTENT_BYTES + 1));
        if (bytes.length > MAX_PREVIEW_CONTENT_BYTES) {
            log.warn("预览拒绝：实际读取字节 {} 超过上限 {} 字节",
                    bytes.length, MAX_PREVIEW_CONTENT_BYTES);
            return null;
        }
        return bytes;
    }

    /**
    * 将 办公室 文档转换为 PDF 并缓存，转换失败时返回 空。
    *
    * @param storage 文件存储
    * @param key     对象键
    * @param ext     文件扩展名
    * @param ops     文件操作设置
    * @return PDF 字节；转换失败时返回 空
     */
    private byte[] convertAndCachePdf(FileStorage storage, String key, String ext, FileOperationSetting ops) {
        String cacheKey = key + buildOpsSuffix(ops);
 // 使用 获取或转换 实现并发去重：同一文件并发请求只触发一次转换
        return getPdfCache().getOrConvert("default", cacheKey, () -> {
            try {
                var getResult = storage.getObject(key);
                if (getResult == null || getResult.getInputStream() == null) {
                    return null;
                }
                byte[] originalBytes = readWithLimit(getResult);
                if (originalBytes == null) {
                    log.warn("PDF 转换拒绝：文件 {} 超过最大预览内容上限", key);
                    return null;
                }
                Path tempPdf = Files.createTempFile("preview-", ".pdf");
                try {
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(originalBytes);
                         FileOutputStream fos = new FileOutputStream(tempPdf.toFile())) {
                        FileSource source = FileSource.of(bais, ext);
                        FileSource target = FileSource.of(fos, "pdf");
                        ConvertSupport.convert(ext, "pdf")
                                .from(source).to(target).convert();
                    }
                    return Files.readAllBytes(tempPdf);
                } finally {
                    Files.deleteIfExists(tempPdf);
                }
            } catch (Exception e) {
                log.warn("PDF 转换失败 ({}): {}", ext, e.getMessage());
                return null;
            }
        });
    }

    /**
    * 按扩展名查找支持的预览提供者。
    *
    * @param ext  文件扩展名
    * @param mime MIME 类型
    * @return 匹配的提供者；未找到时返回 空
     */
    private FileStoragePreviewProvider findProvider(String ext, String mime) {
        for (FileStoragePreviewProvider p : previewProviders) {
            if (p.supports(ext, mime)) {
                return p;
            }
        }
        return null;
    }

    /**
    * 将预览结果包装为完整 HTML 页面。
    *
    * <p>当 {@link PreviewResult#requiresSandbox()} 为真时，将 provider 产出的
    * 内容放入带沙箱属性的 iframe，并注入受限的 CSP，以隔离其中可能携带的脚本，
    * 降低跨源内容带来的 XSS 风险；否则直接平铺渲染。</p>
    *
    * @param result 预览结果
    * @return 完整 HTML 页面字符串
     */
    private String wrapPreviewPage(PreviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        if (result.getEmbeddedCss() != null) {
            sb.append("<style>").append(result.getEmbeddedCss()).append("</style>");
        }
        if (result.getCssUrls() != null) {
            for (String url : result.getCssUrls()) {
                sb.append("<link rel=\"stylesheet\" href=\"").append(StringUtils.escapeAttr(url)).append("\">");
            }
        }
        sb.append("</head><body>");
        if (result.isRequiresSandbox()) {
            // 沙箱隔离：将 provider 产出的不可信内容放入带 sandbox 的 iframe，
            // 并通过 srcdoc 注入受限 CSP，隔离脚本与外层页面 DOM / Cookie 访问
            sb.append(buildSandboxedIframe(result));
        } else {
            if (result.getHtmlContent() != null) {
                sb.append(result.getHtmlContent());
            }
            if (result.getEmbeddedJs() != null) {
                sb.append("<script>").append(result.getEmbeddedJs()).append("</script>");
            }
            if (result.getJsUrls() != null) {
                for (String url : result.getJsUrls()) {
                    sb.append("<script src=\"").append(StringUtils.escapeAttr(url)).append("\"></script>");
                }
            }
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
    * 构建沙箱 iframe：以 srcdoc 承载完整文档，注入受限 CSP 并启用 sandbox。
    *
    * <p>srcdoc 属性值整体经 {@link StringUtils#escapeAttr} 转义，
    * 内容中携带的脚本即使被执行也受 CSP 与沙箱双重限制，无法访问外层页面。</p>
    *
    * @param result 预览结果
    * @return 沙箱 iframe HTML
     */
    private String buildSandboxedIframe(PreviewResult result) {
        StringBuilder inner = new StringBuilder();
        inner.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; ")
                .append("style-src 'unsafe-inline'; img-src data:; script-src 'unsafe-inline'\">");
        if (result.getEmbeddedCss() != null) {
            inner.append("<style>").append(result.getEmbeddedCss()).append("</style>");
        }
        inner.append("</head><body>");
        if (result.getHtmlContent() != null) {
            inner.append(result.getHtmlContent());
        }
        if (result.getEmbeddedJs() != null) {
            inner.append("<script>").append(result.getEmbeddedJs()).append("</script>");
        }
        inner.append("</body></html>");
        return "<iframe sandbox=\"allow-scripts\" style=\"width:100%;height:100vh;border:0\" srcdoc=\""
                + StringUtils.escapeAttr(inner.toString()) + "\"></iframe>";
    }

    /**
    * 从对象键提取小写扩展名，复合扩展名（如 焦油.gz）优先识别。
    *
    * @param key 对象键（含路径）
    * @return 小写扩展名；无扩展名时返回空串
     */
    private static String getExt(String key) {
        if (key == null || !key.contains(".")) {
            return "";
        }
        String lower = key.toLowerCase(Locale.ENGLISH);
        for (String compound : COMPOUND_EXTS) {
            if (lower.endsWith("." + compound)) {
                return compound;
            }
        }
        return key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ENGLISH);
    }

    /**
    * 根据文件操作设置构建缓存键后缀，无操作时返回空串。
    *
    * @param ops 文件操作设置
    * @return 缓存键后缀
     */
    private static String buildOpsSuffix(FileOperationSetting ops) {
        if (ops == null || !ops.hasOperation()) {
            return "";
        }
        // 使用字段长度前缀 + 显式字段拼接替代 hashCode()，完全避免不同参数组合的哈希碰撞
        // 格式: 每个字段用 "len(value)value" 拼接，如 "6200x2004webp"
        StringBuilder sb = new StringBuilder();
        appendField(sb, ops.getSize());
        appendField(sb, ops.getFormat());
        appendIntField(sb, ops.getQuality());
        appendField(sb, ops.getCrop());
        appendIntField(sb, ops.getRotate());
        appendField(sb, ops.getFlip());
        if (Boolean.TRUE.equals(ops.getGrayscale())) {
            sb.append("1g");
        }
        appendFloatField(sb, ops.getBlur());
        appendFloatField(sb, ops.getSharpen());
        appendField(sb, ops.getWatermarkText());
        appendField(sb, ops.getWatermarkImage());
        return sb.length() > 0 ? "_" + sb.toString() : "";
    }

    /**
    * 追加字符串字段（长度前缀 + 值），空 时不追加
    *
    * @param sb sb
    * @param value 值
     */
    private static void appendField(StringBuilder sb, String value) {
        if (value != null) {
            sb.append(value.length()).append(value);
        }
    }

    /**
    * 追加整数字段（长度前缀 + 值），空 时不追加
    *
    * @param sb sb
    * @param value 值
     */
    private static void appendIntField(StringBuilder sb, Integer value) {
        if (value != null) {
            String s = String.valueOf(value);
            sb.append(s.length()).append(s);
        }
    }

    /**
    * 追加浮点字段（长度前缀 + 值），空 或 <=0 时不追加
    *
    * @param sb sb
    * @param value 值
     */
    private static void appendFloatField(StringBuilder sb, Float value) {
        if (value != null && value > 0) {
            String s = String.valueOf(value);
            sb.append(s.length()).append(s);
        }
    }
}
