package com.chua.common.support.lang.document;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
* OpenAPI 请求 Body 描述。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class OpenApiRequestBody {

    /**
    * 描述。
    */
    private String description;

    /**
    * 是否必填。
    */
    private boolean required;

    /**
    * 内容类型（如 application/json），通常仅一个。
    */
    private String contentType = "application/json";

    /**
    * Body 类型（string / object / array / 自定义）。
    */
    private String type;

    /**
    * Body 示例 JSON（可选）。
    */
    private String example;
}
