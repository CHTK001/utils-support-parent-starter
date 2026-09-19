package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.skill.SkillArgumentSchema;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.utils.ObjectUtils;
import com.chua.common.support.utils.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Agent 系统提示词构建器
 *
 * <p>负责将主 Agent 的指令（instruction）与子 Agent 路由描述、技能描述、MCP 工具描述自动拼接，
 * 生成完整的 system prompt，使大模型能够感知可用的子 Agent、技能和外部工具并进行智能路由。</p>
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
 * @since 4.0.0.42
 */
public final class AgentSystemPromptBuilder {

    /**
     * Markdown 分隔线
     */
    private static final String MD_SEPARATOR = "\n\n---\n";

    /**
     * 换行符
     */
    private static final String NEW_LINE = "\n";

    /**
     * 段标题前缀
     */
    private static final String SECTION_PREFIX = "## ";

    /**
     * 子标题前缀
     */
    private static final String SUB_SECTION_PREFIX = "### ";

    /**
     * 技能段标题
     */
    private static final String SKILLS_HEADER = "## 可用技能";

    /**
     * 技能段说明
     */
    private static final String SKILLS_DESC =
            "当用户请求匹配以下技能时，请输出对应的技能调用指令。\n技能调用格式：`[SKILL:技能名](参数1=值1,参数2=值2)`";

    /**
     * 参数表格头
     */
    private static final String ARG_TABLE_HEADER = "| 参数 | 类型 | 必填 | 说明 |";

    /**
     * 参数表格分隔线
     */
    private static final String ARG_TABLE_SEPARATOR = "|------|------|------|------|";

    /**
     * 必填：是
     */
    private static final String REQUIRED_YES = "是";

    /**
     * 必填：否
     */
    private static final String REQUIRED_NO = "否";

    /**
     * 调用示例前缀
     */
    private static final String CALL_SAMPLE_PREFIX = "**调用示例：**\n```\n";

    /**
     * 代码块结束
     */
    private static final String CODE_BLOCK_END = "\n```\n\n";

    /**
     * MCP 工具段标题
     */
    private static final String MCP_HEADER = "## 可用 MCP 工具";

    /**
     * MCP 工具段说明
     */
    private static final String MCP_DESC =
            "你可以调用以下外部工具来完成任务。\n调用格式：`[MCP:工具名](参数JSON)`";

    /**
     * 参数描述前缀
     */
    private static final String DESC_PREFIX = "**描述：** ";

    /**
     * 参数段落前缀
     */
    private static final String ARG_PREFIX = "**参数：**\n\n";

    /**
     * JSON Schema 参数前缀
     */
    private static final String JSON_SCHEMA_PREFIX = "**参数（JSON Schema）：**\n```json\n";

    /**
     * MCP 调用规则标题
     */
    private static final String MCP_RULE_HEADER = "### 调用规则";

    /**
     * MCP 调用规则条目（按行索引）
     */
    private static final String MCP_RULE_1 = "1. 分析用户请求，判断是否需要调用 MCP 工具";
    /** MCP 工具调用规则第二条 */
    private static final String MCP_RULE_2 = "2. 若需要，输出 `[MCP:工具名]({\"参数名\": \"参数值\"})` 格式";
    /** MCP 工具调用规则第三条 */
    private static final String MCP_RULE_3 = "3. 框架将自动执行工具并返回结果";
    /** MCP 工具调用规则第四条 */
    private static final String MCP_RULE_4 = "4. 根据工具结果生成最终回答";

    /**
     * 子 Agent 段标题
     */
    private static final String SUB_AGENT_HEADER = "## 可用子 Agent";

    /**
     * 子 Agent 段说明
     */
    private static final String SUB_AGENT_DESC =
            "你可以将任务委派给以下子 Agent 执行。\n每个子 Agent 拥有独立的工具集和专业能力。";

    /**
     * 子 Agent 表格头
     */
    private static final String SUB_AGENT_TABLE_HEADER = "| Agent ID | 名称 | 描述 |";

    /**
     * 子 Agent 表格分隔线
     */
    private static final String SUB_AGENT_TABLE_SEPARATOR = "|----------|------|------|";

