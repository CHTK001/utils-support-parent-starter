package com.chua.common.support.network.client.yunxiao.constant;

/**
* 云效制品仓库类型枚举。
*
* <p>对应云效 OpenAPI 制品仓库模块的 {@code repoType} 参数取值，用于查询与删除制品时
* 指定仓库类型。</p>
*
* @author CH
* @since 4.0.0.42
 */
public enum RepoType {

    /**
    * 通用制品仓库
    */
    GENERIC,

    /**
    * Docker 镜像仓库
    */
    DOCKER,

    /**
    * Maven 制品仓库
    */
    MAVEN,

    /**
    * NPM 制品仓库
    */
    NPM,

    /**
    * NuGet 制品仓库
    */
    NUGET,

    /**
    * PyPI 制品仓库
    */
    PYPI
}
