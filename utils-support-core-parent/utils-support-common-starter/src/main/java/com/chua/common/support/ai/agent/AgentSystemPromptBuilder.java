package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.skill.SkillArgumentSchema;
import com.chua.common.support.ai.skill.SkillDefinition;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * Agent 系统提示词构建器
 *
 * <p>负责将主 Agent 的指令（instruction）与子 Agent 路由描述、技能描述自动拼接，
 * 生成完整的 system prompt，使大模型能够感知可用的子 Agent 和技能并进行智能路由。
 *
 * <h3>生成的 prompt 结构</h3>
 * <pre>
 * {主 Agent 的 instruction}
 *
 * ---
 * ## 可用子 Agent                          ← 路由表
 *
 * | Agent ID | 名称 | 描述 |
 * |----------|------|------|
 * | dev-agent | 开发助手 | 代码开发专家 |
 *
 * ## 路由规则                              ← 告诉 LLM 如何选择
 *
 * 1. 分析用户输入，判断最匹配的子 Agent
 * 2. 在回复中明确输出要使用的 Agent ID
 * 3. 框架将自动使用该 Agent 的专属配置执行任务
 * 4. 若没有合适的子 Agent，由当前 Agent 直接处理
 *
 * ---
 * ## 可用技能                              ← 技能描述
 *
 * ### get_weather
 * 描述：查询天气
 * 参数：city (string, 必填)
 * 调用格式：[SKILL:get_weather](city=北京)
 *
 * ## 技能调用规则                          ← 告诉 LLM 如何调用技能
 *
 * 1. 分析用户请求，判断是否匹配某个技能
 * 2. 若匹配，输出 [SKILL:技能名](参数=值) 格式
 * 3. 框架将自动执行对应技能并返回结果
 * </pre>
 *
 * <h3>调用链</h3>
 * <pre>
 *   AgentDefinition 构造
 *     → AgentSystemPromptBuilder.build(instruction, subAgents, skills)
 *     → 自动拼接路由表 + 技能描述 + 调用规则
 *     → 存入 AgentDefinition.systemPrompt 字段
 *     → Agent 实现类通过 getSystemPrompt() 获取并传给 ChatClient
 * </pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@SuppressWarnings({"NullAway", "unchecked"})
@NullUnmarked
public final class AgentSystemPromptBuilder {

    private AgentSystemPromptBuilder() {
    }

    /**
     * 生成技能描述段（Markdown 格式）
     *
     * <p>将所有已注册技能的名称、描述和参数 schema 输出为结构化文本，
     * 使 LLM 能够识别何时应该调用哪个技能，以及如何构造调用参数。
     *
     * @param skills 已注册的技能定义列表
     * @return 技能描述段文本
     */
    public static String buildSkillsSection(Map<String, SkillDefinition> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用技能\n\n");
        sb.append("当用户请求匹配以下技能时，请输出对应的技能调用指令。\n");
        sb.append("技能调用格式：`[SKILL:技能名](参数1=值1,参数2=值2)`\n\n");

        for (Map.Entry<String, SkillDefinition> entry : skills.entrySet()) {
            SkillDefinition skill = entry.getValue();
            sb.append("### ").append(skill.getName()).append("\n\n");
            sb.append("**描述：** ").append(skill.getDescription()).append("\n\n");

            if (skill.getArguments() != null && !skill.getArguments().isEmpty()) {
                sb.append("**参数：**\n\n");
                sb.append("| 参数 | 类型 | 必填 | 说明 |\n");
                sb.append("|------|------|------|------|\n");
                for (SkillArgumentSchema arg : skill.getArguments()) {
                    sb.append("| ").append(arg.getName())
                      .append(" | ").append(arg.getType())
                      .append(" | ").append(arg.isRequired() ? "是" : "否")
                      .append(" | ").append(arg.getDescription())
                      .append(" |\n");
                }
                sb.append("\n");
            }

            sb.append("**调用示例：**\n```\n");
            sb.append("[SKILL:").append(skill.getName()).append("]");
            if (skill.getArguments() != null && !skill.getArguments().isEmpty()) {
                sb.append("(");
                for (int i = 0; i < skill.getArguments().size(); i++) {
                    SkillArgumentSchema arg = skill.getArguments().get(i);
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(arg.getName()).append("=值");
                }
                sb.append(")");
            }
            sb.append("\n```\n\n");
        }

        return sb.toString();
    }

