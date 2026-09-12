package com.chua.common.support.lang.document;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
* OpenAPI 单一接口（path + method + operation）。
*
* <p>字段命名贴近 OpenAPI 3.x 规范：{@link #method} 为 HTTP method，
* {@link #path} 为模板路径（如 {@code /users/{id}}），
* {@link #summary} 为 OpenAPI operation.summary。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class OpenApiEndpoint {

    /**
    * 所属 tag（缺省 "default"）。
     */
    private String tag = "default";

    /**
    * HTTP 请求方法（GET/POST/PUT/DELETE/PATCH/...）。
     */
    private String method;

    /**
    * 路径模板（如 /api/users/{id}）。
     */
    private String path;

    /**
    * 接口标题（summary），缺省回退到 path。
     */
    private String summary;

    /**
    * 接口描述（description）。
     */
    private String description;

    /**
    * OpenAPI operationId。
     */
    private String operationId;

    /**
    * 是否已废弃（operation.deprecated）。
     */
    private boolean deprecated;

    /**
    * 请求参数列表（query / path / header / cookie / body）。
     */
    private List<OpenApiParam> parameters = new ArrayList<>();

    /**
    * 请求 Body（可选，自描述片段）。
     */
    private OpenApiRequestBody requestBody;

    /**
    * 响应列表（key 为 HTTP 状态码，如 "200" / "404"）。
     */
    private List<OpenApiResponse> responses = new ArrayList<>();

    /**
    * 请求示例 JSON（可选，由 provider 写入）。
     */
    private String requestSample;

    /**
    * 响应示例 JSON（可选，由 provider 写入）。
     */
    private String responseSample;
}
