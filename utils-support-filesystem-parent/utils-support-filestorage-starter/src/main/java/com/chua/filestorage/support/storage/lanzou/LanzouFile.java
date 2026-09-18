package com.chua.filestorage.support.storage.lanzou;

import lombok.Builder;
import lombok.Data;

/**
* 蓝奏云网盘条目模型。
*
* <p>同时用于描述文件与文件夹，通过 {@link #directory} 区分。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class LanzouFile {

    /**
    * 条目 标识，文件对应 文件_标识，文件夹对应 文件夹_标识。
    */
    private String id;

    /**
    * 完整名称（含扩展名）。
    */
    private String name;

    /**
    * 文件大小（字节），文件夹为 0。
    */
    private long size;

    /**
    * 蓝奏云原始大小描述，如 {@code 1.2 M}。
    */
    private String sizeText;

    /**
    * 下载次数。
    */
    private long downloads;

    /**
    * 上传时间描述，如 {@code 3 天前}、{@code 2024-01-05}。
    */
    private String time;

    /**
    * 是否为文件夹。
    */
    private boolean directory;

    /**
    * 是否设置了访问密码。
    */
    private boolean encrypted;

    /**
    * 分享链接，需调用分享信息接口后填充。
    */
    private String shareUrl;

    /**
    * 分享密码，无密码时为空串。
    */
    private String sharePassword;
}
