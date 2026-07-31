package com.chua.filestorage.support.storage.lanzou;

import lombok.Builder;
import lombok.Data;

/**
 * 蓝奏云分享页解析结果。
 *
 * @author CH
 * @since 1.0
 */
@Data
@Builder
public class LanzouShareInfo {

    /**
     * 文件名（含扩展名）。
     */
    private String fileName;

    /**
     * 文件大小（字节），无法解析时为 -1。
     */
    private long size;

    /**
     * 页面展示的大小文本，如 {@code 12.5 M}。
     */
    private String sizeText;

    /**
     * 上传时间描述。
     */
    private String time;

    /**
     * 上传者昵称。
     */
    private String uploader;

    /**
     * 解析出的下载直链。
     */
    private String downloadUrl;
}
