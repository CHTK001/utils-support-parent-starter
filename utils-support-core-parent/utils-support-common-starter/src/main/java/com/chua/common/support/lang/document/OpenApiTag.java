package com.chua.common.support.lang.document;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
* OpenAPI 标签分组。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class OpenApiTag {

    /**
    * tag 名（group by key）。
    */
    private String name;

    /**
    * tag 描述（OpenAPI tags[].description）。
    */
    private String description;
}
