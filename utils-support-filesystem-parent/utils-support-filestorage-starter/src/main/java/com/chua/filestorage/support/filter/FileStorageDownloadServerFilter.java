package com.chua.filestorage.support.filter;

import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.cache.PreviewPdfCache;
import com.chua.filestorage.support.flash.FlashTokenService;
import com.chua.filestorage.support.operation.FileOperationSetting;
import com.chua.filestorage.support.setting.FileStorageSetting;
import com.chua.filestorage.support.utils.MimeTypeUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 文件存储下载过滤器。
 *
 * <p>拦截 {@code ?download} 和 {@code ?flash} flag 形式的请求。
 * 文件路径从 URL 路径 解析：{@code /{bucket}/{filepath}?download}。
 * 当 {@link FileStorageSetting#isOpenDownload()} 为 false 时拒绝。</p>
 *
 * <p>支持：
 * <ul>
 *   <li>Range 断点续传（{@link FileStorageSetting#isOpenRange()} 开启时）</li>
 *   <li>闪图一次性文件（使用后自动清理）</li>
 *   <li>自定义文件名（filename 参数）</li>
 *   <li>存储路由（storage 参数）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 2024/12/28
 */
@Slf4j
public class FileStorageDownloadServerFilter extends AbstractFileStorageServerFilter {

    /**
     * 创建 文件storagedownload服务端过滤器 实例
     * @param setting setting
     */
    public FileStorageDownloadServerFilter(FileStorageSetting setting) {
        super(setting);
    }

    /**
     * 创建 文件storagedownload服务端过滤器 实例
     * @param setting setting
     * @param java Java
     * @param cacheDir 缓存dir
     */
    public FileStorageDownloadServerFilter(FileStorageSetting setting, java.nio.file.Path cacheDir) {
        super(setting, new PreviewPdfCache(cacheDir));
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String download = request.getParam("download");
        String flash = request.getParam("flash");
        if (download == null && flash == null) {
            chain.doFilter(request, response);
            return;
        }

        if (download != null) {
            // 普通下载：/{bucket}/{filepath}?download
            if (!setting.isOpenDownload()) {
                response.setStatus(403).end("Download disabled");
                return;
            }
            handleDownload(request, response);
        } else {
            // 闪图模式：/{bucket}/{filepath}?flash=<token> 或 ?flash=create
            if (!setting.isOpenFlash()) {
                response.setStatus(403).end("Flash disabled");
                return;
            }
            handleFlash(request, response, flash);
        }
    }

    /**
     * 处理Download
     *
     * @param request 请求
     * @param response 响应
     */
    private void handleDownload(ServerRequest request, ServerResponse response) throws Exception {
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

        var getResult = storage.getObject(key);
        if (getResult == null || getResult.getInputStream() == null) {
            response.setStatus(404).end("File not found");
            return;
        }

        String ext = getExt(key);
        String mime = MimeTypeUtils.getMimeType(ext);
        String fileName = resolveFileName(request, key);

        String rangeHeader = setting.isOpenRange() && MimeTypeUtils.isRangeSupported(mime)
                ? request.getHeader("Range") : null;

        if (rangeHeader != null && !rangeHeader.isBlank()) {
            handleRange(request, response, getResult, mime, fileName, rangeHeader);
            return;
        }

        byte[] bytes = getResult.getInputStream().readAllBytes();
        response.setStatus(200)
                .setContentType("application/octet-stream")
                .setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .setHeader("Content-Length", String.valueOf(bytes.length))
                .setHeader("Accept-Ranges", "bytes")
                .end(bytes);
    }

    /**
     * 处理Flash
     *
     * @param request 请求
     * @param response 响应
     * @param flashTokenOrCmd flash令牌或CMD
     */
    private void handleFlash(ServerRequest request, ServerResponse response, String flashTokenOrCmd) throws Exception {
        FlashTokenService svc = getFlashService();

        if ("create".equalsIgnoreCase(flashTokenOrCmd) || "1".equals(flashTokenOrCmd)) {
 // 创建闪图 令牌（不需要文件路径）
            String token = svc.createToken();
            response.setStatus(201)
                    .setContentType("application/json")
                    .setHeader("X-Flash-Token", token)
                    .end("{\"token\":\"" + token + "\"}");
            return;
        }

        // 使用闪图 token：/{bucket}/{filepath}?flash=<token>
        if (!svc.validateToken(flashTokenOrCmd)) {
            response.setStatus(404).end("Flash token invalid or expired");
            return;
        }

        String key = resolveFilepath(request);
        if (key == null || key.isEmpty()) {
 // 兼容旧版：从 查询 参数 键 获取
            key = request.getParam("key");
        }
        if (key == null || key.isEmpty()) {
            response.setStatus(400).end("Missing file path in URL");
            return;
        }

        String storageName = resolveBucket(request);
        FileStorage storage = getFileStorage(storageName);
        if (storage == null) {
            response.setStatus(404).end("Storage not found");
            return;
        }

        var getResult = storage.getObject(key);
        if (getResult == null || getResult.getInputStream() == null) {
            response.setStatus(404).end("File not found");
            return;
        }

        byte[] bytes = getResult.getInputStream().readAllBytes();
        String ext = getExt(key);
        String mime = MimeTypeUtils.getMimeType(ext);
        String fileName = resolveFileName(request, key);

        response.setStatus(200)
                .setContentType(mime)
                .setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .setHeader("Content-Length", String.valueOf(bytes.length))
                .end(bytes);

 // 消费后删除 记号笔 文件
        svc.consumeToken(flashTokenOrCmd);

        // 闪图模式下可选删除原文件
        if (setting.getCache().isFlashAutoDeleteOrigin()) {
            try {
                storage.deleteObject(key);
                log.debug("[Flash] 原文件已删除: key={}", key);
            } catch (Exception e) {
                log.warn("[Flash] 删除原文件失败: key={}", key, e);
            }
        }
    }

    /**
     * 处理范围
     * @param request 请求
     * @param response 响应
     * @param getResult 获取结果
     * @param mime mime
     * @param fileName 文件名
     * @param rangeHeader 范围头部
     */
    private void handleRange(ServerRequest request, ServerResponse response,
                             com.chua.common.support.storage.result.GetObjectResult getResult,
                             String mime, String fileName, String rangeHeader) throws IOException {
        byte[] full = getResult.getInputStream().readAllBytes();
        long len = full.length;

        Range r = parseRange(rangeHeader, len);
        if (r == null) {
            response.setStatus(416)
                    .setHeader("Content-Range", "bytes */" + len)
                    .end("Requested Range Not Satisfiable");
            return;
        }

        byte[] part = new byte[(int) (r.end - r.start + 1)];
        System.arraycopy(full, (int) r.start, part, 0, part.length);

        response.setStatus(206)
                .setContentType("application/octet-stream")
                .setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .setHeader("Content-Length", String.valueOf(part.length))
                .setHeader("Content-Range", "bytes " + r.start + "-" + r.end + "/" + len)
                .setHeader("Accept-Ranges", "bytes")
                .end(part);
    }

    /**
     * 解析文件名
     *
     * @param request 请求
     * @param key 键
     * @return resolve文件名称的结果
     */
    private static String resolveFileName(ServerRequest request, String key) {
        String filename = request.getParam("filename");
        if (!StringUtils.isEmpty(filename)) {
            return filename;
        }
        if (key != null && key.contains("/")) {
            return key.substring(key.lastIndexOf('/') + 1);
        }
        return key;
    }

    /**
     * 获取Ext
     *
     * @param key 键
     * @return 获取ext的结果
     */
    private static String getExt(String key) {
        if (key == null || !key.contains(".")) {
            return "";
        }
        return key.substring(key.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ENGLISH);
    }

    /**
     * 解析范围
     *
     * @param header 头部
     * @param len len
     * @return 解析范围的结果
     */
    private static Range parseRange(String header, long len) {
        String h = header.trim();
        if (!h.startsWith("bytes=")) {
            return null;
        }
        String[] parts = h.substring(6).split("-", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            long s = Long.parseLong(parts[0]);
            long e = parts[1].isEmpty() ? len - 1 : Long.parseLong(parts[1]);
            if (s > e || e >= len) {
                return null;
            }
            return new Range(s, e);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 范围
     *
     * @param start 启动
     * @param end 结束
     * @return 范围的结果
     */
    private record Range(long start, long end) {
    }
}
