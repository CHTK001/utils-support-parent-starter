package com.chua.common.support.lang.document;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
* OpenAPI 请求参数（query / path / header / cookie / body.field）。
*
* <p>位置（in）取 OpenAPI 标准的字符串：query / path / header / cookie / body。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class OpenApiParam {

    /**
    * 参数名。
     */
    private String name;

    /**
    * 位置（query / path / header / cookie / body）。
     */
    private String in;

    /**
    * 数据类型（string / integer / boolean / array / object / 自定义）。
     */
    private String type;

    /**
    * 是否必填。
     */
    private boolean required;

    /**
    * 描述。
     */
    private String description;

    /**
    * 示例值（字符串，渲染时按 JSON 嵌入）。
     */
    private String example;
}
