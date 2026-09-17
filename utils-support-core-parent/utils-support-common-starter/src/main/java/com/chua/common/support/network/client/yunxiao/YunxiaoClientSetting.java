package com.chua.common.support.network.client.yunxiao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 云效（Alibaba Cloud DevOps / Yunxiao）客户端配置。
*
* <p>承载访问云效 OpenAPI 所需的全部连接信息：服务接入点域名、个人访问令牌（PAT）、
* 企业 Id（organizationId，仅中心版需要）以及版本（中心版 / Region 版）等。</p>
*
* <p><b>中心版与 Region 版差异：</b></p>
* <ul>
*   <li>中心版：请求路径含 {@code /organizations/{organizationId}} 前缀，需配置 organizationId</li>
*   <li>Region 版：请求路径不含组织前缀，无需 organizationId</li>
* </ul>
*
* <p><b>典型用法：</b></p>
* <pre>{@code
* YunxiaoClientSetting setting = YunxiaoClientSetting.builder()
*         .domain("devops.cn-hangzhou.aliyuncs.com")
*         .token("pt-xxxx")
*         .organizationId("60d54f3daccf2bbd6659f3ad")
*         .central(true)
*         .build();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YunxiaoClientSetting {

    /**
    * 服务接入点域名，如 {@code "devops.cn-hangzhou.aliyuncs.com"}（不含协议前缀）
    */
    private String domain;

    /**
    * 个人访问令牌（Personal Access Token，PAT），通过请求头 {@code x-yunxiao-token} 传递
    */
    private String token;

    /**
    * 企业 Id（organizationId），仅中心版需要；Region 版可为空
    */
    private String organizationId;

    /**
    * 是否为云效中心版（中心版请求路径含组织前缀）；false 表示 Region 版
    */
    private boolean central;
}