    /**
     * 生成 MCP 工具描述段（Markdown 格式）
     *
     * <p>将所有已注册 MCP 工具的名称、描述和参数 schema 输出为结构化文本，
     * 使 LLM 能够识别可调用的外部工具并构造正确的调用参数。
     *
     * @param tools MCP 工具描述符列表
     * @return MCP 工具描述段文本
     */
    public static String buildMcpToolsSection(List<McpToolDescriptor> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用 MCP 工具\n\n");
        sb.append("你可以调用以下外部工具来完成任务。");
        sb.append("调用格式：`[MCP:工具名](参数JSON)`\n\n");

        for (McpToolDescriptor tool : tools) {
            sb.append("### ").append(escape(tool.getName())).append("\n\n");
            sb.append("**描述：** ").append(escape(tool.getDescription())).append("\n\n");

            Map<String, Object> schema = tool.getInputSchema();
            if (schema != null && !schema.isEmpty()) {
                sb.append("**参数（JSON Schema）：**\n```json\n");
                sb.append(formatJsonSchema(schema));
                sb.append("\n```\n\n");
            }

            sb.append("**调用示例：**\n```\n");
            sb.append("[MCP:").append(escape(tool.getName())).append("]({\"arg1\": \"值\"})\n");
            sb.append("```\n\n");
        }

        sb.append("### 调用规则\n\n");
        sb.append("1. 分析用户请求，判断是否需要调用 MCP 工具\n");
        sb.append("2. 若需要，输出 `[MCP:工具名]({\"参数名\": \"参数值\"})` 格式\n");
        sb.append("3. 框架将自动执行工具并返回结果\n");
        sb.append("4. 根据工具结果生成最终回答\n");

        return sb.toString();
    }

