package com.chua.common.support.lang.document;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 响应（HTTP 状态码 + 描述 + 字段）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class OpenApiResponse {

    /**
     * HTTP 状态码（"200" / "404"）。
     */
    private String code;

    /**
     * 描述（OpenAPI response.description）。
     */
    private String description;

    /**
     * 内容类型。
     */
    private String contentType = "application/json";

    /**
     * 响应字段（平铺 / 嵌套 flatten 后）。
     */
    private List<OpenApiParam> fields = new ArrayList<>();

    /**
     * 响应示例 JSON（可选）。
     */
    private String example;
}
