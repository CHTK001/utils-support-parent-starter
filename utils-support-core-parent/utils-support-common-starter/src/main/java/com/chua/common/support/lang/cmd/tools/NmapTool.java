package com.chua.common.support.lang.cmd.tools;

import com.chua.common.support.lang.cmd.CliTool;
import com.chua.common.support.lang.cmd.CliToolDescriptor;
import com.chua.common.support.lang.cmd.CliVersion;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;

import javax.annotation.Nonnull;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 网络端口扫描工具 nmap。
 *
 * <p>通过 {@link CliTool} 统一定位 nmap 可执行文件、探测版本，
 * 并提供常用扫描参数的便捷方法。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * NmapTool nmap = new NmapTool();
 * if (nmap.isAvailable()) {
 *     CmdResult result = nmap.tcpConnectScan("192.168.1.1");
 *     List<PortInfo> ports = nmap.parseTcpOutput(result.getStdout());
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("nmap")
public class NmapTool extends CliTool {

    /** nmap 版本输出格式：Nmap version 7.94 ( https://nmap.org ) */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("Nmap version (\\d[\\d.]*)");

    /** 创建 nmap 工具实例，使用预置的工具描述。 */
    public NmapTool() {
        super(CliToolDescriptor.builder("nmap")
                .displayName("网络端口扫描工具 nmap")
                .windowsExecutable("nmap.exe")
                .envKey("NMAP_BIN")
                .candidateDirs(
                        "C:\\Program Files (x86)\\Nmap",
                        "C:\\Program Files\\Nmap",
                        "/usr/bin",
                        "/usr/local/bin",
                        "/opt/local/bin")
                .versionArgs("--version")
                .versionPattern(VERSION_PATTERN)
                .minVersion(CliVersion.of(7, 0))
                .defaultTimeout(120, TimeUnit.SECONDS)
                .installPackage("nmap")
                .build());
    }

    /**
     * TCP Connect 全端口扫描（-sT --open -T4 -p-）。
     *
     * @param host 目标 IP 或域名
     * @return 执行结果，stdout 为 nmap 标准输出
     */
    @Nonnull
    public CmdResult tcpConnectScan(@Nonnull String host) {
        return execute(120, TimeUnit.SECONDS,
                "-sT", "--open", "-T4", "-p-", host);
    }

    /**
     * TCP Connect 快速扫描常用端口（-sT --open -T4）。
     *
     * @param host 目标 IP 或域名
     * @return 执行结果
     */
    @Nonnull
    public CmdResult tcpQuickScan(@Nonnull String host) {
        return execute(30, TimeUnit.SECONDS,
                "-sT", "--open", "-T4", host);
    }

    /**
     * 解析 nmap 标准输出中的开放端口列表。
     *
     * <p>支持两种输出格式：
     * <ul>
     *   <li>标准格式：{@code 22/tcp open ssh}</li>
     *   <li>XML 格式（通过 stdout 含 portid 解析）</li>
     * </ul>
     *
     * @param stdout nmap 标准输出
     * @return 解析出的端口信息数组（JSON 序列化后存库）
     */
    @Nonnull
    public java.util.List<java.util.Map<String, String>> parseOpenPorts(@Nonnull String stdout) {
        java.util.List<java.util.Map<String, String>> ports = new java.util.ArrayList<>();
        if (stdout == null || stdout.isEmpty()) {
            return ports;
        }
        // 标准输出格式：22/tcp open ssh
        // 或含端口范围：80-82/tcp open http
        java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile(
                "(\\d+(?:-\\d+)?)/(\\w+)\\s+open\\s+(\\S+)(?:\\s+(.*))?");
        for (String line : stdout.split("\\r?\\n")) {
            java.util.regex.Matcher m = linePattern.matcher(line.trim());
            if (m.find()) {
                String portSpec = m.group(1);
                String protocol = m.group(2);
                String service = m.group(3);
                String version = m.group(4);

                // 处理端口范围（如 80-82），拆分为单个端口
                if (portSpec.contains("-")) {
                    String[] parts = portSpec.split("-");
                    int start = Integer.parseInt(parts[0]);
                    int end = Integer.parseInt(parts[1]);
                    for (int p = start; p <= end; p++) {
                        ports.add(buildPortEntry(String.valueOf(p), protocol, service, version));
                    }
                } else {
                    ports.add(buildPortEntry(portSpec, protocol, service, version));
                }
            }
        }
        return ports;
    }

    private java.util.Map<String, String> buildPortEntry(String port, String protocol,
                                                          String service, String version) {
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("port", port);
        entry.put("protocol", protocol);
        entry.put("service", service);
        if (version != null && !version.isBlank()) {
            entry.put("version", version.trim());
        }
        return entry;
    }
}
