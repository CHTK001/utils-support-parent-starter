package com.chua.common.support.storage.request;

import com.chua.common.support.utils.StringUtils;
import lombok.Builder;
import lombok.Data;

/**
 * 获取/下载文件请求对象。
 *
 * <p>包含下载文件所需的 Key 或文件名+路径信息。</p>
 *
 * @author CH
 * @since 1.0
 */
@Data
@Builder
public class GetObjectRequest {

    /**
     * 文件名（含扩展名）。
     */
    private String fileName;

    /**
     * 文件路径。
     */
    private String filePath;

    /**
     * 对象完整 键（路径 + 文件名），用于传递完整标识。
     */
    private String key;

    /**
     * 读取起始偏移（字节），默认 0（从头读取）。
     *
     * <p>与 {@link #length} 配合实现 HTTP Range 语义：由调用方（如文件服务过滤器）
     * 在 206 响应时只取需要的区间，避免把整份大文件读进内存。存储实现若不支持
     * 偏移读取可忽略该字段（调用方会自行 skip），支持的后端（本地文件系统、
     * 对象存储）应下推到原生 Range/seek 能力。</p>
     */
    @Builder.Default
    private long offset = 0L;

    /**
     * 读取长度（字节），默认 -1 表示读到末尾。
     *
     * <p>仅在 {@link #offset} 大于 0 或显式指定时生效；调用方负责按实际读到的
     * 字节数生成 {@code Content-Range}，因此本字段只是读取上限，不做截断校验。</p>
     */
    @Builder.Default
    private long length = -1L;

    /**
     * 获取完整的对象 键。
     *
     * @return 完整的 键（路径 + 文件名）
     */
    public String getKey() {
        if (StringUtils.isBlank(filePath)) {
            return fileName;
        }
        return filePath.endsWith("/") ? filePath + fileName : filePath + "/" + fileName;
    }
}