    /**
     * 路由规则标题
     */
    private static final String ROUTE_RULE_HEADER = "## 路由规则";

    /**
     * 路由规则条目（按行索引）
     */
    private static final String ROUTE_RULE_1 = "1. 分析用户输入，判断最匹配的子 Agent";
    /** Agent 路由规则第二条 */
    private static final String ROUTE_RULE_2 = "2. 在回复中明确输出要使用的 Agent ID";
    /** Agent 路由规则第三条 */
    private static final String ROUTE_RULE_3 =
            "3. 框架将自动使用该 Agent 的专属 ChatClient、MCP 工具和技能执行任务";
    /** Agent 路由规则第四条 */
    private static final String ROUTE_RULE_4 = "4. 若没有合适的子 Agent，由当前 Agent 直接处理";

    /**
     * 技能调用规则标题
     */
    private static final String SKILL_RULE_HEADER = "## 技能调用规则";

    /**
     * 技能调用规则条目（按行索引）
     */
    private static final String SKILL_RULE_1 = "1. 分析用户请求，判断是否匹配某个技能";
    /** 技能调用规则第二条 */
    private static final String SKILL_RULE_2 = "2. 若匹配，输出 `[SKILL:技能名](参数=值)` 格式的调用指令";
    /** 技能调用规则第三条 */
    private static final String SKILL_RULE_3 = "3. 框架将自动执行对应技能并返回结果";
    /** 技能调用规则第四条 */
    private static final String SKILL_RULE_4 = "4. 若无匹配技能，直接回答用户问题";

    /**
     * 纯文本模式子 Agent 段说明
     */
    private static final String SUB_AGENT_PLAIN_DESC = "你可以将任务委派给以下子 Agent 执行。";

    /**
     * 纯文本路由规则
     */
    private static final String PLAIN_ROUTE_RULE =
            "\n路由规则：分析用户输入，选择最匹配的子 Agent。\n"
                    + "框架将自动使用该 Agent 的专属 ChatClient、MCP 工具和技能执行任务。\n"
                    + "若没有合适的子 Agent，由当前 Agent 直接处理。\n";

    /**
     * 子 Agent 条目连接符：—
     */
    private static final String DASH = " — ";

    /**
     * 表格单元格分隔符
     */
    private static final String CELL_SEPARATOR = " | ";

    /**
     * 行尾换行
     */
    private static final String ROW_NEW_LINE = " |\n";

    /**
     * 空字符串
     */
    private static final String EMPTY_STRING = "";

    /**
     * 表格换行占位
     */
    private static final String TABLE_ROW_NEW_LINE = "\n";

    /**
     * 表格行后缀
     */
    private static final String TABLE_LINE_SUFFIX = " |\n";

    /**
     * 参数示例占位值
     */
    private static final String ARG_PLACEHOLDER_VALUE = "值";

    /**
     * 序号 1
     */
    private static final int INDEX_OFFSET = 1;

    /**
     * Markdown 表格列分隔符
     */
    private static final String MD_PIPE = "|";

    /**
     * Markdown 表格列分隔符转义
     */
    private static final String MD_PIPE_ESCAPED = "\\|";

    /**
     * 换行替换为空格
     */
    private static final String SPACE = " ";

    /**
     * 段落间隔（两个换行）
     */
    private static final String PARAGRAPH_BREAK = "\n\n";

    /**
     * JSON 大括号起始
     */
    private static final String JSON_OBJECT_START = "{";

    /**
     * JSON 大括号结束
     */
    private static final String JSON_OBJECT_END = "}";

    /**
     * JSON 数组起始
     */
    private static final String JSON_ARRAY_START = "[";

    /**
     * JSON 数组结束
     */
    private static final String JSON_ARRAY_END = "]";

    /**
     * JSON null 字面量
     */
    private static final String JSON_NULL = "null";

    /**
     * JSON 双引号
     */
    private static final String JSON_QUOTE = "\"";

    /**
     * JSON 键值分隔符
     */
    private static final String JSON_KEY_VALUE_SEPARATOR = "\": ";

    /**
     * JSON 字段分隔符
     */
    private static final String JSON_FIELD_SEPARATOR = ",";

    /**
     * JSON 行首缩进（2 空格）
     */
    private static final String JSON_INDENT_2 = "  ";

