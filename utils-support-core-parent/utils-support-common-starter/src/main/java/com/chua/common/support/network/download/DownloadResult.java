package com.chua.common.support.network.download;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
* 下载结果。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class DownloadResult {
    /** 是否成功 */
    private boolean success;
    /** 文件路径 */
    private Path file;
    /** 是否跳过（文件已存在且校验通过） */
    private boolean skipped;
    /** 跳过/成功原因 */
    private String reason;
    /** 期望 MD5 */
    private String md5;
}
