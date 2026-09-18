package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP 服务安装提供者抽象基类。
 *
 * <p>封装将外部 MCP server（如 {@code npx <npm包>}）安装到 AI 编辑器 mcp.json 配置的通用能力：
 * <ul>
 *   <li>{@link #mcpServerKey()} — 安装进 mcpServers 的服务名键</li>
 *   <li>{@link #mcpCommand()} / {@link #mcpArgs()} — 服务命令（默认 {@code npx -y <pkg>}）</li>
 *   <li>{@link #browserToolDescriptors()} — 该外部服务提供的工具描述符</li>
 * </ul>
 * install/uninstall 复用 {@link AgentEditorProvider#readMcpConfig}/{@code supportedEditors}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public abstract class AbstractExternalMcpInstallerProvider extends AgentEditorProvider implements McpProvider {

    /** 日志 */
    protected static final Logger log = LoggerFactory.getLogger(AbstractExternalMcpInstallerProvider.class);

    /**
    * npm 包名（如 agent-browser /
    * 
    * @playwright/mcp /
    * 
    * @puppeteer/mcp）。
    * @return 结果字符串
    */
    protected abstract String npmPackage();

    /**
     * MCP server 安装键名（默认 npm 包末段）。
     * @return 结果字符串
     */
    protected String mcpServerKey() {
        String pkg = npmPackage();
        int slash = pkg.lastIndexOf('/');
        return slash >= 0 ? pkg.substring(slash + 1) : pkg;
    }

    /**
     * 启动命令（默认 npx）。
     * @return 结果字符串
     */
    protected String mcpCommand() {
        return "npx";
    }

    /**
     * 启动参数（默认 {@code -y <npmPackage>}）。
     * @return 结果列表，无数据时为空列表
     */
    protected List<String> mcpArgs() {
        return List.of("-y", npmPackage());
    }

    /**
     * 该外部服务提供的工具描述符。
     * @return 结果列表，无数据时为空列表
     */
    protected abstract List<McpToolDescriptor> browserToolDescriptors();

    @Override
    public McpClient create() {
        return new ExternalBrowserMcpClient();
    }

    @Override
    public boolean install(String clientId, String toolId) {
        AgentEditor editor = findEditor(clientId);
        if (editor == null) {
            log.warn("[{}] 未找到客户端: {}", mcpServerKey(), clientId);
            return false;
        }
        if (editor == AgentEditor.TRAE_CN) {
            log.info("[{}] TRAE-CN 不支持 MCP 安装，跳过", mcpServerKey());
            return true;
        }
        return writeServerConfig(editor, true);
    }

    @Override
    public boolean uninstall(String clientId, String toolId) {
        AgentEditor editor = findEditor(clientId);
        if (editor == null) {
            log.warn("[{}] 未找到客户端: {}", mcpServerKey(), clientId);
            return false;
        }
        if (editor == AgentEditor.TRAE_CN) {
            log.info("[{}] TRAE-CN 不支持 MCP 卸载，跳过", mcpServerKey());
            return true;
        }
        return writeServerConfig(editor, false);
    }

    @Override
    public List<String> listAvailable() {
        return listAvailableEditors();
    }

    /**
     * 写入服务端配置。
     *
     * @param editor 方法入参 editor
     * @param install install（布尔开关）
     * @return 是否成功（true 表示成功）
     */
    @SuppressWarnings("unchecked")
    private boolean writeServerConfig(AgentEditor editor, boolean install) {
        try {
            Path configFile;
            String envValue = editor.getEnvVar() != null ? System.getenv(editor.getEnvVar()) : null;
            if (envValue != null && !envValue.isBlank()) {
                configFile = Path.of(envValue).resolve(editor.getMcpConfigFile());
            } else if (editor.isWorkspaceBased()) {
                List<Path> workspaces = discoverCodebuddyWorkspaces(null);
                if (workspaces.isEmpty()) {
                    log.warn("[{}] 未发现工作区，无法安装: {}", mcpServerKey(), editor.getName());
                    return false;
                }
                configFile = workspaces.getFirst().resolve(editor.getMcpConfigPath());
            } else {
                configFile = editor.getMcpConfigFilePath();
            }
            Map<String, Object> existing = new LinkedHashMap<>(readMcpConfig(configFile));
            Map<String, Object> servers = new LinkedHashMap<>();
            Object existingServers = existing.get("mcpServers");
            if (existingServers instanceof Map) {
                servers.putAll((Map<String, Object>) existingServers);
            }
            String key = mcpServerKey();
            if (install) {
                Map<String, Object> serverConfig = new LinkedHashMap<>();
                serverConfig.put("command", mcpCommand());
                serverConfig.put("args", mcpArgs());
                servers.put(key, serverConfig);
                log.info("[{}] 已安装到 {} : {}", key, editor.getName(), configFile);
            } else {
                servers.remove(key);
                log.info("[{}] 已从 {} 卸载", key, editor.getName());
            }
            existing.put("mcpServers", servers);
            Files.writeString(configFile, mapToJson(existing, 0), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            log.error("[{}] 写入 {} 配置失败: {}", mcpServerKey(), editor.getName(), e.getMessage());
            return false;
        }
    }

    /**
    * 外部浏览器 MCP 客户端：在 JVM 内提供工具列表与调用占位。
    *
    * @author CH
    * @since 4.0.0.45
    */
    protected class ExternalBrowserMcpClient implements McpClient {
        /** initialized */
        private volatile boolean initialized = false;

        @Override
        public void init() {
            initialized = true;
            log.info("[{}] MCP 客户端已初始化", mcpServerKey());
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            return browserToolDescriptors();
        }

        @Override
        public McpToolResult callTool(McpToolCall toolCall) {
            try {
                return handleExternalTool(toolCall);
            } catch (Exception e) {
                log.error("[{}] 工具调用异常", mcpServerKey(), e);
                return McpToolResult.error("调用异常: " + e.getMessage());
            }
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }
    }

    /**
        * 处理外部工具调用；子类可覆写为真实浏览器调用，默认返回未实现提示。
        *
        * @param toolCall 工具调用
        * @return 工具调用结果
        */
    protected McpToolResult handleExternalTool(McpToolCall toolCall) {
        return McpToolResult.error("工具[" + toolCall.getToolName() + "] 需通过 MCP 外部服务执行");
    }

    @Override
    public String toString() {
        return mcpServerKey();
    }
}
