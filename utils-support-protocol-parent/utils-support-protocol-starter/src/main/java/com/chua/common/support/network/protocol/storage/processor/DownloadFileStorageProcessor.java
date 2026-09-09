package com.chua.common.support.network.protocol.storage.processor;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.storage.FileStorageProcessor;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import com.chua.common.support.network.protocol.storage.filter.ImageFilterManager;
import com.chua.common.support.network.protocol.storage.image.ImageSettingManager;
import com.chua.common.support.network.protocol.storage.setting.FileSettingManager;
import com.chua.common.support.network.protocol.storage.watermark.DefaultImageWatermarkProcessor;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.utils.ArrayUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 下载模式文件存储处理器
 * 
 * 处理文件下载，包括Range请求支持
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("download")
public class DownloadFileStorageProcessor implements FileStorageProcessor {

    private static final String DOWNLOAD_MODE = "download";

    private final FileSettingManager fileSettingManager = new FileSettingManager();
    private final ImageSettingManager imageSettingManager = new ImageSettingManager();
    private final ImageFilterManager imageFilterManager = new ImageFilterManager();
    private final DefaultImageWatermarkProcessor watermarkProcessor = new DefaultImageWatermarkProcessor();

    @Override
    public String getName() {
        return "DownloadFileStorageProcessor";
    }

    @Override
    public int getOrder() {
        return 100; // 下载处理器优先级
    }

    @Override
    public boolean supports(FileStorageProcessorContext context) {
        return DOWNLOAD_MODE.equals(context.getMode());
    }

    @Override
    public void process(FileStorageProcessorContext context) throws Exception {
        ServletRequest request = context.getRequest();
        ServletResponse response = context.getResponse();

        if (log.isDebugEnabled()) {
            log.debug("处理下载模式: {}", context.getPath());
        }

        // 检查下载权限
        if (!isDownloadAllowed(request, context.getFileStorageFactory())) {
            response.setStatusCode(403);
            response.setBody("下载权限不足".getBytes());
            return;
        }

        // 按顺序执行处理链：setting -> image -> filter -> watermarker
        try {
            // 1. 文件设置处理（格式转换等）
            if (log.isDebugEnabled()) {
                log.debug("开始文件设置处理");
            }
            fileSettingManager.process(context);
            if (log.isDebugEnabled()) {
                log.debug("文件设置处理完成");
            }

            // 2. 图片设置处理（旋转、缩略图、质量、灰度等）
            if (log.isDebugEnabled()) {
                log.debug("开始图片设置处理");
            }
            imageSettingManager.process(context);
            if (log.isDebugEnabled()) {
                log.debug("图片设置处理完成");
            }

            // 3. 图片滤镜处理
            if (log.isDebugEnabled()) {
                log.debug("开始图片滤镜处理");
            }
            imageFilterManager.process(context);
            if (log.isDebugEnabled()) {
                log.debug("图片滤镜处理完成");
            }

            // 4. 图片水印处理
            if (log.isDebugEnabled()) {
                log.debug("开始图片水印处理");
            }
            watermarkProcessor.process(context);
            if (log.isDebugEnabled()) {
                log.debug("图片水印处理完成");
            }

        } catch (Exception e) {
            log.error("下载模式处理链执行失败: {}", context.getPath(), e);
            // 继续执行，不中断下载功能
        }

        // 设置下载响应头
        response.addHeader("Content-Type", "application/octet-stream");

        // 设置文件名
        if (context.getMetadata() != null && StringUtils.isNotEmpty(context.getMetadata().getFilename())) {
            String filename = context.getMetadata().getFilename();
            response.addHeader("Content-Disposition",
                String.format("attachment; filename=\"%s\"", filename));
        }

        // 处理Range请求（断点续传）
        if (context.getFileStorageFactory().openRange()) {
            handleRangeRequest(request, response);
        }

        context.markProcessed();
    }

    /**
     * 检查是否允许下载
     */
private boolean isDownloadAllowed(ServletRequest request, com.chua.common.support.network.protocol.storage.FileStorageFactory fileStorageFactory) {
        if (!fileStorageFactory.openDownload()) {
            return false;
        }

        String[] userAgent = fileStorageFactory.downloadUserAgent();
        if (ArrayUtils.isEmpty(userAgent)) {
            return true;
        }

        String header = request.getHeader("X-Download-User-Agent");
        if (StringUtils.isEmpty(header)) {
            return false;
        }

        return ArrayUtils.containsAllIgnoreCase(userAgent, StringUtils.splitAndTrim(header, ";"));
    }

    /**
     * 处理Range请求
     */
    private void handleRangeRequest(ServletRequest request, ServletResponse response) {
        String rangeHeader = request.getHeader("range");
        if (StringUtils.isEmpty(rangeHeader)) {
            return;
        }

        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            return;
        }

        try {
            // 解析Range头：bytes=start-end
            if (rangeHeader.startsWith("bytes=")) {
                String range = rangeHeader.substring(6);
                String[] parts = range.split("-");

                int start = 0;
                int end = body.length - 1;

                if (parts.length >= 1 && StringUtils.isNotEmpty(parts[0])) {
                    start = Integer.parseInt(parts[0]);
                }

                if (parts.length >= 2 && StringUtils.isNotEmpty(parts[1])) {
                    end = Integer.parseInt(parts[1]);
                }

                // 确保范围有效
                start = Math.max(0, Math.min(start, body.length - 1));
                end = Math.max(start, Math.min(end, body.length - 1));

                // 截取指定范围的数据
                byte[] rangeData = new byte[end - start + 1];
                System.arraycopy(body, start, rangeData, 0, rangeData.length);

                // 设置Range响应
                response.setBody(rangeData);
                response.setStatusCode(206); // Partial Content
                response.addHeader("Content-Range", 
                    String.format("bytes %d-%d/%d", start, end, body.length));
                response.addHeader("Accept-Ranges", "bytes");

                if (log.isDebugEnabled()) {
                    log.debug("处理Range请求: {} -> {}字节", rangeHeader, rangeData.length);
                }
            }
        } catch (Exception e) {
            log.warn("处理Range请求失败: {}", rangeHeader, e);
            // Range处理失败时，返回完整文件
        }
    }
}