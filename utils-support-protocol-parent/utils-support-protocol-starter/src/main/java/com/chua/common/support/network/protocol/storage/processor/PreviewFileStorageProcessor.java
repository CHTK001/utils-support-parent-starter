package com.chua.common.support.network.protocol.storage.processor;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.storage.FileStorageProcessor;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import com.chua.common.support.network.protocol.storage.filter.ImageFilterManager;
import com.chua.common.support.network.protocol.storage.image.ImageSettingManager;
import com.chua.common.support.network.protocol.storage.setting.FileSettingManager;
import com.chua.common.support.network.protocol.storage.watermark.DefaultImageWatermarkProcessor;
import com.chua.common.support.network.protocol.request.ServletResponse;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 预览模式文件存储处理器
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("preview")
public class PreviewFileStorageProcessor implements FileStorageProcessor {

    private final FileSettingManager fileSettingManager = new FileSettingManager();
    private final ImageSettingManager imageSettingManager = new ImageSettingManager();
    private final ImageFilterManager imageFilterManager = new ImageFilterManager();
    private final DefaultImageWatermarkProcessor watermarkProcessor = new DefaultImageWatermarkProcessor();

    @Override
    public String getName() {
        return "PreviewFileStorageProcessor";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public boolean supports(FileStorageProcessorContext context) {
        return "preview".equals(context.getMode());
    }

    @Override
    public void process(FileStorageProcessorContext context) throws Exception {
        ServletResponse response = context.getResponse();

        if (log.isDebugEnabled()) {
            log.debug("处理预览模式: {}", context.getPath());
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
            log.error("预览模式处理链执行失败: {}", context.getPath(), e);
            // 继续执行，不中断预览功能
        }

        // 设置正确的Content-Type用于预览
        if (context.getGetObjectResult() != null && context.getGetObjectResult().getMediaType() != null) {
            String contentType = context.getGetObjectResult().getMediaType().toString();
            response.addHeader("Content-Type", contentType);
            if (log.isDebugEnabled()) {
                log.debug("设置预览Content-Type: {}", contentType);
            }
        }

        // 设置缓存头
        response.addHeader("Cache-Control", "public, max-age=3600");

        context.markProcessed();
    }
}