    /**
     * JSON 行前缀（换行+缩进）
     */
    private static final String JSON_LINE_PREFIX_2 = "\n  ";

    /**
     * JSON 大括号结尾（换行+缩进+大括号）
     */
    private static final String JSON_OBJECT_END_INDENTED_2 = "\n  }";

    /**
     * JSON 数组结尾（换行+缩进+方括号）
     */
    private static final String JSON_ARRAY_END_INDENTED_2 = "\n  ]";

    /**
     * JSON 顶层结束（换行+大括号）
     */
    private static final String JSON_TOP_END = "\n}";

    /**
     * JSON 转义：反斜杠
     */
    private static final String JSON_ESCAPE_BACKSLASH = "\\\\";

    /**
     * JSON 转义：双引号
     */
    private static final String JSON_ESCAPE_QUOTE = "\\\"";

    /**
     * JSON 转义：换行
     */
    private static final String JSON_ESCAPE_NEW_LINE = "\\n";

    /**
     * JSON 转义：回车
     */
    private static final String JSON_ESCAPE_CARRIAGE = "\\r";

    /**
     * JSON 转义：制表符
     */
    private static final String JSON_ESCAPE_TAB = "\\t";

    /**
     * MCP 工具调用示例模板
     */
    private static final String MCP_CALL_SAMPLE_TEMPLATE = "[MCP:%s]({\"arg1\": \"值\"})\n";

    /**
     * 技能调用前缀
     */
    private static final String SKILL_CALL_PREFIX = "[SKILL:";

    /**
     * 技能调用后缀（不含参数）
     */
    private static final String SKILL_CALL_SUFFIX = "]";

    /**
     * 技能调用左括号
     */
    private static final String SKILL_CALL_LEFT_PAREN = "(";

    /**
     * 技能调用右括号
     */
    private static final String SKILL_CALL_RIGHT_PAREN = ")";

    /**
     * 技能参数连接符
     */
    private static final String SKILL_ARG_SEPARATOR = ",";

    /**
     * 技能参数赋值符
     */
    private static final String SKILL_ARG_ASSIGN = "=";

    /**
     * 序号加点前缀
     */
    private static final String INDEX_DOT = ". ";

    /**
     * 纯文本模式子 Agent 条目前缀（左方括号）
     */
    private static final String BRACKET_LEFT = "[";

    /**
     * 纯文本模式子 Agent 条目后缀（右方括号）
     */
    private static final String BRACKET_RIGHT = "]";

    /** 创建 AgentSystemPromptBuilder 实例 */
    private AgentSystemPromptBuilder() {
    }

    /**
     * 生成技能描述段（Markdown 格式）
     *
     * <p>将所有已注册技能的名称、描述和参数 schema 输出为结构化文本，
     * 使 LLM 能够识别何时应该调用哪个技能，以及如何构造调用参数。</p>
     *
     * @param skills 已注册的技能定义列表
     * @return 技能描述段文本
     */
    public static String buildSkillsSection(Map<String, SkillDefinition> skills) {
        if (MapUtils.isEmpty(skills)) {
            return EMPTY_STRING;
        }

        StringBuilder sb = new StringBuilder();
        appendSkillsHeader(sb);
        appendSkillsTable(sb, skills);
        return sb.toString();
    }

