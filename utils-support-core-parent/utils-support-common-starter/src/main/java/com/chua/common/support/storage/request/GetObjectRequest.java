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
