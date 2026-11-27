package com.chua.deeplearning.support.agent.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.chua.common.support.utils.StringUtils;

/**
 * opencode 项目级配置生成器。
 *
 * <p>opencode 自带工具、技能、子 agent 与记忆，进程外无法通过 Java 回调注入，
 * 唯一受支持的接入方式是“在项目目录写出它自己会读的配置文件”。本类负责把
 * 上层（{@code AgentDefinition} / {@code Agent} 的链式配置）翻译成 opencode
 * 能识别的落盘配置，并保证可重复生成、不破坏用户手写内容。</p>
 *
 * <h3>产物</h3>
 * <ul>
 *   <li>{@code <workspace>/opencode.json}：agent 定义、MCP 服务、权限、默认模型、
 *       上下文压缩、技能目录</li>
 *   <li>{@code <workspace>/AGENTS.md}：主 agent 的系统提示词，以带标记的区块写入，
 *       区块外的手写内容不会被覆盖</li>
 * </ul>
 *
 * <h3>版本兼容</h3>
 * <p>opencode 1.x 使用 {@code agent[name].prompt} 与 {@code mcp[name]}，
 * 2.x 使用 {@code agents[name].system} 与 {@code mcp.servers[name]}，同时把 1.x 的
 * 写法当作 deprecated 兼容项读入并归一化。因此本类默认写 1.x 写法（两版都能读）；
 * 若磁盘上已有 2.x 写法（{@code agents} 或 {@code mcp.servers}），则改写成 2.x
 * 写法，避免同一份文件里出现两套并行定义。</p>
 *
 * <h3>失败语义</h3>
 * <p>磁盘上的 {@code opencode.json} 存在但不是合法 JSON、或不是 UTF-8 时，
 * 直接抛 {@link IllegalStateException} 并带上路径，绝不静默覆盖用户文件。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public final class OpencodeProjectConfig {

    /**
     * 配置文件名称
     */
    static final String CONFIG_FILE = "opencode.json";

    /**
     * 项目指令文件名称（opencode 自动读取）
     */
    static final String INSTRUCTIONS_FILE = "AGENTS.md";

    /**
     * 配置 schema 地址
     */
    private static final String SCHEMA_URL = "https://opencode.ai/config.json";

    /**
     * OpenAI 兼容 provider 的实现包（2.x 写法用 {@code package} 声明）
     */
    private static final String PACKAGE_OPENAI_COMPATIBLE = "@ai-sdk/openai-compatible";

    /**
     * 生成区块起始标记
     */
    private static final String MARK_BEGIN = "<!-- BEGIN chua-agent-config -->";

    /**
     * 生成区块结束标记
     */
    private static final String MARK_END = "<!-- END chua-agent-config -->";

    /**
     * 生成区块标题
     */
    private static final String BLOCK_TITLE = "## Agent 指令（chua 自动生成）";

    /**
     * 生成区块说明
     */
    private static final String BLOCK_HINT = "> 本节由 chua deeplearning agent 适配层按运行期配置生成，重新生成时整块覆盖；"
            + "手写内容请放在本节之外。";

    /**
     * agent 模式：主 agent
     */
    public static final String MODE_PRIMARY = "primary";

    /**
     * agent 模式：子 agent
     */
    public static final String MODE_SUBAGENT = "subagent";

    /**
     * 权限取值：询问
     */
    public static final String PERMISSION_ASK = "ask";

    /**
     * 权限取值：允许
     */
    public static final String PERMISSION_ALLOW = "allow";

    /**
     * 权限取值：拒绝
     */
    public static final String PERMISSION_DENY = "deny";

    /**
     * JSON 解析器（仅本类使用，线程安全）
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 同一工作目录的写盘串行化锁。
     *
     * <p>同一工作区会被多个会话线程同时写 {@code opencode.json}（实测两个
     * {@code onPool-worker} 在同一毫秒各写一次）。{@link Files#writeString} 的
     * 截断与写入不是原子操作，两个线程各持独立文件位置时会互相穿插，产出
     * 「一个文档的尾部盖在另一个文档中间」的非法 JSON；opencode 解析失败后
     * 所有 provider/model 定义一起丢失，表现就是 {@code Model unavailable}。
     * 因此读改写整个过程按工作目录互斥（跨实例共享，键为绝对路径）。</p>
     */
    private static final ConcurrentHashMap<String, Object> WRITE_LOCKS = new ConcurrentHashMap<>();

    /**
     * 工作目录
     */
    private final Path workspace;

    /**
     * 待写入的 agent 定义，键为 agent 名称
     */
    private final Map<String, AgentSpec> agents = new LinkedHashMap<>();

    /**
     * 待写入的 MCP 服务，键为服务名，值为 opencode 原生 mcp 条目
     */
    private final Map<String, Map<String, Object>> mcpServers = new LinkedHashMap<>();

    /**
     * 待写入的工具权限，键为权限动作名
     */
    private final Map<String, String> permissions = new LinkedHashMap<>();

    /**
     * 追加的技能目录
     */
    private final List<String> skillPaths = new ArrayList<>();

    /** 托管 MCP 名单；null 保持原来的追加行为。 */
    private List<String> selectedMcpServers;

    /** 托管技能 ID；null 表示不改动技能权限。 */
    private List<String> selectedSkills;

    /** 原生委派开关；null 表示不接管。 */
    private Boolean delegationEnabled;

    /**
     * 待写入的 provider 定义，键为 provider 标识
     */
    private final Map<String, ProviderSpec> providers = new LinkedHashMap<>();

    /**
     * provider 凭据，键为 provider 标识。
     *
     * <p>1.x（≤1.18）的 serve 模式不会把 auth.json 里的密钥套到自定义 provider 上
     * （1.18.31 实测：run 模式正常、serve 模式对上游 403「缺少 API Key」），因此
     * 1.x 段的 {@code options.apiKey} 必须直接内嵌密钥；2.x 段仍走 env 注入，不受
     * 影响。工作区为运行期隔离目录（非受版本控制仓库），密钥落盘范围与隔离数据
     * 目录的 {@code auth.json} 一致。</p>
     */
    private final Map<String, String> providerApiKeys = new LinkedHashMap<>();

    /**
     * 默认模型（provider/model）
     */
    private String defaultModel;

    /**
     * 默认主 agent 名称
     */
    private String defaultAgent;

    /**
     * 是否启用自动上下文压缩；null 表示不改动
     */
    private Boolean compactionAuto;

    /**
     * 是否启用 MCP
     */
    private boolean mcpEnabled = true;

    /**
     * 主 agent 系统提示词（写入 AGENTS.md）；null 表示不改动
     */
    private String instructions;

    /**
     * 构造生成器
     *
     * @param workspace 工作目录
     */
    private OpencodeProjectConfig(Path workspace) {
        this.workspace = workspace;
    }

    /**
     * 在指定工作目录上创建生成器。
     *
     * @param workspace 工作目录，不能为 null
     * @return 生成器
     */
    public static OpencodeProjectConfig in(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("工作目录不能为空");
        }
        return new OpencodeProjectConfig(workspace);
    }

    /**
     * 登记一个 agent 定义。
     *
     * @param spec agent 定义
     * @return 当前生成器
     */
    public OpencodeProjectConfig agent(AgentSpec spec) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            return this;
        }
        agents.put(spec.name(), spec);
        return this;
    }

    /**
     * 登记一个 MCP 服务（opencode 原生 mcp 条目）。
     *
     * <p>支持的键：{@code type}（{@code local}/{@code remote}）、{@code command}（
     * local 必填，字符串数组）、{@code url}（remote 必填）、{@code environment}、
     * {@code headers}、{@code cwd}、{@code timeout}。</p>
     *
     * @param name 服务名
     * @param spec opencode 原生 mcp 条目
     * @return 当前生成器
     */
    public OpencodeProjectConfig mcpServer(String name, Map<String, Object> spec) {
        if (StringUtils.isBlank(name) || spec == null || spec.isEmpty()) {
            return this;
        }
        validateMcpSpec(name, spec);
        mcpServers.put(name.trim(), spec);
        return this;
    }

    /**
     * 限定工作目录中可启用的 MCP 配置键。
     *
     * @param names 当前选择；空名单表示全部关闭，null 表示不接管
     * @return 当前生成器
     */
    public OpencodeProjectConfig mcpSelection(List<String> names) {
        selectedMcpServers = names == null ? null : List.copyOf(names);
        return this;
    }

    /**
     * 限定本次生成的 Agent 可加载的技能 ID。
     *
     * @param names 当前选择；空名单表示全部关闭，null 表示不接管
     * @return 当前生成器
     */
    public OpencodeProjectConfig skillSelection(List<String> names) {
        if (names != null && names.stream().anyMatch(name -> name == null || name.isBlank()
                || name.indexOf('*') >= 0 || name.indexOf('?') >= 0)) {
            throw new IllegalArgumentException("技能 ID 必须是非空的精确名称，不能包含通配符");
        }
        selectedSkills = names == null ? null : List.copyOf(names);
        return this;
    }

    /**
     * 设置托管 Agent 的原生委派权限。
     *
     * @param enabled true 清除关闭项，false 禁用，null 不接管
     * @return 当前生成器
     */
    public OpencodeProjectConfig delegation(Boolean enabled) {
        delegationEnabled = enabled;
        return this;
    }

    /**
     * 登记一个 provider 定义。
     *
     * <p>站点自定义供应商（如 {@code moss}）不在 opencode 内置目录里，必须显式声明
     * {@code options.baseURL} 与可用模型清单，否则 opencode 无法为
     * {@code provider/model} 构造模型客户端，最终表现为
     * {@code UnknownError: Unexpected server error}。</p>
     *
     * <p>凭据（apiKey）由 {@code OpencodeServerProcess} 合并进隔离数据目录的
     * {@code auth.json}（CLI run 模式用），并经 {@link #providerApiKey(String, String)}
     * 写入 1.x 段 {@code options.apiKey}（1.x serve 模式不消费 auth.json，见该方法）。</p>
     *
     * @param spec provider 定义
     * @return 当前生成器
     */
    public OpencodeProjectConfig provider(ProviderSpec spec) {
        if (spec == null || spec.id() == null || spec.id().isBlank()) {
            return this;
        }
        providers.put(spec.id().trim(), spec);
        return this;
    }

    /**
     * 登记 provider 的 API 密钥（写入 1.x 段 {@code options.apiKey}）。
     *
     * <p>1.x 的 serve 模式不消费 auth.json（见 {@code providerApiKeys} 字段说明），
     * 密钥必须随项目配置下发；2.x 忽略 1.x 段，仍从 {@code providers.<id>.env}
     * 声明的环境变量取密钥，因此两条轨都要保留。</p>
     *
     * @param providerId provider 标识（{@code provider/model} 中斜杠前的部分）
     * @param apiKey     API 密钥；空白忽略
     * @return 当前生成器
     */
    public OpencodeProjectConfig providerApiKey(String providerId, String apiKey) {
        if (StringUtils.isBlank(providerId) || StringUtils.isBlank(apiKey)) {
            return this;
        }
        providerApiKeys.put(providerId.trim(), apiKey.trim());
        return this;
    }

    /**
     * 设置是否启用 MCP。
     *
     * <p>关闭时，配置里所有 MCP 服务条目都会被标记 {@code enabled=false}，
     * 而不是仅仅“不新增”。</p>
     *
     * @param enabled 是否启用
     * @return 当前生成器
     */
    public OpencodeProjectConfig mcpEnabled(boolean enabled) {
        this.mcpEnabled = enabled;
        return this;
    }

    /**
     * 设置工具权限。
     *
     * @param action 动作名，如 {@code bash}、{@code edit}、{@code webfetch}
     * @param effect 取值：{@link #PERMISSION_ASK} / {@link #PERMISSION_ALLOW} / {@link #PERMISSION_DENY}
     * @return 当前生成器
     */
    public OpencodeProjectConfig permission(String action, String effect) {
        if (StringUtils.isBlank(action) || StringUtils.isBlank(effect)) {
            return this;
        }
        String value = effect.trim();
        if (!PERMISSION_ASK.equals(value) && !PERMISSION_ALLOW.equals(value)
                && !PERMISSION_DENY.equals(value)) {
            throw new IllegalArgumentException("非法权限取值: " + effect + "（仅支持 ask/allow/deny）");
        }
        permissions.put(action.trim(), value);
        return this;
    }

    /**
     * 设置默认模型。
     *
     * @param providerModel 形如 {@code provider/model}；不含斜杠时忽略
     * @return 当前生成器
     */
    public OpencodeProjectConfig model(String providerModel) {
        if (providerModel == null) {
            return this;
        }
        String value = providerModel.trim();
        if (value.indexOf('/') <= 0) {
            log.warn("[opencode-server] 默认模型 {} 缺少 provider 前缀，配置中不写入 model", providerModel);
            return this;
        }
        this.defaultModel = value;
        return this;
    }

    /**
     * 设置默认主 agent。
     *
     * @param name agent 名称
     * @return 当前生成器
     */
    public OpencodeProjectConfig defaultAgent(String name) {
        if (StringUtils.isNotBlank(name)) {
            this.defaultAgent = name.trim();
        }
        return this;
    }

    /**
     * 设置是否启用自动上下文压缩。
     *
     * @param auto 是否启用
     * @return 当前生成器
     */
    public OpencodeProjectConfig compaction(boolean auto) {
        this.compactionAuto = auto;
        return this;
    }

    /**
     * 追加一个技能目录（opencode 会从该目录发现 {@code SKILL.md}）。
     *
     * @param path 技能目录
     * @return 当前生成器
     */
    public OpencodeProjectConfig skillPath(String path) {
        if (StringUtils.isNotBlank(path) && !skillPaths.contains(path)) {
            skillPaths.add(path);
        }
        return this;
    }

    /**
     * 设置写入 AGENTS.md 的主 agent 系统提示词。
     *
     * @param markdown 提示词正文；空白表示不写
     * @return 当前生成器
     */
    public OpencodeProjectConfig instructions(String markdown) {
        if (StringUtils.isNotBlank(markdown)) {
            this.instructions = markdown;
        }
        return this;
    }

    /**
     * 落盘生成配置。
     *
     * @return 生成结果
     */
    public Result write() {
        Path absWorkspace = workspace.toAbsolutePath().normalize();
        Object lock = WRITE_LOCKS.computeIfAbsent(absWorkspace.toString(), key -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(workspace);
                Path configPath = workspace.resolve(CONFIG_FILE);
                ObjectNode root = readConfig(configPath);
                root.put("$schema", SCHEMA_URL);
                boolean v2 = isV2Shape(root);
                applyAgents(root, v2);
                applyProviders(root);
                applyMcp(root, v2);
                applyPermissions(root);
                applySkillSelection(root, v2);
                if (defaultModel != null) {
                    root.put("model", defaultModel);
                }
                if (defaultAgent != null) {
                    root.put("default_agent", defaultAgent);
                }
                if (compactionAuto != null) {
                    childObject(root, "compaction").put("auto", compactionAuto.booleanValue());
                }
                applySkills(root, v2);
                boolean configChanged = writeIfChanged(configPath, toJson(root));
                Path instructionsPath = workspace.resolve(INSTRUCTIONS_FILE);
                boolean instructionsChanged = false;
                if (instructions != null) {
                    instructionsChanged = writeIfChanged(instructionsPath, renderInstructions());
                }
                return new Result(configPath, instructions != null ? instructionsPath : null,
                        configChanged, instructionsChanged);
            } catch (IOException e) {
                throw new IllegalStateException("写入 opencode 配置失败: " + workspace, e);
            }
        }
    }

    /**
     * 读取磁盘上的配置；不存在则返回新的空对象。
     *
     * @param configPath 配置文件路径
     * @return 配置根节点
     * @throws IOException 读取失败
     */
    private ObjectNode readConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            return mapper.createObjectNode();
        }
        String text;
        try {
            text = Files.readString(configPath, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IllegalStateException("配置文件不是 UTF-8 编码: " + configPath, e);
        }
        if (text.isBlank()) {
            return mapper.createObjectNode();
        }
        JsonNode node;
        try {
            node = mapper.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("配置文件不是合法 JSON: " + configPath, e);
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("配置文件根节点必须是 JSON 对象: " + configPath);
        }
        return object;
    }

    /**
     * 判断磁盘配置是否为 opencode 2.x 写法。
     *
     * @param root 配置根节点
     * @return true 表示 2.x 写法
     */
    private static boolean isV2Shape(ObjectNode root) {
        JsonNode agentsNode = root.get("agents");
        if (agentsNode instanceof ObjectNode && !agentsNode.isEmpty()) {
            return true;
        }
        JsonNode mcpNode = root.get("mcp");
        return mcpNode instanceof ObjectNode && mcpNode.has("servers");
    }

    /**
     * 写入 agent 定义。
     *
     * @param root 配置根节点
     * @param v2   是否 2.x 写法
     */
    private void applyAgents(ObjectNode root, boolean v2) {
        if (agents.isEmpty()) {
            return;
        }
        ObjectNode container = childObject(root, v2 ? "agents" : "agent");
        for (AgentSpec spec : agents.values()) {
            JsonNode existing = container.get(spec.name());
            ObjectNode node = existing instanceof ObjectNode object ? object : container.putObject(spec.name());
            String description = spec.description();
            if (StringUtils.isNotBlank(description)) {
                node.put("description", description);
            }
            if (spec.mode() != null && !spec.mode().isBlank()) {
                node.put("mode", spec.mode());
            }
            if (spec.permissions() != null && !spec.permissions().isEmpty()) {
                ObjectNode permissionNode = childObject(node, "permission");
                spec.permissions().forEach(permissionNode::put);
            }
            String prompt = spec.prompt();
            if (StringUtils.isNotBlank(prompt)) {
                node.put(v2 ? "system" : "prompt", prompt);
            }
            if (spec.steps() != null && spec.steps().intValue() > 0) {
                node.put("steps", spec.steps().intValue());
            }
            if (spec.model() != null && !spec.model().isBlank()) {
                node.put("model", spec.model());
            }
            if (spec.tools() != null && !spec.tools().isEmpty()) {
                ObjectNode toolsNode = childObject(node, "tools");
                spec.tools().forEach(toolsNode::put);
            }
            if (delegationEnabled != null) {
                if (v2) {
                    ArrayNode rules = mapper.createArrayNode();
                    if (node.get("permissions") instanceof ArrayNode existingRules) {
                        for (JsonNode rule : existingRules) {
                            if (!("subagent".equals(rule.path("action").asText())
                                    && "*".equals(rule.path("resource").asText())
                                    && "deny".equals(rule.path("effect").asText()))) {
                                rules.add(rule);
                            }
                        }
                    }
                    if (!delegationEnabled) {
                        rules.addObject().put("action", "subagent").put("resource", "*").put("effect", "deny");
                    }
                    node.set("permissions", rules);
                }
                ObjectNode toolsNode = childObject(node, "tools");
                if (delegationEnabled || v2) {
                    toolsNode.remove("task");
                } else {
                    toolsNode.put("task", false);
                }
            }
        }
    }

    /**
     * 写入 provider 定义。
     *
     * <p>opencode 的内置目录只收录官方厂商的模型清单；站点自建的 OpenAI 兼容端点
     * （如 {@code moss}）必须显式声明端点与模型清单，否则 {@code provider/model}
     * 解析不到，表现为 {@code UnknownError: Unexpected server error}。</p>
     *
     * <p>这里同时写两套写法：{@code provider} 是 1.x 写法（老版本 CLI 读），
     * {@code providers} 是 2.x 写法（1.19 起的 server 只认它）。实测两份并存不会
     * 互相覆盖：2.x server 解析 provider 时用的就是 {@code providers} 段。</p>
     *
     * <p>密钥下发分两轨：2.x 在 provider 上声明 {@code env} 环境变量名，由
     * {@link OpencodeServerProcess#providerCredential} 在启动子进程时注入；1.x 的
     * serve 模式不消费 auth.json（1.18.31 实测：run 模式正常、serve 模式对上游
     * 403「缺少 API Key」），因此 1.x 段的 {@code options.apiKey} 直接内嵌密钥。
     * 工作区为运行期隔离目录（非受版本控制仓库），密钥落盘范围与隔离数据目录的
     * {@code auth.json} 一致。</p>
     *
     * <p>1.x 段必须写 {@code npm}：1.x 用它选择 AI SDK 实现包，缺省时 provider 的
     * API 会被解析成 {@code native}，会话起跑即抛
     * {@code SessionRunnerModel.UnsupportedApiError: Unsupported API for
     * <provider>/<model>: native}（实测 1.19.5）。2.x 段刻意不写 {@code npm}：
     * {@code package} 才是 2.x 的实现包字段，写错会覆盖内置 provider 的原生协议实现。</p>
     *
     * @param root 配置根节点
     */
    private void applyProviders(ObjectNode root) {
        if (providers.isEmpty()) {
            return;
        }
        ObjectNode legacy = childObject(root, "provider");
        ObjectNode container = childObject(root, "providers");
        for (ProviderSpec spec : providers.values()) {
            String name = spec.name() == null || spec.name().isBlank() ? spec.id() : spec.name();
            JsonNode existingLegacy = legacy.get(spec.id());
            ObjectNode node = existingLegacy instanceof ObjectNode object ? object : legacy.putObject(spec.id());
            node.put("name", name);
            node.put("npm", PACKAGE_OPENAI_COMPATIBLE);
            if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
                childObject(node, "options").put("baseURL", spec.baseUrl());
            }
            String apiKey = providerApiKeys.get(spec.id());
            if (StringUtils.isNotBlank(apiKey)) {
                childObject(node, "options").put("apiKey", apiKey);
            }
            JsonNode existing = container.get(spec.id());
            ObjectNode v2Node = existing instanceof ObjectNode object ? object : container.putObject(spec.id());
            v2Node.put("name", name);
            v2Node.put("package", PACKAGE_OPENAI_COMPATIBLE);
            v2Node.putArray("env").add(providerEnvName(spec.id()));
            if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
                childObject(v2Node, "settings").put("baseURL", spec.baseUrl());
            }
            if (spec.models() == null || spec.models().isEmpty()) {
                continue;
            }
            ObjectNode legacyModels = childObject(node, "models");
            ObjectNode models = childObject(v2Node, "models");
            for (String model : spec.models()) {
                if (StringUtils.isBlank(model)) {
                    continue;
                }
                legacyModels.putObject(model.trim()).put("name", model.trim());
                models.putObject(model.trim()).put("name", model.trim());
            }
        }
    }

    /**
     * provider 对应的环境变量名。
     *
     * <p>2.x 通过 provider 的 {@code env} 声明从哪个环境变量读密钥。规则：标识转大写，
     * 非字母数字替换为下划线，追加 {@code _API_KEY}（{@code agnes} →
     * {@code AGNES_API_KEY}）。{@link OpencodeServerProcess} 注入子进程环境变量时必须
     * 使用同一个名字，否则 provider 会在解析阶段报 {@code Model unavailable}。</p>
     *
     * @param providerId provider 标识（{@code provider/model} 中斜杠前的部分）
     * @return 环境变量名
     */
    public static String providerEnvName(String providerId) {
        StringBuilder builder = new StringBuilder();
        for (char ch : providerId.toCharArray()) {
            builder.append(Character.isLetterOrDigit(ch) ? Character.toUpperCase(ch) : '_');
        }
        return builder.append("_API_KEY").toString();
    }

    /**
     * 写入 MCP 服务。
     *
     * @param root 配置根节点
     * @param v2   是否 2.x 写法
     */
    private void applyMcp(ObjectNode root, boolean v2) {
        if (mcpServers.isEmpty() && mcpEnabled && selectedMcpServers == null) {
            return;
        }
        ObjectNode container;
        if (v2) {
            container = childObject(childObject(root, "mcp"), "servers");
        } else {
            container = childObject(root, "mcp");
        }
        for (Map.Entry<String, Map<String, Object>> entry : mcpServers.entrySet()) {
            JsonNode existing = container.get(entry.getKey());
            ObjectNode node = existing instanceof ObjectNode object ? object : container.putObject(entry.getKey());
            node.setAll(asObjectNode(mapper.valueToTree(entry.getValue())));
            node.remove(v2 ? "enabled" : "disabled");
            node.put(v2 ? "disabled" : "enabled", v2 ? !mcpEnabled : mcpEnabled);
        }
        if (!mcpEnabled || selectedMcpServers != null) {
            container.properties().forEach(item -> {
                if (item.getValue() instanceof ObjectNode node) {
                    boolean enabled = mcpEnabled && selectedMcpServers != null
                            && selectedMcpServers.contains(item.getKey());
                    node.remove(v2 ? "enabled" : "disabled");
                    node.put(v2 ? "disabled" : "enabled", v2 ? !enabled : enabled);
                }
            });
        }
    }

    /**
     * 写入工具权限。
     *
     * @param root 配置根节点
     */
    private void applyPermissions(ObjectNode root) {
        if (permissions.isEmpty()) {
            return;
        }
        ObjectNode node = childObject(root, "permission");
        permissions.forEach(node::put);
    }

    /**
     * 追加技能目录。
     *
     * @param root 配置根节点
     * @param v2 是否使用 2.x 配置形状
     */
    private void applySkills(ObjectNode root, boolean v2) {
        if (skillPaths.isEmpty()) {
            return;
        }
        JsonNode existing = root.get("skills");
        ArrayNode array;
        if (existing instanceof ArrayNode items) {
            array = items;
        } else if (existing instanceof ObjectNode object && object.get("paths") instanceof ArrayNode paths) {
            array = paths;
        } else if (v2) {
            array = root.putArray("skills");
        } else {
            array = childObject(root, "skills").putArray("paths");
        }
        for (String path : skillPaths) {
            boolean present = false;
            for (JsonNode item : array) {
                if (path.equals(item.asText())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                array.add(path);
            }
        }
    }

    /**
     * 在本次托管的 Agent 上替换技能权限；其它动作与手写 Agent 保持原状。
     *
     * @param root 配置根节点
     * @param v2   是否使用 2.x 配置形状
     */
    private void applySkillSelection(ObjectNode root, boolean v2) {
        if (selectedSkills == null || agents.isEmpty()) {
            return;
        }
        ObjectNode container = childObject(root, v2 ? "agents" : "agent");
        for (String name : agents.keySet()) {
            ObjectNode node = childObject(container, name);
            if (node.get("tools") instanceof ObjectNode tools) {
                // 旧工具布尔值可覆盖 permission，必须让当前技能名单成为唯一来源。
                tools.remove("skill");
            }
            if (v2) {
                ArrayNode rules = mapper.createArrayNode();
                if (node.get("permissions") instanceof ArrayNode existing) {
                    for (JsonNode rule : existing) {
                        if (!"skill".equals(rule.path("action").asText())) {
                            rules.add(rule);
                        }
                    }
                }
                rules.addObject().put("action", "skill").put("resource", "*").put("effect", "deny");
                for (String skill : selectedSkills) {
                    rules.addObject().put("action", "skill").put("resource", skill).put("effect", "allow");
                }
                node.set("permissions", rules);
            } else {
                ObjectNode skills = childObject(node, "permission").putObject("skill");
                skills.put("*", "deny");
                selectedSkills.forEach(skill -> skills.put(skill, "allow"));
            }
        }
    }

    /**
     * 渲染 AGENTS.md 内容；已有区块原位替换，无区块则追加，保留手写内容。
     *
     * @return 文件内容
     */
    private String renderInstructions() {
        String block = MARK_BEGIN + "\n" + BLOCK_TITLE + "\n\n" + BLOCK_HINT + "\n\n"
                + instructions.trim() + "\n" + MARK_END;
        Path path = workspace.resolve(INSTRUCTIONS_FILE);
        String existing = null;
        if (Files.exists(path)) {
            try {
                existing = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取 " + path + " 失败", e);
            }
        }
        if (StringUtils.isBlank(existing)) {
            return block + "\n";
        }
        int begin = existing.indexOf(MARK_BEGIN);
        int end = existing.indexOf(MARK_END);
        if (begin >= 0 && end > begin) {
            return existing.substring(0, begin) + block + existing.substring(end + MARK_END.length());
        }
        String separator = existing.endsWith("\n") ? "\n" : "\n\n";
        return existing + separator + block + "\n";
    }

    /**
     * 序列化配置。
     *
     * @param root 配置根节点
     * @return JSON 文本
     * @throws IOException 序列化失败
     */
    private String toJson(ObjectNode root) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
    }

    /**
     * 内容变化时才写盘。
     *
     * @param path    目标文件
     * @param content 目标内容
     * @return 是否真的写入
     * @throws IOException 写入失败
     */
    private static boolean writeIfChanged(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            String existing = Files.readString(path, StandardCharsets.UTF_8);
            if (existing.equals(content)) {
                return false;
            }
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 先写临时文件再原子替换：进程被中断时不会留下半截 JSON，
        // opencode 每次启动都会读取该文件，半截内容等于整套 provider 定义失效。
        Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return true;
    }

    /**
     * 取（或创建）子对象节点。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 子对象节点
     */
    private static ObjectNode childObject(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing instanceof ObjectNode object) {
            return object;
        }
        return parent.putObject(field);
    }

    /**
     * 校验 opencode 原生 MCP 条目，避免写出服务端无法加载的配置。
     *
     * @param name 服务名
     * @param spec 条目
     */
    private static void validateMcpSpec(String name, Map<String, Object> spec) {
        Object type = spec.get("type");
        String value = type == null ? "" : String.valueOf(type).trim();
        if ("local".equals(value)) {
            Object command = spec.get("command");
            if (!(command instanceof List<?> items) || items.isEmpty()
                    || items.stream().anyMatch(item -> item == null || String.valueOf(item).isBlank())) {
                throw new IllegalArgumentException("本地 MCP 服务 " + name + " 需要非空 command 数组");
            }
            return;
        }
        if ("remote".equals(value)) {
            Object url = spec.get("url");
            if (url == null || String.valueOf(url).isBlank()) {
                throw new IllegalArgumentException("远程 MCP 服务 " + name + " 需要 url");
            }
            return;
        }
        throw new IllegalArgumentException("MCP 服务 " + name + " 的 type 必须是 local 或 remote，实际: " + type);
    }

    /**
     * 断言为对象节点。
     *
     * @param node 节点
     * @return 对象节点
     */
    private static ObjectNode asObjectNode(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("MCP 配置必须是 JSON 对象");
        }
        return object;
    }

    /**
     * 单个 provider 定义。
     *
     * @param id      provider 标识（{@code provider/model} 中斜杠前的部分）
     * @param name    展示名；null 时用 {@code id}
     * @param baseUrl OpenAI 兼容端点根地址（如 {@code https://api.mosi.cn/v1}）；null 表示不写
     * @param models  该 provider 下可用的模型标识；null 或空表示不写
     */
    public record ProviderSpec(String id, String name, String baseUrl, List<String> models) {

        /**
         * 站点自建 OpenAI 兼容端点的构造入口。
         *
         * @param id      provider 标识
         * @param baseUrl 端点根地址
         * @param models  可用模型标识
         * @return 定义
         */
        public static ProviderSpec openAiCompatible(String id, String baseUrl, List<String> models) {
            return new ProviderSpec(id, id, baseUrl, models);
        }
    }

    /**
     * 单个 agent 定义。
     *
     * @param name        agent 名称（opencode 侧标识）
     * @param mode        {@link #MODE_PRIMARY} 或 {@link #MODE_SUBAGENT}；null 表示沿用既有值
     * @param description 供模型判断何时使用的描述
     * @param prompt      系统提示词
     * @param steps       最大 agent 轮数；null 或非正数表示不写
     * @param model       该 agent 使用的模型（provider/model）；null 表示继承默认
     * @param permissions 该 agent 的权限覆盖；null 表示不写
     * @param tools       该 agent 的工具开关覆盖（如 {@code bash -> false}）；null 表示不写
     */
    public record AgentSpec(String name, String mode, String description, String prompt,
                            Integer steps, String model, Map<String, String> permissions,
                            Map<String, Boolean> tools) {

        /**
         * 只覆盖模型的构造入口（用于 {@code chatClient(agentId, ..)}）。
         *
         * @param name  agent 名称
         * @param model 模型标识
         * @return 定义
         */
        public static AgentSpec modelOnly(String name, String model) {
            return new AgentSpec(name, null, null, null, null, model, null, null);
        }

        /**
         * 只覆盖模型与工具开关的构造入口。
         *
         * @param name  agent 名称
         * @param model 模型标识
         * @param tools 工具开关覆盖
         * @return 定义
         */
        public static AgentSpec modelOnly(String name, String model, Map<String, Boolean> tools) {
            return new AgentSpec(name, null, null, null, null, model, null, tools);
        }
    }

    /**
     * 生成结果。
     *
     * @param configFile          配置文件路径
     * @param instructionsFile    项目指令文件路径；未生成指令时为 null
     * @param configChanged       配置是否发生变化
     * @param instructionsChanged 项目指令是否发生变化
     */
    public record Result(Path configFile, Path instructionsFile,
                         boolean configChanged, boolean instructionsChanged) {

        /**
         * 是否产生了任何变化。
         *
         * @return true 表示有文件被写入
         */
        public boolean changed() {
            return configChanged || instructionsChanged;
        }
    }
}
