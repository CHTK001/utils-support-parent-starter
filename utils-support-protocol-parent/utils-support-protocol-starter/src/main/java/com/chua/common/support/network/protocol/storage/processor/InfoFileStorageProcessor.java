package com.chua.common.support.network.protocol.storage.processor;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.storage.oss.metadata.Metadata;
import com.chua.common.support.network.protocol.storage.FileStorageProcessor;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import com.chua.common.support.network.protocol.request.ServletResponse;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 信息模式文件存储处理器
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("info")
public class InfoFileStorageProcessor implements FileStorageProcessor {

    @Override
    public String getName() {
        return "InfoFileStorageProcessor";
    }

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public boolean supports(FileStorageProcessorContext context) {
        return "info".equals(context.getMode());
    }

    @Override
    public void process(FileStorageProcessorContext context) throws Exception {
        ServletResponse response = context.getResponse();

        if (log.isDebugEnabled()) {
            log.debug("处理信息模式: {}", context.getPath());
        }

        // 生成文件信息HTML页面
        String html = generateInfoHtml(context);
        
        response.setBody(html.getBytes());
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        
        context.markProcessed();
    }

    /**
     * 生成文件信息HTML
     */
    private String generateInfoHtml(FileStorageProcessorContext context) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html><head><meta charset=\"utf-8\"><title>文件信息</title>");
        html.append("<style>");
        html.append("body { font-family: Arial, sans-serif; margin: 40px; }");
        html.append("table { border-collapse: collapse; width: 100%; }");
        html.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }");
        html.append("th { background-color: #f2f2f2; }");
        html.append("</style>");
        html.append("</head><body>");
        html.append("<h1>文件信息</h1>");
        
        html.append("<table>");
        html.append("<tr><th>属性</th><th>值</th></tr>");
        
        // 文件路径
        html.append("<tr><td>文件路径</td><td>").append(context.getPath()).append("</td></tr>");
        
        // 存储桶
        html.append("<tr><td>存储桶</td><td>").append(context.getBeanName()).append("</td></tr>");
        
        // 文件元数据
        if (context.getMetadata() != null) {
            Metadata metadata = context.getMetadata();
            
            if (metadata.getFilename() != null) {
                html.append("<tr><td>文件名</td><td>").append(metadata.getFilename()).append("</td></tr>");
            }
            
            if (metadata.getFileSize() != null) {
                html.append("<tr><td>文件大小</td><td>").append(formatFileSize(metadata.getFileSize())).append("</td></tr>");
            }
            
            if (metadata.getSuffix() != null) {
                html.append("<tr><td>文件扩展名</td><td>").append(metadata.getSuffix()).append("</td></tr>");
            }
            
            if (metadata.getLastModified() != null) {
                html.append("<tr><td>最后修改时间</td><td>").append(new java.util.Date(metadata.getLastModified())).append("</td></tr>");
            }
        }
        
        // 媒体类型
        if (context.getMediaType() != null) {
            html.append("<tr><td>媒体类型</td><td>").append(context.getMediaType()).append("</td></tr>");
        }
        
        html.append("</table>");
        html.append("</body></html>");
        
        return html.toString();
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }
    }
}