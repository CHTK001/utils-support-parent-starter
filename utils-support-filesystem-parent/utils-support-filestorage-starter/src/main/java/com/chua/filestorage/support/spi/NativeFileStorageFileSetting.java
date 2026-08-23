package com.chua.filestorage.support.spi;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.bridge.RustFileStorageBridge;
import com.chua.filestorage.support.operation.FileOperationSetting;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Rust 实现的文件存储 URL 参数设置。
 *
 * <p>优先使用 Rust native 库解析 URL 参数。
 * 若 Rust 库未初始化，自动降级到 JDK 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("native")
public class NativeFileStorageFileSetting implements FileStorageFileSetting {

    @Override
    /** Capabilities */
    public List<String> capabilities() {
        if (!RustFileStorageBridge.isInitialized()) {
            log.debug("[NativeFileStorageFileSetting] Rust 库未初始化，降级 JDK");
            return new JdkFileStorageFileSetting().capabilities();
        }
        return RustFileStorageBridge.nativeCapabilities();
    }

    @Override
    /** 解析 */
    public FileOperationSetting parse(ServerRequest request) {
        if (!RustFileStorageBridge.isInitialized()) {
            log.debug("[NativeFileStorageFileSetting] Rust 库未初始化，降级 JDK 解析");
            return new JdkFileStorageFileSetting().parse(request);
        }
        // 将 request 所有参数转为 JSON 字符串传给 native
        Map<String, String> params = request.getParams();
        String json = paramsToJson(params);
        return RustFileStorageBridge.nativeParseParams(json);
    }

    /** ParamsToJson */
    private String paramsToJson(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        params.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                if (sb.length() > 1) {
                    sb.append(",");
                }
                sb.append("\"").append(k).append("\":\"").append(v.replace("\"", "\\\"")).append("\"");
            }
        });
        sb.append("}");
        return sb.toString();
    }
}
