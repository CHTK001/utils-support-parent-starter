package com.chua.common.support.network.client.yunxiao;

import lombok.Data;

/**
 * 云效制品仓库（Repository）信息。
 *
 * <p>对应云效 OpenAPI 制品仓库模块的仓库实体，包含公开性、最近更新时间、
 * 仓库模式、描述、仓库 Id、仓库名称与仓库类型等字段。</p>
 *
 * <p>字段命名与云效 OpenAPI 返回的 JSON 字段保持一致，由 Jackson 自动反序列化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class YunxiaoRepository {

    /**
     * 公开性，PRIVATE：私有仓库；INTERNAL：组织内可见
     */
    private String accessLevel;

    /**
     * 最近更新时间（毫秒时间戳字符串）
     */
    private String latestUpdate;

    /**
     * 仓库模式，可选值：Hybrid / Local / Proxy / ProxyCache / Group
     */
    private String repoCategory;

    /**
     * 仓库描述
     */
    private String repoDesc;

    /**
     * 仓库描述文件（JSON 字符串）
     */
    private String repoDescriptor;

    /**
     * 仓库 Id
     */
    private String repoId;

    /**
     * 仓库名称
     */
    private String repoName;

    /**
     * 仓库类型，可选值：GENERIC / DOCKER / MAVEN / NPM / NUGET / PYPI
     */
    private String repoType;

    /**
     * 是否收藏
     */
    private Boolean star;
}
