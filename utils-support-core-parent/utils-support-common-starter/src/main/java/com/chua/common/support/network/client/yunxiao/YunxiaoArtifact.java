package com.chua.common.support.network.client.yunxiao;

import lombok.Data;

import java.util.List;

/**
 * 云效制品（Artifact）信息。
 *
 * <p>对应云效 OpenAPI 制品仓库模块的单个制品实体，包含下载次数、最近更新时间、
 * 模块名、组织信息以及制品版本列表等字段。</p>
 *
 * <p>字段命名与云效 OpenAPI 返回的 JSON 字段保持一致，由 Jackson 自动反序列化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class YunxiaoArtifact {

    /**
     * 下载次数
     */
    private Integer downloadCount;

    /**
     * 最近下载时间（毫秒时间戳）
     */
    private Long gmtDownload;

    /**
     * 制品 Id
     */
    private Long id;

    /**
     * 最近更新时间（毫秒时间戳）
     */
    private Long latestUpdate;

    /**
     * 模块名
     */
    private String module;

    /**
     * 组织信息
     */
    private String organization;

    /**
     * 仓库 Id
     */
    private String repositoryId;

    /**
     * 制品版本列表
     */
    private List<YunxiaoArtifactVersion> versions;
}
