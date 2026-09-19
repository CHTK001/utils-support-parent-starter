package com.chua.filestorage.support.spi;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.operation.FileOperationSetting;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * JDK 默认的文件存储 URL 参数设置实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jdk")
public class JdkFileStorageFileSetting implements FileStorageFileSetting {

    @Override
    /** Capabilities */
    public List<String> capabilities() {
        return List.of(
                "size", "format", "quality", "crop", "rotate",
                "flip", "grayscale", "blur", "sharpen", "autoOrient",
                "watermarkText", "watermarkImage", "storage", "forceDownload",
                "pdfPage", "pdfPageSize", "pdfOrientation"
        );
    }

    @Override
    /** 解析 */
    public FileOperationSetting parse(ServerRequest request) {
        return FileOperationSetting.builder()
                .size(getFirst(request, "size", "w", "width"))
                .format(getFirst(request, "format", "f"))
                .quality(parseInt(request, "quality", "q"))
                .crop(getFirst(request, "crop", "c"))
                .rotate(parseInt(request, "rotate", "r"))
                .flip(getFirst(request, "flip"))
                .grayscale(parseBool(request, "grayscale", "gray"))
                .blur(parseFloat(request, "blur"))
                .sharpen(parseFloat(request, "sharpen"))
                .autoOrient(parseBool(request, "autoOrient", "orient"))
                .watermarkText(getFirst(request, "watermarkText", "wmt"))
                .watermarkImage(getFirst(request, "watermarkImage", "wmi"))
                .storageName(getFirst(request, "storage", "s"))
                .forceDownload(parseBool(request, "forceDownload", "fd"))
                .pdfPage(parseInt(request, "pdfPage", "page"))
                .pdfPageSize(getFirst(request, "pdfPageSize"))
                .pdfOrientation(getFirst(request, "pdfOrientation", "orient"))
                .build();
    }

    /**
     * 获取第一个
     *
     * @param request 请求
     * @param names 名称
     * @return 获取第一个的结果
     */
    private String getFirst(ServerRequest request, String... names) {
        for (String name : names) {
            String v = request.getParam(name);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * 解析Int
     *
     * @param request 请求
     * @param names 名称
     * @return 解析int的结果
     */
    private Integer parseInt(ServerRequest request, String... names) {
        String v = getFirst(request, names);
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            log.debug("无法解析整数参数: {}", v);
            return null;
        }
    }

    /**
     * 解析Long
     *
     * @param request 请求
     * @param names 名称
     * @return 解析long的结果
     */
    private Long parseLong(ServerRequest request, String... names) {
        String v = getFirst(request, names);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析Float
     *
     * @param request 请求
     * @param names 名称
     * @return 解析float的结果
     */
    private Float parseFloat(ServerRequest request, String... names) {
        String v = getFirst(request, names);
        if (v == null) {
            return null;
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析Bool
     *
     * @param request 请求
     * @param names 名称
     * @return 解析bool的结果
     */
    private Boolean parseBool(ServerRequest request, String... names) {
        String v = getFirst(request, names);
        if (v == null) {
            return null;
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }
}
