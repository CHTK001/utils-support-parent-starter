package com.chua.common.support.ai.agent;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Agent 工具（function calling）定义。
 *
 * <p>把「进程内可执行的 Java 能力」以统一形态交给各 Agent 实现：
 * 名称、描述、参数 JSON Schema 与执行器。langchain4j / agentscope 会把它注册进自己的
 * 工具循环；opencode 是独立进程，无法回调本进程的 Java 代码，因此 {@code tool(..)}
 * 会显式失败并提示改用 MCP 服务或 SKILL.md。</p>
 *
 * <p>参数 Schema 与 {@code ChatTool} 同形：</p>
 * <pre>{@code
 * AgentTool.builder()
 *     .name("read_file")
 *     .description("读取文本文件")
 *     .parameters(Map.of(
 *         "type", "object",
 *         "properties", Map.of("path", Map.of("type", "string", "description", "文件路径")),
 *         "required", List.of("path")))
 *     .executor(args -> Files.readString(Path.of(String.valueOf(args.get("path")))))
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Getter
public final class AgentTool {

    /**
     * 工具名称（模型调用时的函数标识，须与模型允许的字符集一致）
     */
    private final String name;

    /**
     * 工具描述（供模型判断何时调用）
     */
    private final String description;

    /**
     * 参数 JSON Schema（type/properties/required），为空表示无参数
     */
    private final Map<String, Object> parameters;

    /**
     * 执行器
     */
    private final AgentToolExecutor executor;

    /**
     * 构造工具定义。
     *
     * @param name        名称，不能为空
     * @param description 描述，可为 null
     * @param parameters  参数 JSON Schema，可为 null
     * @param executor    执行器，不能为 null
     */
    @Builder
    private AgentTool(String name, String description, Map<String, Object> parameters,
                      AgentToolExecutor executor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (executor == null) {
            throw new IllegalArgumentException("工具执行器不能为空（name=" + name + "）");
        }
        this.name = name.trim();
        this.description = description;
        this.parameters = parameters;
        this.executor = executor;
    }
}