    /**
     * 将 JSON Schema Map 格式化为缩进 JSON 字符串
     */
    private static String formatJsonSchema(Map<String, Object> schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\n  \"").append(escapeJson(entry.getKey())).append("\": ");
            sb.append(formatJsonValue(entry.getValue(), "  "));
        }
        sb.append("\n}");
        return sb.toString();
    }

    private static String formatJsonValue(Object value, String indent) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<String, Object> e : map.entrySet()) {
                sb.append("\n  ").append(indent).append("\"").append(escapeJson(e.getKey())).append("\": ");
                sb.append(formatJsonValue(e.getValue(), indent + "  "));
                sb.append(",");
            }
            if (!map.isEmpty()) sb.setLength(sb.length() - 1);
            sb.append("\n").append(indent).append("}");
            return sb.toString();
        }
        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            StringBuilder sb = new StringBuilder("[");
            for (Object item : list) {
                sb.append("\n  ").append(indent).append(formatJsonValue(item, indent + "  "));
                sb.append(",");
            }
            if (!list.isEmpty()) sb.setLength(sb.length() - 1);
            sb.append("\n").append(indent).append("]");
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 构建完整的系统提示词（仅含子 Agent 路由）
     *
     * @param instruction 主 Agent 的系统指令
     * @param subAgents   已注册的子 Agent 定义列表
     * @return 完整的系统提示词
     */
    public static String build(String instruction, List<AgentDefinition> subAgents) {
        return build(instruction, subAgents, null);
    }

    /**
     * 构建完整的系统提示词（含子 Agent 路由 + 技能描述 + MCP 工具）
     *
     * <p>将 instruction、子 Agent 路由表、MCP 工具描述和技能描述自动拼接。
     *
     * @param instruction 主 Agent 的系统指令
     * @param subAgents   已注册的子 Agent 定义列表
     * @param skills      已注册的技能定义映射
     * @param mcpTools    MCP 工具描述符列表
     * @return 完整的系统提示词
     */
    public static String build(String instruction, List<AgentDefinition> subAgents,
                                Map<String, SkillDefinition> skills,
                                List<McpToolDescriptor> mcpTools) {
        String base = instruction != null ? instruction : "";
        StringBuilder sb = new StringBuilder(base);

        if (subAgents != null && !subAgents.isEmpty()) {
            sb.append("\n\n---\n").append(buildSubAgentSection(subAgents));
        }

        String mcpSection = buildMcpToolsSection(mcpTools);
        if (!mcpSection.isEmpty()) {
            sb.append("\n\n---\n").append(mcpSection);
        }

        String skillsSection = buildSkillsSection(skills);
        if (!skillsSection.isEmpty()) {
            sb.append("\n\n---\n").append(skillsSection);
        }

        if (skills != null && !skills.isEmpty()) {
            sb.append("\n## 技能调用规则\n\n");
            sb.append("1. 分析用户请求，判断是否匹配某个技能\n");
            sb.append("2. 若匹配，输出 `[SKILL:技能名](参数=值)` 格式的调用指令\n");
            sb.append("3. 框架将自动执行对应技能并返回结果\n");
            sb.append("4. 若无匹配技能，直接回答用户问题\n");
        }

        return sb.toString();
    }

    /**
     * 构建完整的系统提示词（含子 Agent 路由 + 技能描述）
     *
     * @param instruction 主 Agent 的系统指令
     * @param subAgents   已注册的子 Agent 定义列表
     * @param skills      已注册的技能定义映射
     * @return 完整的系统提示词
     */
    public static String build(String instruction, List<AgentDefinition> subAgents,
                                Map<String, SkillDefinition> skills) {
        String base = instruction != null ? instruction : "";
        StringBuilder sb = new StringBuilder(base);

        // 子 Agent 路由段
        if (subAgents != null && !subAgents.isEmpty()) {
            sb.append("\n\n---\n").append(buildSubAgentSection(subAgents));
        }

        // 技能描述段
        String skillsSection = buildSkillsSection(skills);
        if (!skillsSection.isEmpty()) {
            sb.append("\n\n---\n").append(skillsSection);
        }

        // 技能调用规则
        if (skills != null && !skills.isEmpty()) {
            sb.append("\n## 技能调用规则\n\n");
            sb.append("1. 分析用户请求，判断是否匹配某个技能\n");
            sb.append("2. 若匹配，输出 `[SKILL:技能名](参数=值)` 格式的调用指令\n");
            sb.append("3. 框架将自动执行对应技能并返回结果\n");
            sb.append("4. 若无匹配技能，直接回答用户问题\n");
        }

        return sb.toString();
    }

    /**
     * 生成子 Agent 路由段（Markdown 格式）
     *
     * @param subAgents 子 Agent 定义列表
     * @return 路由段文本
     */
    static String buildSubAgentSection(List<AgentDefinition> subAgents) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用子 Agent\n\n");
        sb.append("你可以将任务委派给以下子 Agent 执行。\n");
        sb.append("每个子 Agent 拥有独立的工具集和专业能力。\n\n");

        // Markdown 表格
        sb.append("| Agent ID | 名称 | 描述 |\n");
        sb.append("|----------|------|------|\n");
        for (AgentDefinition agent : subAgents) {
            sb.append("| ")
              .append(escape(agent.getId()))
              .append(" | ")
              .append(escape(agent.getName()))
              .append(" | ")
              .append(escape(agent.getDescription()))
              .append(" |\n");
        }

        // 路由规则
        sb.append("\n## 路由规则\n\n");
        sb.append("1. 分析用户输入，判断最匹配的子 Agent\n");
        sb.append("2. 在回复中明确输出要使用的 Agent ID\n");
        sb.append("3. 框架将自动使用该 Agent 的专属 ChatClient、MCP 工具和技能执行任务\n");
        sb.append("4. 若没有合适的子 Agent，由当前 Agent 直接处理\n");

        return sb.toString();
    }

    /**
     * 生成纯文本格式的子 Agent 路由说明
     *
     * @param subAgents 子 Agent 定义列表
     * @return 纯文本格式的路由说明
     */
    public static String buildSubAgentSectionPlainText(List<AgentDefinition> subAgents) {
        if (subAgents == null || subAgents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用子 Agent\n\n");
        sb.append("你可以将任务委派给以下子 Agent 执行。\n\n");

        for (int i = 0; i < subAgents.size(); i++) {
            AgentDefinition agent = subAgents.get(i);
            sb.append(i + 1).append(". [")
              .append(agent.getId()).append("] ")
              .append(agent.getName()).append(" — ")
              .append(agent.getDescription()).append("\n");
        }

        sb.append("\n路由规则：分析用户输入，选择最匹配的子 Agent。\n");
        sb.append("框架将自动使用该 Agent 的专属 ChatClient、MCP 工具和技能执行任务。\n");
        sb.append("若没有合适的子 Agent，由当前 Agent 直接处理。\n");

        return sb.toString();
    }

    /**
     * 转义 Markdown 特殊字符
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }
}