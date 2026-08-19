package com.chua.common.support.datasearch.manager;

import com.chua.common.support.ai.mcp.DefaultMcpManager;
import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.skill.DefaultSkillManager;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.datasearch.skill.spi.SkillProvider;
import com.chua.common.support.spi.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Datasearch 技能与 MCP 统一管理器，使用 common-starter 的 {@link SkillManager} 和 {@link McpManager} 进行包管理。
 *
 * <p>核心职责：
 * <ul>
 *   <li>自动发现并注册所有 SPI {@link SkillProvider} 和 {@link McpProvider} 实现</li>
 *   <li>使用 common-starter 的 {@link DefaultSkillManager} 管理技能注册表</li>
 *   <li>使用 common-starter 的 {@link DefaultMcpManager} 管理 MCP 客户端注册表</li>
 *   <li>提供统一的 install/uninstall 接口</li>
 *   <li>实时输出初始化、安装、卸载日志</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * DatasearchSkillMcpManager manager = DatasearchSkillMcpManager.create();
 * manager.initAll();
 * manager.install("Cursor", null);
 * manager.uninstall("Cursor", null);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DatasearchSkillMcpManager {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(DatasearchSkillMcpManager.class);

    /** Skill管理器 */
    private final SkillManager skillManager = new DefaultSkillManager();
    /** MCP管理器 */
    private final McpManager mcpManager = new DefaultMcpManager();
    /** skillProviders */
    private final Map<String, SkillProvider> skillProviders = new LinkedHashMap<>();
    /** mcpProviders */
    private final Map<String, McpProvider> mcpProviders = new LinkedHashMap<>();

    /** Initialized */
    private boolean initialized = false;

    /**
     * 创建新的管理器实例
     *
     * @return DatasearchSkillMcpManager 实例
     */
    public static DatasearchSkillMcpManager create() {
        return new DatasearchSkillMcpManager();
    }

    /**
     * 初始化：自动发现所有 SPI 实现并注册 Skill + MCP 客户端
     */
    public DatasearchSkillMcpManager initAll() {
        if (initialized) {
            log.info("DatasearchSkillMcpManager 已初始化，跳过");
            return this;
        }

        log.info("========================================");
        log.info("  Datasearch Skill/MCP Manager 初始化开始");
        log.info("========================================");

        discoverSkillProviders();
        discoverMcpProviders();
        registerAllSkills();
        registerAllMcpClients();

        initialized = true;
        log.info("========================================");
        log.info("  初始化完成: {} 个 SkillProvider, {} 个 McpProvider, {} 个 Skills, {} 个 MCP 客户端",
                skillProviders.size(), mcpProviders.size(), skillManager.getAll().size(), mcpManager.getAll().size());
        log.info("========================================");
        return this;
    }

    /**
     * 自动发现所有 SkillProvider SPI 实现
     */
    private void discoverSkillProviders() {
        log.info("[Step 1/4] 发现 SkillProvider SPI 实现...");
        Map<String, SkillProvider> providerMap = ServiceProvider.of(SkillProvider.class).list();
        if (providerMap == null || providerMap.isEmpty()) {
            log.warn("  -> 未发现任何 SkillProvider 实现");
            return;
        }
        for (Map.Entry<String, SkillProvider> entry : providerMap.entrySet()) {
            String name = entry.getKey();
            SkillProvider provider = entry.getValue();
            skillProviders.put(name, provider);
            log.info("  -> 发现 SkillProvider: name={}, class={}", name, provider.getClass().getSimpleName());
        }
    }

    /**
     * 自动发现所有 McpProvider SPI 实现
     */
    private void discoverMcpProviders() {
        log.info("[Step 2/4] 发现 McpProvider SPI 实现...");
        Map<String, McpProvider> providerMap = ServiceProvider.of(McpProvider.class).list();
        if (providerMap == null || providerMap.isEmpty()) {
            log.warn("  -> 未发现任何 McpProvider 实现");
            return;
        }
        for (Map.Entry<String, McpProvider> entry : providerMap.entrySet()) {
            String name = entry.getKey();
            McpProvider provider = entry.getValue();
            mcpProviders.put(name, provider);
            log.info("  -> 发现 McpProvider: name={}, class={}", name, provider.getClass().getSimpleName());
        }
    }

    /**
     * 将所有 SkillProvider 的技能注册到 SkillManager
     */
    private void registerAllSkills() {
        log.info("[Step 3/4] 注册 Skills 到 SkillManager...");
        for (Map.Entry<String, SkillProvider> entry : skillProviders.entrySet()) {
            String providerName = entry.getKey();
            SkillProvider provider = entry.getValue();
            try {
                java.util.List<SkillDefinition> skills = provider.getSkills();
                if (skills == null || skills.isEmpty()) {
                    log.info("  -> {} ({}): 无技能定义", providerName, provider.getClass().getSimpleName());
                    continue;
                }
                for (SkillDefinition skill : skills) {
                    skillManager.register(skill);
                    log.info("  -> 注册 Skill: {}", skill.getName());
                }
            } catch (Exception e) {
                log.error("  -> 注册 SkillProvider [{}] 失败: {}", providerName, e.getMessage());
            }
        }
    }

    /**
     * 将所有 McpProvider 的 MCP 客户端注册到 McpManager
     */
    private void registerAllMcpClients() {
        log.info("[Step 4/4] 注册 MCP 客户端到 McpManager...");
        for (Map.Entry<String, McpProvider> entry : mcpProviders.entrySet()) {
            String providerName = entry.getKey();
            McpProvider provider = entry.getValue();
            try {
                McpClient client = provider.create();
                if (client == null) {
                    log.warn("  -> {} ({}): McpClient 创建返回 null", providerName, provider.getClass().getSimpleName());
                    continue;
                }
                mcpManager.register(providerName, client);
                java.util.List<McpToolDescriptor> tools = client.listTools();
                log.info("  -> 注册 MCP 客户端: name={}, tools={}",
                        providerName, tools != null ? tools.size() : 0);
                if (tools != null) {
                    for (McpToolDescriptor tool : tools) {
                        log.info("     - 工具: {}", tool.getName());
                    }
                }
            } catch (Exception e) {
                log.error("  -> 注册 McpProvider [{}] 失败: {}", providerName, e.getMessage());
            }
        }
        mcpManager.initAll();
        log.info("  -> McpManager.initAll() 完成, 共 {} 个客户端已初始化", mcpManager.getAll().size());
    }

    /**
     * 安装 Skill/MCP 到指定客户端
     *
     * @param clientId 客户端标识
     * @param skillId  技能 ID（null 表示安装全部）
     * @return 安装结果
     */
    public Map<String, Boolean> install(String clientId, String skillId) {
        log.info("开始安装: clientId={}, skillId={}", clientId, skillId);
        Map<String, Boolean> results = new LinkedHashMap<>();

        // 安装 SkillProvider
        for (Map.Entry<String, SkillProvider> entry : skillProviders.entrySet()) {
            String name = entry.getKey();
            SkillProvider provider = entry.getValue();
            try {
                boolean ok = provider.install(clientId, skillId);
                results.put("skill:" + name, ok);
                log.info("  SkillProvider [{}] install({}, {}) -> {}", name, clientId, skillId, ok);
            } catch (Exception e) {
                results.put("skill:" + name, false);
                log.error("  SkillProvider [{}] install 异常: {}", name, e.getMessage());
            }
        }

        // 安装 McpProvider
        for (Map.Entry<String, McpProvider> entry : mcpProviders.entrySet()) {
            String name = entry.getKey();
            McpProvider provider = entry.getValue();
            try {
                boolean ok = provider.install(clientId, skillId);
                results.put("mcp:" + name, ok);
                log.info("  McpProvider [{}] install({}, {}) -> {}", name, clientId, skillId, ok);
            } catch (Exception e) {
                results.put("mcp:" + name, false);
                log.error("  McpProvider [{}] install 异常: {}", name, e.getMessage());
            }
        }

        log.info("安装完成: clientId={}, 结果={}", clientId, results);
        return results;
    }

    /**
     * 卸载 Skill/MCP 从指定客户端
     *
     * @param clientId 客户端标识
     * @param skillId  技能 ID（null 表示卸载全部）
     * @return 卸载结果
     */
    public Map<String, Boolean> uninstall(String clientId, String skillId) {
        log.info("开始卸载: clientId={}, skillId={}", clientId, skillId);
        Map<String, Boolean> results = new LinkedHashMap<>();

        for (Map.Entry<String, SkillProvider> entry : skillProviders.entrySet()) {
            String name = entry.getKey();
            SkillProvider provider = entry.getValue();
            try {
                boolean ok = provider.uninstall(clientId, skillId);
                results.put("skill:" + name, ok);
                log.info("  SkillProvider [{}] uninstall({}, {}) -> {}", name, clientId, skillId, ok);
            } catch (Exception e) {
                results.put("skill:" + name, false);
                log.error("  SkillProvider [{}] uninstall 异常: {}", name, e.getMessage());
            }
        }

        for (Map.Entry<String, McpProvider> entry : mcpProviders.entrySet()) {
            String name = entry.getKey();
            McpProvider provider = entry.getValue();
            try {
                boolean ok = provider.uninstall(clientId, skillId);
                results.put("mcp:" + name, ok);
                log.info("  McpProvider [{}] uninstall({}, {}) -> {}", name, clientId, skillId, ok);
            } catch (Exception e) {
                results.put("mcp:" + name, false);
                log.error("  McpProvider [{}] uninstall 异常: {}", name, e.getMessage());
            }
        }

        log.info("卸载完成: clientId={}, 结果={}", clientId, results);
        return results;
    }

    // ==================== Getters ====================

    /** 获取SkillManager */
    public SkillManager getSkillManager() {
        return skillManager;
    }

    /** 获取McpManager */
    public McpManager getMcpManager() {
        return mcpManager;
    }

    /** 获取SkillProviders */
    public Map<String, SkillProvider> getSkillProviders() {
        return new LinkedHashMap<>(skillProviders);
    }

    /** 获取McpProviders */
    public Map<String, McpProvider> getMcpProviders() {
        return new LinkedHashMap<>(mcpProviders);
    }

    /** 获取McpClients */
    public Map<String, McpClient> getMcpClients() {
        return mcpManager.getAll();
    }

    /** 是否Initialized */
    public boolean isInitialized() {
        return initialized;
    }
}