    /**
     * 生成 MCP 工具描述段（Markdown 格式）
     *
     * <p>将所有已注册 MCP 工具的名称、描述和参数 schema 输出为结构化文本，
     * 使 LLM 能够识别可调用的外部工具并构造正确的调用参数。</p>
     *
     * @param tools MCP 工具描述符列表
     * @return MCP 工具描述段文本
     */
    public static String buildMcpToolsSection(List<McpToolDescriptor> tools) {
        if (CollectionUtils.isEmpty(tools)) {
            return EMPTY_STRING;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(MCP_HEADER).append(PARAGRAPH_BREAK);
        sb.append(MCP_DESC).append(PARAGRAPH_BREAK);

        appendMcpToolEntries(sb, tools);
        appendMcpCallRules(sb);

        return sb.toString();
    }

    /**
     * 构建完整的系统提示词（仅含子 Agent 路由）
     *
     * @param instruction 主 Agent 的系统指令
     * @param subAgents   已注册的子 Agent 定义列表
     * @return 完整的系统提示词
     */
    public static String build(String instruction, List<AgentDefinition> subAgents) {
        return build(instruction, subAgents, null, null);
    }

    /**
     * 构建完整的系统提示词（含子 Agent 路由 + 技能描述 + MCP 工具）
     *
     * <p>将 instruction、子 Agent 路由表、MCP 工具描述和技能描述自动拼接。</p>
     *
     * @param instruction 主 Agent 的系统指令
     * @param subAgents   已注册的子 Agent 定义列表
     * @param skills      已注册的技能定义映射
     * @param mcpTools    MCP 工具描述符列表
     * @return 完整的系统提示词
     */
    public static String build(String instruction,
                               List<AgentDefinition> subAgents,
                               Map<String, SkillDefinition> skills,
                               List<McpToolDescriptor> mcpTools) {
        StringBuilder sb = new StringBuilder();
        sb.append(ObjectUtils.isNull(instruction) ? EMPTY_STRING : instruction);

        // 子 Agent 路由段
        if (CollectionUtils.isNotEmpty(subAgents)) {
            sb.append(MD_SEPARATOR).append(buildSubAgentSection(subAgents));
        }

        // MCP 工具描述段
        String mcpSection = buildMcpToolsSection(mcpTools);
        if (StringUtils.isNotEmpty(mcpSection)) {
            sb.append(MD_SEPARATOR).append(mcpSection);
        }

        // 技能描述段
        String skillsSection = buildSkillsSection(skills);
        if (StringUtils.isNotEmpty(skillsSection)) {
            sb.append(MD_SEPARATOR).append(skillsSection);
        }

        // 技能调用规则
        if (!MapUtils.isEmpty(skills)) {
            sb.append(NEW_LINE).append(SKILL_RULE_HEADER).append(PARAGRAPH_BREAK);
            sb.append(SKILL_RULE_1).append(NEW_LINE);
            sb.append(SKILL_RULE_2).append(NEW_LINE);
            sb.append(SKILL_RULE_3).append(NEW_LINE);
            sb.append(SKILL_RULE_4);
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
    public static String build(String instruction,
                               List<AgentDefinition> subAgents,
                               Map<String, SkillDefinition> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append(ObjectUtils.isNull(instruction) ? EMPTY_STRING : instruction);

        // 子 Agent 路由段
        if (CollectionUtils.isNotEmpty(subAgents)) {
            sb.append(MD_SEPARATOR).append(buildSubAgentSection(subAgents));
        }

        // 技能描述段
        String skillsSection = buildSkillsSection(skills);
        if (StringUtils.isNotEmpty(skillsSection)) {
            sb.append(MD_SEPARATOR).append(skillsSection);
        }

        // 技能调用规则
        if (!MapUtils.isEmpty(skills)) {
            sb.append(NEW_LINE).append(SKILL_RULE_HEADER).append(PARAGRAPH_BREAK);
            sb.append(SKILL_RULE_1).append(NEW_LINE);
            sb.append(SKILL_RULE_2).append(NEW_LINE);
            sb.append(SKILL_RULE_3).append(NEW_LINE);
            sb.append(SKILL_RULE_4);
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
        sb.append(SUB_AGENT_HEADER).append(PARAGRAPH_BREAK);
        sb.append(SUB_AGENT_DESC).append(PARAGRAPH_BREAK);

        appendSubAgentTable(sb, subAgents);
        appendRouteRules(sb);

        return sb.toString();
    }

    /**
     * 生成纯文本格式的子 Agent 路由说明
     *
     * @param subAgents 子 Agent 定义列表
     * @return 纯文本格式的路由说明
     */
    public static String buildSubAgentSectionPlainText(List<AgentDefinition> subAgents) {
        if (CollectionUtils.isEmpty(subAgents)) {
            return EMPTY_STRING;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(SUB_AGENT_HEADER).append(PARAGRAPH_BREAK);
        sb.append(SUB_AGENT_PLAIN_DESC).append(PARAGRAPH_BREAK);

        appendSubAgentList(sb, subAgents);
        sb.append(PLAIN_ROUTE_RULE);

        return sb.toString();
    }

    // ==================== 内部：技能段 ====================

    /**
     * 拼接技能段标题与说明。
     *
     * @param sb 目标字符串构建器
     */
    private static void appendSkillsHeader(StringBuilder sb) {
        sb.append(SKILLS_HEADER).append(PARAGRAPH_BREAK);
        sb.append(SKILLS_DESC).append(PARAGRAPH_BREAK);
    }

    /**
     * 拼接技能段的所有技能条目（表格 + 调用示例）。
     *
     * @param sb     目标字符串构建器
     * @param skills 技能定义映射
     */
    private static void appendSkillsTable(StringBuilder sb, Map<String, SkillDefinition> skills) {
        for (SkillDefinition skill : skills.values()) {
            appendSkillEntry(sb, skill);
        }
    }

    /**
     * 拼接单个技能条目：子标题、描述、参数表格、调用示例。
     *
     * @param sb    目标字符串构建器
     * @param skill 技能定义
     */
    private static void appendSkillEntry(StringBuilder sb, SkillDefinition skill) {
        appendSkillTitle(sb, skill);
        appendSkillDescription(sb, skill);
        appendSkillArgsTable(sb, skill.getArguments());
        appendSkillCallSample(sb, skill);
    }

    /**
     * 拼接技能子标题。
     *
     * @param sb    目标字符串构建器
     * @param skill 技能定义
     */
    private static void appendSkillTitle(StringBuilder sb, SkillDefinition skill) {
        sb.append(SUB_SECTION_PREFIX).append(skill.getName()).append(PARAGRAPH_BREAK);
    }

    /**
     * 拼接技能描述行。
     *
     * @param sb    目标字符串构建器
     * @param skill 技能定义
     */
    private static void appendSkillDescription(StringBuilder sb, SkillDefinition skill) {
        sb.append(DESC_PREFIX).append(skill.getDescription()).append(PARAGRAPH_BREAK);
    }

    /**
     * 拼接技能参数 Markdown 表格。
     *
     * @param sb  目标字符串构建器
     * @param arg 参数 schema 列表
     */
    private static void appendSkillArgsTable(StringBuilder sb, List<SkillArgumentSchema> arg) {
        if (CollectionUtils.isEmpty(arg)) {
            return;
        }

        sb.append(ARG_PREFIX);
        sb.append(ARG_TABLE_HEADER).append(NEW_LINE);
        sb.append(ARG_TABLE_SEPARATOR).append(NEW_LINE);

        for (SkillArgumentSchema schema : arg) {
            sb.append(MD_PIPE).append(SPACE).append(schema.getName()).append(CELL_SEPARATOR)
                    .append(schema.getType()).append(CELL_SEPARATOR)
                    .append(schema.isRequired() ? REQUIRED_YES : REQUIRED_NO).append(CELL_SEPARATOR)
                    .append(schema.getDescription()).append(TABLE_LINE_SUFFIX);
        }

        sb.append(NEW_LINE);
    }

    /**
     * 拼接技能调用示例代码块。
     *
     * @param sb    目标字符串构建器
     * @param skill 技能定义
     */
    private static void appendSkillCallSample(StringBuilder sb, SkillDefinition skill) {
        sb.append(CALL_SAMPLE_PREFIX);
        sb.append(SKILL_CALL_PREFIX).append(skill.getName()).append(SKILL_CALL_SUFFIX);

        List<SkillArgumentSchema> args = skill.getArguments();
        if (CollectionUtils.isNotEmpty(args)) {
            sb.append(SKILL_CALL_LEFT_PAREN);
            sb.append(joinSkillArgSamples(args));
            sb.append(SKILL_CALL_RIGHT_PAREN);
        }

        sb.append(CODE_BLOCK_END);
    }

    /**
     * 将参数列表拼接为 {@code name=值,name=值} 形式。
     *
     * @param args 参数 schema 列表
     * @return 拼接结果
     */
    private static String joinSkillArgSamples(List<SkillArgumentSchema> args) {
        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                inner.append(SKILL_ARG_SEPARATOR);
            }
            inner.append(args.get(i).getName())
                    .append(SKILL_ARG_ASSIGN)
                    .append(ARG_PLACEHOLDER_VALUE);
        }
        return inner.toString();
    }

    // ==================== 内部：MCP 段 ====================

    /**
     * 拼接 MCP 工具条目列表。
     *
     * @param sb    目标字符串构建器
     * @param tools MCP 工具描述符列表
     */
    private static void appendMcpToolEntries(StringBuilder sb, List<McpToolDescriptor> tools) {
        for (McpToolDescriptor tool : tools) {
            appendMcpToolEntry(sb, tool);
        }
    }

    /**
     * 拼接单个 MCP 工具条目：子标题、描述、参数 JSON Schema、调用示例。
     *
     * @param sb   目标字符串构建器
     * @param tool MCP 工具描述符
     */
    private static void appendMcpToolEntry(StringBuilder sb, McpToolDescriptor tool) {
        sb.append(SUB_SECTION_PREFIX).append(escape(tool.getName())).append(PARAGRAPH_BREAK);
        sb.append(DESC_PREFIX).append(escape(tool.getDescription())).append(PARAGRAPH_BREAK);

        Map<String, Object> schema = tool.getInputSchema();
        if (!MapUtils.isEmpty(schema)) {
            sb.append(JSON_SCHEMA_PREFIX);
            sb.append(formatJsonSchema(schema));
            sb.append(CODE_BLOCK_END);
        }

        sb.append(CALL_SAMPLE_PREFIX);
        sb.append(String.format(MCP_CALL_SAMPLE_TEMPLATE, escape(tool.getName())));
        sb.append(CODE_BLOCK_END);
    }

    /**
     * 拼接 MCP 调用规则段落。
     *
     * @param sb 目标字符串构建器
     */
    private static void appendMcpCallRules(StringBuilder sb) {
        sb.append(SUB_SECTION_PREFIX).append(MCP_RULE_HEADER).append(PARAGRAPH_BREAK);
        sb.append(MCP_RULE_1).append(NEW_LINE);
        sb.append(MCP_RULE_2).append(NEW_LINE);
        sb.append(MCP_RULE_3).append(NEW_LINE);
        sb.append(MCP_RULE_4);
    }

    /**
     * 将 JSON Schema Map 格式化为缩进 JSON 字符串。
     *
     * @param schema JSON Schema Map
     * @return 格式化后的 JSON 字符串
     */
    private static String formatJsonSchema(Map<String, Object> schema) {
        StringBuilder sb = new StringBuilder();
        sb.append(JSON_OBJECT_START);

        boolean first = true;
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!first) {
                sb.append(JSON_FIELD_SEPARATOR);
            }
            first = false;

            sb.append(JSON_LINE_PREFIX_2)
                    .append(JSON_QUOTE)
                    .append(escapeJson(entry.getKey()))
                    .append(JSON_QUOTE)
                    .append(JSON_KEY_VALUE_SEPARATOR)
                    .append(formatJsonValue(entry.getValue(), JSON_INDENT_2));
        }

        sb.append(JSON_TOP_END);
        return sb.toString();
    }

    /**
     * 递归格式化 JSON 值。
     *
     * @param value   当前值
     * @param indent  当前缩进字符串
     * @return JSON 字符串
     */
    private static String formatJsonValue(Object value, String indent) {
        if (ObjectUtils.isNull(value)) {
            return JSON_NULL;
        }

        if (value instanceof String) {
            return JSON_QUOTE + escapeJson((String) value) + JSON_QUOTE;
        }

        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        if (value instanceof Map) {
            return formatJsonObject((Map<String, Object>) value, indent);
        }

        if (value instanceof List) {
            return formatJsonArray((List<Object>) value, indent);
        }

        return String.valueOf(value);
    }

    /**
     * 格式化 JSON 对象。
     *
     * @param map    对象 Map
     * @param indent 当前缩进字符串
     * @return JSON 字符串
     */
    private static String formatJsonObject(Map<String, Object> map, String indent) {
        StringBuilder sb = new StringBuilder(JSON_OBJECT_START);
        for (Map.Entry<String, Object> e : map.entrySet()) {
            sb.append(JSON_LINE_PREFIX_2).append(indent)
                    .append(JSON_QUOTE).append(escapeJson(e.getKey())).append(JSON_QUOTE)
                    .append(JSON_KEY_VALUE_SEPARATOR)
                    .append(formatJsonValue(e.getValue(), indent + JSON_INDENT_2))
                    .append(JSON_FIELD_SEPARATOR);
        }

        if (!map.isEmpty()) {
            sb.setLength(sb.length() - JSON_FIELD_SEPARATOR.length());
        }

        sb.append(NEW_LINE).append(indent).append(JSON_OBJECT_END);
        return sb.toString();
    }

    /**
     * 格式化 JSON 数组。
     *
     * @param list   数组 List
     * @param indent 当前缩进字符串
     * @return JSON 字符串
     */
    private static String formatJsonArray(List<Object> list, String indent) {
        StringBuilder sb = new StringBuilder(JSON_ARRAY_START);
        for (Object item : list) {
            sb.append(JSON_LINE_PREFIX_2).append(indent)
                    .append(formatJsonValue(item, indent + JSON_INDENT_2))
                    .append(JSON_FIELD_SEPARATOR);
        }

        if (!list.isEmpty()) {
            sb.setLength(sb.length() - JSON_FIELD_SEPARATOR.length());
        }

        sb.append(NEW_LINE).append(indent).append(JSON_ARRAY_END);
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeJson(String s) {
        if (s == null) {
            return EMPTY_STRING;
        }
        return s.replace("\\", JSON_ESCAPE_BACKSLASH)
                .replace("\"", JSON_ESCAPE_QUOTE)
                .replace("\n", JSON_ESCAPE_NEW_LINE)
                .replace("\r", JSON_ESCAPE_CARRIAGE)
                .replace("\t", JSON_ESCAPE_TAB);
    }

    // ==================== 内部：子 Agent 段 ====================

    /**
     * 拼接子 Agent Markdown 表格。
     *
     * @param sb        目标字符串构建器
     * @param subAgents 子 Agent 定义列表
     */
    private static void appendSubAgentTable(StringBuilder sb, List<AgentDefinition> subAgents) {
        sb.append(SUB_AGENT_TABLE_HEADER).append(NEW_LINE);
        sb.append(SUB_AGENT_TABLE_SEPARATOR).append(NEW_LINE);

        for (AgentDefinition agent : subAgents) {
            sb.append(MD_PIPE).append(SPACE).append(escape(agent.getId())).append(CELL_SEPARATOR)
                    .append(escape(agent.getName())).append(CELL_SEPARATOR)
                    .append(escape(agent.getDescription())).append(ROW_NEW_LINE);
        }
    }

    /**
     * 拼接路由规则段落。
     *
     * @param sb 目标字符串构建器
     */
    private static void appendRouteRules(StringBuilder sb) {
        sb.append(PARAGRAPH_BREAK).append(ROUTE_RULE_HEADER).append(PARAGRAPH_BREAK);
        sb.append(ROUTE_RULE_1).append(NEW_LINE);
        sb.append(ROUTE_RULE_2).append(NEW_LINE);
        sb.append(ROUTE_RULE_3).append(NEW_LINE);
        sb.append(ROUTE_RULE_4);
    }

    /**
     * 拼接纯文本模式子 Agent 编号列表。
     *
     * @param sb        目标字符串构建器
     * @param subAgents 子 Agent 定义列表
     */
    private static void appendSubAgentList(StringBuilder sb, List<AgentDefinition> subAgents) {
        for (int i = 0; i < subAgents.size(); i++) {
            AgentDefinition agent = subAgents.get(i);
            sb.append(i + INDEX_OFFSET).append(INDEX_DOT)
                    .append(BRACKET_LEFT).append(agent.getId()).append(BRACKET_RIGHT).append(SPACE)
                    .append(agent.getName()).append(DASH).append(agent.getDescription())
                    .append(NEW_LINE);
        }
    }

    /**
     * 转义 Markdown 特殊字符（管道符与换行）。
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    private static String escape(String value) {
        if (value == null) {
            return EMPTY_STRING;
        }
        return value.replace(MD_PIPE, MD_PIPE_ESCAPED).replace(NEW_LINE, SPACE);
    }
}
