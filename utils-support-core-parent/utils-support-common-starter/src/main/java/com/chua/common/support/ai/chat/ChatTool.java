package com.chua.common.support.ai.chat;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * AI 工具（函数调用）定义
 *
 * <p>描述一个可被大模型调用的函数工具，包含名称、描述与参数 JSON Schema。
 * 各 {@link ChatClient} 实现据此构建对应服务商的 function calling 请求参数。
 *
 * <p>参数示例：
 * <pre>{@code
 *   ChatTool.builder()
 *       .name("get_weather")
 *       .description("查询指定城市的天气")
 *       .parameters(Map.of(
 *           "type", "object",
 *           "properties", Map.of(
 *               "city", Map.of("type", "string", "description", "城市名")
 *           ),
 *           "required", List.of("city")
 *       ))
 *       .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Builder
public class ChatTool {

    /**
     * 工具名称（模型调用时的函数标识）
     */
    private final String name;

    /**
     * 工具描述（供模型判断何时调用）
     */
    private final String description;

    /**
     * 参数 JSON Schema（type/properties/required 等），为空时表示无参数
     */
    private final Map<String, Object> parameters;
}
