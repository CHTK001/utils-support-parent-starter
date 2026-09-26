package com.chua.common.support.lang.document;

/**
 * OpenAPI 请求参数（query / path / header / cookie / body.field）。
 * <p>位置（in）取 OpenAPI 标准的字符串：query / path / header / cookie / body。</p>
 *
 * @param name 参数名。
 * @param in 位置（query / path / header / cookie / body）。
 * @param type 数据类型（string / integer / boolean / array / object /
 *             自定义）。
 * @param required 是否必填。
 * @param description 描述。
 * @param example 示例值（字符串，渲染时按 JSON
 *                嵌入）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public record OpenApiParam(
        String name,
        String in,
        String type,
        boolean required,
        String description,
        String example
) {
}
