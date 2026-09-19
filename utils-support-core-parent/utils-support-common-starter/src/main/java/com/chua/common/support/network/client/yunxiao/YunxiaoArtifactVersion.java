package com.chua.common.support.network.client.yunxiao;

import lombok.Data;

/**
 * 云效制品版本（Artifact Version）信息。
 *
 * <p>对应云效 OpenAPI 制品版本实体，包含创建时间、创建人、最新下载时间、
 * 制品版本 Id、修改人与修改时间等字段。</p>
 *
 * <p>字段命名与云效 OpenAPI 返回的 JSON 字段保持一致，由 Jackson 自动反序列化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class YunxiaoArtifactVersion {

    /**
     * 创建时间（毫秒时间戳）
     */
    private Long createTime;

    /**
     * 创建人
     */
    private String creator;

    /**
     * 最新下载时间（毫秒时间戳）
     */
    private Long gmtDownload;

    /**
     * 制品版本 Id
     */
    private Long id;

    /**
     * 修改人
     */
    private String modifier;

    /**
     * 修改时间（毫秒时间戳）
     */
    private Long updateTime;

    /**
     * 版本号
     */
    private String version;
}
