package com.chua.remote.support.agent.launch;

import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Agent 配置
 * <p>优先级: 命令行参数 &gt; 环境变量 &gt; agent.properties 配置文件 &gt; 默认值</p>
 *
 * @author CH
 */@Getter
@Setter
public class AgentProperties {
    private String gatewayHost = "127.0.0.1";
    private int gatewayPort = 9001;
    private String agentId = UUID.randomUUID().toString().replace("-", "");
    private String agentSecret = "gateway-agent-secret";
    private String verifyCode = "";
    private List<String> protocols = Arrays.asList("SSH", "DESKTOP");
    private List<String> codecs = List.of("H264");
    /** 传输协议 */
    private String transport = "TCP";
    /**
     * 连接 Gateway 失败后的最大重试次数，&lt;=0 表示无限重试。
     * 该值同时作用于首次连接和断线后的重连。
     */
    private int reconnectMaxAttempts = 0;

    /**
     * @return 解析后的传输协议列表，始终包含 TCP
     */
    public List<String> getTransports() {
        if (transport == null || transport.isBlank()) { return List.of("TCP"); }
        String[] parts = transport.split(",");
        boolean hasTcp = false;
        for (String p : parts) {
            if ("TCP".equalsIgnoreCase(p.trim())) { hasTcp = true; break; }
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim().toUpperCase();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) { result.add(trimmed); }
        }
        if (!hasTcp) { result.add(0, "TCP"); }
        return result;
    }
    private Map<String, String> capabilities = Map.of(
            "os.name", System.getProperty("os.name", "unknown"),
            "os.arch", System.getProperty("os.arch", "unknown"),
            "os.version", System.getProperty("os.version", "unknown")
    );

    /**
     * 从命令行参数解析配置（同时加载配置文件和环境变量）
     * @param args 参数
     * @return 配置对象
     */
    public static AgentProperties fromCommandLine(String[] args) {
        AgentProperties p = new AgentProperties();
        loadFromConfigFile(p);
        applyEnvVars(p);
        Map<String, String> capabilities = new LinkedHashMap<>(p.getCapabilities());
        for (String arg : args) {
            if (arg.startsWith("--gateway.host=")) { p.setGatewayHost(arg.split("=", 2)[1]); }
            if (arg.startsWith("--gateway.port=")) { p.setGatewayPort(Integer.parseInt(arg.split("=", 2)[1])); }
            if (arg.startsWith("--agent.id=")) { p.setAgentId(arg.split("=", 2)[1]); }
            if (arg.startsWith("--agent.secret=")) { p.setAgentSecret(arg.split("=", 2)[1]); }
            if (arg.startsWith("--agent.verify-code=")) { p.setVerifyCode(arg.split("=", 2)[1]); }
            if (arg.startsWith("--agent.protocols=")) { p.setProtocols(Arrays.asList(arg.split("=", 2)[1].split(","))); }
            if (arg.startsWith("--agent.codec=")) { p.setCodecs(List.of(arg.split("=", 2)[1])); }
            if (arg.startsWith("--agent.transport=")) { p.setTransport(arg.split("=", 2)[1].toUpperCase()); }
            if (arg.startsWith("--agent.reconnect.max-attempts=")) { p.setReconnectMaxAttempts(Integer.parseInt(arg.split("=", 2)[1])); }
            if (arg.startsWith("--agent.reconnect.maxAttempts=")) { p.setReconnectMaxAttempts(Integer.parseInt(arg.split("=", 2)[1])); }
            if (arg.startsWith("--agent.capability.")) { applyCapability(capabilities, arg.substring("--agent.capability.".length())); }
            if (arg.startsWith("--agent.capabilities=")) { applyCapabilities(capabilities, arg.split("=", 2)[1]); }
        }
        p.setCapabilities(capabilities);
        return p;
    }

    /**
     * 仅从环境变量和配置文件加载配置
     * @return 配置对象
     */
    public static AgentProperties fromEnvironment() {
        AgentProperties p = new AgentProperties();
        loadFromConfigFile(p);
        applyEnvVars(p);
        return p;
    }

    private static void applyEnvVars(AgentProperties p) {
        String host = System.getenv("GATEWAY_HOST");
        if (host != null && !host.isBlank()) { p.setGatewayHost(host); }
        String port = System.getenv("GATEWAY_PORT");
        if (port != null && !port.isBlank()) { p.setGatewayPort(Integer.parseInt(port)); }
        String agentId = System.getenv("AGENT_ID");
        if (agentId != null && !agentId.isBlank()) { p.setAgentId(agentId); }
        String secret = System.getenv("AGENT_SECRET");
        if (secret != null && !secret.isBlank()) { p.setAgentSecret(secret); }
        String protocols = System.getenv("AGENT_PROTOCOLS");
        if (protocols != null && !protocols.isBlank()) { p.setProtocols(Arrays.asList(protocols.split(","))); }
        String transport = System.getenv("AGENT_TRANSPORT");
        if (transport != null && !transport.isBlank()) { p.setTransport(transport.toUpperCase()); }
        String verifyCode = System.getenv("AGENT_VERIFY_CODE");
        if (verifyCode != null && !verifyCode.isBlank()) { p.setVerifyCode(verifyCode); }
    }

    private static void loadFromConfigFile(AgentProperties p) {
        java.util.Properties props = new java.util.Properties();
        for (File configFile : findConfigFiles()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            } catch (Exception ignored) {
            }
        }
        try (InputStream is = AgentProperties.class.getClassLoader().getResourceAsStream("agent.properties")) {
            if (is != null) {
                java.util.Properties defaultProps = new java.util.Properties();
                defaultProps.load(is);
                for (String key : defaultProps.stringPropertyNames()) {
                    props.putIfAbsent(key, defaultProps.getProperty(key));
                }
            }
        } catch (Exception ignored) {
        }
        applyProperties(p, props);
    }

    private static List<File> findConfigFiles() {
        List<File> files = new java.util.ArrayList<>();
        File appDir = getAppDirectory();
        if (appDir != null) {
            File f = new File(appDir, "agent.properties");
            if (f.exists()) { files.add(f); }
        }
        File cwd = new File("agent.properties");
        if (cwd.exists()) { files.add(cwd); }
        File home = new File(System.getProperty("user.home"), ".agent.properties");
        if (home.exists()) { files.add(home); }
        return files;
    }

    private static File getAppDirectory() {
        String appHome = System.getProperty("agent.app.home");
        if (appHome != null && !appHome.isBlank()) {
            return new File(appHome);
        }
        String jarPath = AgentProperties.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (jarPath != null) {
            File jarFile = new File(jarPath);
            if (jarFile.isFile() && jarFile.getParentFile() != null) {
                return jarFile.getParentFile();
            }
        }
        return null;
    }

    private static void applyProperties(AgentProperties p, Properties props) {
        String host = props.getProperty("gateway.host");
        if (host != null && !host.isBlank()) { p.setGatewayHost(host); }
        String port = props.getProperty("gateway.port");
        if (port != null && !port.isBlank()) { p.setGatewayPort(Integer.parseInt(port)); }
        String agentId = props.getProperty("agent.id");
        if (agentId != null && !agentId.isBlank()) { p.setAgentId(agentId); }
        String secret = props.getProperty("agent.secret");
        if (secret != null && !secret.isBlank()) { p.setAgentSecret(secret); }
        String protocols = props.getProperty("agent.protocols");
        if (protocols != null && !protocols.isBlank()) { p.setProtocols(Arrays.asList(protocols.split(","))); }
        String transport = props.getProperty("agent.transport");
        if (transport != null && !transport.isBlank()) { p.setTransport(transport.toUpperCase()); }
        String verifyCode = props.getProperty("agent.verify-code");
        if (verifyCode != null && !verifyCode.isBlank()) { p.setVerifyCode(verifyCode); }
        String reconnect = props.getProperty("agent.reconnect.max-attempts");
        if (reconnect != null && !reconnect.isBlank()) { p.setReconnectMaxAttempts(Integer.parseInt(reconnect)); }
        Map<String, String> caps = new LinkedHashMap<>(p.getCapabilities());
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("rustdesk.")) {
                caps.put(key, props.getProperty(key));
            }
        }
        p.setCapabilities(caps);
    }

    private static void applyCapabilities(Map<String, String> capabilities, String value) {
        if (value == null || value.isBlank()) { return; }
        for (String item : value.split(",")) {
            applyCapability(capabilities, item);
        }
    }

    private static void applyCapability(Map<String, String> capabilities, String pair) {
        if (pair == null) { return; }
        int index = pair.indexOf('=');
        if (index <= 0) { return; }
        String key = pair.substring(0, index).trim();
        if (key.isEmpty()) { return; }
        capabilities.put(key, pair.substring(index + 1));
    }
}
