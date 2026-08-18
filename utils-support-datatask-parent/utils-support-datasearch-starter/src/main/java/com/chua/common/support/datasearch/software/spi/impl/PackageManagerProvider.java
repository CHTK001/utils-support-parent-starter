package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.ai.skill.SkillArgumentSchema;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillResult;
import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.lang.cmd.PackageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 common-starter {@link PackageManager} 的软件包管理器基础提供器，
 * 封装 MCP、Skill 和 Software 的公共能力。
 *
 * <p>支持检测系统包管理器（winget/choco/brew/apt/yum/dnf/apk）
 * 并搜索软件包，安装/卸载软件，实时输出日志。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PackageManagerProvider {

    /** 日志 */
    protected static final Logger log = LoggerFactory.getLogger(PackageManagerProvider.class);

    /** 名称 */
    protected static final String NAME = "package-manager";
    /** Prefix */
    protected static final String PREFIX = "";

    /**
     * 获取提供者名称
     */
    public String name() {
        return NAME;
    }

    /**
     * 安装到客户端（MCP/Skill 通用），包管理器始终可用，无需本地安装
     */
    public boolean install(String clientId, String skillId) {
        log.info("PackageManager 安装: clientId={}, skillId={}", clientId, skillId);
        return true;
    }

    /**
     * 从客户端卸载（MCP/Skill 通用）
     */
    public boolean uninstall(String clientId, String skillId) {
        log.info("PackageManager 卸载: clientId={}, skillId={}", clientId, skillId);
        return true;
    }

    /**
     * 获取所有已安装（始终全部可用）
     */
    public Map<String, Boolean> listInstalled() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (McpToolDescriptor td : toolDescriptors()) {
            result.put(td.getName(), detectAvailable() != null && !detectAvailable().isEmpty());
        }
        return result;
    }

    /**
     * 列出当前可用的客户端
     */
    public List<String> listAvailable() {
        return List.of(NAME);
    }

    // ==================== MCP 工具描述 ====================

    public static List<McpToolDescriptor> toolDescriptors() {
        return List.of(
                new McpToolDescriptor(PREFIX + "search",
                        "搜索系统软件包（通过 winget/brew/apt/choco/yum/dnf/apk）",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "keyword", Map.of("type", "string", "description", "搜索关键词")),
                                "required", List.of("keyword"))),
                new McpToolDescriptor(PREFIX + "install",
                        "安装系统软件包",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "packageId", Map.of("type", "string", "description", "包 ID")),
                                "required", List.of("packageId"))),
                new McpToolDescriptor(PREFIX + "uninstall",
                        "卸载系统软件包",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "packageId", Map.of("type", "string", "description", "包 ID")),
                                "required", List.of("packageId"))),
                new McpToolDescriptor(PREFIX + "list_managers",
                        "列出当前系统可用的包管理器",
                        Map.of("type", "object",
                                "properties", Map.of(),
                                "required", List.of()))
        );
    }

    // ==================== MCP 客户端 ====================

    protected class PackageManagerMcpClient implements McpClient {
        private volatile boolean initialized = false;

        @Override
        public void init() {
            initialized = true;
            List<PackageManager.Type> pms = detectAvailable();
            log.info("PackageManager MCP 客户端已初始化, 可用包管理器: {}", pms);
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            return toolDescriptors();
        }

        @Override
        public McpToolResult callTool(McpToolCall toolCall) {
            try {
                return switch (toolCall.getToolName()) {
                    case PREFIX + "search" -> handleSearch(toolCall.getArguments());
                    case PREFIX + "install" -> handleInstall(toolCall.getArguments());
                    case PREFIX + "uninstall" -> handleUninstall(toolCall.getArguments());
                    case PREFIX + "list_managers" -> handleListManagers();
                    default -> McpToolResult.error("未知工具: " + toolCall.getToolName());
                };
            } catch (Exception e) {
                log.error("PackageManager 工具调用异常", e);
                return McpToolResult.error("调用异常: " + e.getMessage());
            }
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }
    }

    // ==================== 工具处理 ====================

    protected McpToolResult handleSearch(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        List<SoftwareInfo> results = searchSoftware(keyword);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SoftwareInfo info : results) {
            items.add(info.toMap());
        }
        return McpToolResult.success(Map.of("keyword", keyword, "total", results.size(), "items", items));
    }

    protected McpToolResult handleInstall(Map<String, Object> args) {
        String packageId = (String) args.get("packageId");
        boolean ok = installSoftware(packageId);
        return ok ? McpToolResult.success(Map.of("packageId", packageId, "installed", true))
                : McpToolResult.error("安装失败: " + packageId);
    }

    protected McpToolResult handleUninstall(Map<String, Object> args) {
        String packageId = (String) args.get("packageId");
        boolean ok = uninstallSoftware(packageId);
        return ok ? McpToolResult.success(Map.of("packageId", packageId, "uninstalled", true))
                : McpToolResult.error("卸载失败: " + packageId);
    }

    protected McpToolResult handleListManagers() {
        List<PackageManager.Type> pms = detectAvailable();
        List<Map<String, Object>> items = new ArrayList<>();
        for (PackageManager.Type pm : pms) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", pm.getCommand());
            item.put("installTemplate", pm.getInstallTemplate());
            items.add(item);
        }
        return McpToolResult.success(Map.of("available", pms.size(), "managers", items));
    }

    // ==================== Skills 定义 ====================

    protected SkillDefinition softwareSearchSkill() {
        return new SkillDefinition(PREFIX + "search",
                "搜索系统软件包，自动检测可用的包管理器（winget/brew/apt/choco 等）并搜索",
                List.of(new SkillArgumentSchema("keyword", "搜索关键词", "string", true, null)),
                args -> toSkillResult(handleSearch(args)));
    }

    protected SkillDefinition softwareInstallSkill() {
        return new SkillDefinition(PREFIX + "install",
                "安装系统软件包",
                List.of(new SkillArgumentSchema("packageId", "包 ID", "string", true, null)),
                args -> toSkillResult(handleInstall(args)));
    }

    protected SkillDefinition softwareUninstallSkill() {
        return new SkillDefinition(PREFIX + "uninstall",
                "卸载系统软件包",
                List.of(new SkillArgumentSchema("packageId", "包 ID", "string", true, null)),
                args -> toSkillResult(handleUninstall(args)));
    }

    protected SkillDefinition listPackageManagersSkill() {
        return new SkillDefinition(PREFIX + "list_managers",
                "列出当前系统可用的包管理器",
                List.of(),
                args -> toSkillResult(handleListManagers()));
    }

    protected SkillResult toSkillResult(McpToolResult mcpResult) {
        if (mcpResult.isSuccess()) {
            return SkillResult.success(mcpResult.getContent());
        }
        return SkillResult.error(mcpResult.getErrorMessage());
    }

    // ==================== Software 操作 ====================

    protected List<SoftwareInfo> searchSoftware(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        List<PackageManager.Type> availablePms = detectAvailable();

        log.info("开始搜索软件: keyword={}, 可用包管理器: {}", keyword, availablePms);
        for (PackageManager.Type pm : availablePms) {
            try {
                List<SoftwareInfo> pmResults = searchWith(pm, keyword);
                results.addAll(pmResults);
                log.info("  {} 返回 {} 个结果", pm.getCommand(), pmResults.size());
            } catch (Exception e) {
                log.warn("  {} 搜索失败: {}", pm.getCommand(), e.getMessage());
            }
        }
        log.info("软件搜索完成: keyword={}, 总结果数={}", keyword, results.size());
        return results;
    }

    protected boolean installSoftware(String packageId) {
        log.info("开始安装软件包: {}", packageId);
        CmdResult result = PackageManager.install(packageId, new LineCallback() {
            @Override
            public void onLine(String line) {
                log.info("  [安装] {}", line);
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("  [安装] 完成, exitCode={}", exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.error("  [安装] 异常: {}", throwable.getMessage());
            }
        });
        boolean ok = result.isSuccess();
        log.info("软件包安装: packageId={}, exitCode={}, success={}", packageId, result.getExitCode(), ok);
        if (!ok) {
            log.warn("安装失败: {}", result.getStderr());
        }
        return ok;
    }

    protected boolean uninstallSoftware(String packageId) {
        log.info("开始卸载软件包: {}", packageId);
        List<PackageManager.Type> availablePms = detectAvailable();
        if (availablePms.isEmpty()) {
            log.warn("未检测到可用的包管理器");
            return false;
        }
        PackageManager.Type pm = availablePms.get(0);
        String cmd = getUninstallCommand(pm, packageId);
        log.info("使用 {} 卸载: {}", pm.getCommand(), cmd);
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 300, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                log.info("  [卸载] {}", line);
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("  [卸载] 完成, exitCode={}", exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.error("  [卸载] 异常: {}", throwable.getMessage());
            }
        });
        boolean ok = result.isSuccess();
        log.info("软件包卸载: packageId={}, exitCode={}, success={}", packageId, result.getExitCode(), ok);
        return ok;
    }

    // ==================== 包管理器搜索 ====================

    protected List<PackageManager.Type> detectAvailable() {
        List<PackageManager.Type> available = PackageManager.detect();
        log.info("检测到 {} 个可用包管理器: {}", available.size(),
                available.stream().map(PackageManager.Type::getCommand).toList());
        return available;
    }

    protected List<SoftwareInfo> searchWith(PackageManager.Type pm, String keyword) {
        String searchCmd = getSearchCommand(pm, keyword);
        log.info("  [{}] 执行搜索: {}", pm.getCommand(), searchCmd);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(searchCmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
                log.debug("  [{}] {}", pm.getCommand(), line);
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("  [{}] 搜索完成, exitCode={}", pm.getCommand(), exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("  [{}] 搜索异常: {}", pm.getCommand(), throwable.getMessage());
            }
        });
        if (!result.isSuccess()) {
            log.warn("  [{}] 搜索命令执行失败: exitCode={}", pm.getCommand(), result.getExitCode());
            return List.of();
        }
        String output = outputBuffer.toString();
        if (output.isBlank()) {
            return List.of();
        }
        return parseSearchOutput(pm, output);
    }

    protected String getSearchCommand(PackageManager.Type pm, String keyword) {
        return switch (pm) {
            case WINGET -> "winget search \"" + keyword + "\" --accept-source-agreements";
            case CHOCO -> "choco search " + keyword;
            case BREW -> "brew search " + keyword;
            case APT -> "apt search " + keyword;
            case YUM -> "yum search " + keyword;
            case DNF -> "dnf search " + keyword;
            case APK -> "apk search " + keyword;
        };
    }

    protected String getUninstallCommand(PackageManager.Type pm, String packageId) {
        return switch (pm) {
            case WINGET -> "winget uninstall --id " + packageId + " --silent";
            case CHOCO -> "choco uninstall -y " + packageId;
            case BREW -> "brew uninstall " + packageId;
            case APT -> "DEBIAN_FRONTEND=noninteractive apt remove -y " + packageId;
            case YUM -> "yum remove -y " + packageId;
            case DNF -> "dnf remove -y " + packageId;
            case APK -> "apk del " + packageId;
        };
    }

    // ==================== 输出解析 ====================

    protected List<SoftwareInfo> parseSearchOutput(PackageManager.Type pm, String output) {
        return switch (pm) {
            case WINGET -> parseWingetOutput(output);
            case CHOCO -> parseChocoOutput(output);
            case BREW -> parseBrewOutput(output);
            case APT -> parseAptOutput(output);
            case YUM, DNF -> parseYumOutput(output);
            case APK -> parseApkOutput(output);
        };
    }

    protected List<SoftwareInfo> parseWingetOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        String[] lines = output.split("\\r?\\n");
        boolean headerFound = false;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (!headerFound) {
                if (line.contains("Name") && line.contains("Id") && line.contains("Version")) {
                    headerFound = true;
                }
                continue;
            }
            String[] parts = line.trim().split("\\s{2,}");
            if (parts.length >= 3) {
                String name = parts[0].trim();
                String id = parts[1].trim();
                String version = parts[2].trim();
                String desc = parts.length >= 4 ? parts[3].trim() : "";
                results.add(new SoftwareInfo(name, version, "winget", desc, id));
            }
        }
        return results;
    }

    protected List<SoftwareInfo> parseChocoOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("Chocolatey") || line.contains("packages found")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                String name = parts[0].trim();
                String version = parts[1].trim();
                String desc = parts.length >= 3 ? line.substring(line.indexOf(version) + version.length()).trim() : "";
                results.add(new SoftwareInfo(name, version, "choco", desc, name));
            }
        }
        return results;
    }

    protected List<SoftwareInfo> parseBrewOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("==>")) {
                continue;
            }
            results.add(new SoftwareInfo(trimmed, "", "brew", "", trimmed));
        }
        return results;
    }

    protected List<SoftwareInfo> parseAptOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("Sorting")) {
                continue;
            }
            int slashIndex = trimmed.indexOf('/');
            if (slashIndex > 0) {
                String name = trimmed.substring(0, slashIndex).trim();
                int dashIndex = trimmed.indexOf(" - ", slashIndex);
                String desc = dashIndex > 0 ? trimmed.substring(dashIndex + 3).trim() : "";
                results.add(new SoftwareInfo(name, "", "apt", desc, name));
            }
        }
        return results;
    }

    protected List<SoftwareInfo> parseYumOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.contains("====") || trimmed.contains("Warning")) {
                continue;
            }
            int colonIndex = trimmed.indexOf(" : ");
            if (colonIndex > 0) {
                String name = trimmed.substring(0, colonIndex).trim();
                String desc = trimmed.substring(colonIndex + 3).trim();
                results.add(new SoftwareInfo(name, "", "yum", desc, name));
            } else {
                String[] parts = trimmed.split("\\.");
                if (parts.length > 0) {
                    String name = parts[parts.length - 1].trim();
                    if (!name.isBlank()) {
                        results.add(new SoftwareInfo(name, "", "yum", "", trimmed));
                    }
                }
            }
        }
        return results;
    }

    protected List<SoftwareInfo> parseApkOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int dashIndex = trimmed.indexOf('-');
            if (dashIndex > 0) {
                String name = trimmed.substring(0, dashIndex).trim();
                String version = trimmed.substring(dashIndex + 1).trim();
                results.add(new SoftwareInfo(name, version, "apk", "", name));
            } else {
                results.add(new SoftwareInfo(trimmed, "", "apk", "", trimmed));
            }
        }
        return results;
    }
}